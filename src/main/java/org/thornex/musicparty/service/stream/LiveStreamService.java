package org.thornex.musicparty.service.stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.config.LocalResourceConfig;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.StreamStatusEvent;
import org.thornex.musicparty.service.LocalCacheService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class LiveStreamService {

    private final LocalCacheService localCacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    
    // 开关状态 (可以通过 AdminController 修改)
    private final AtomicBoolean isEnabled = new AtomicBoolean(false);
    
    // 是否有客户端连接
    private final AtomicBoolean hasListeners = new AtomicBoolean(false);

    // 播放器状态
    private volatile PlayableMusic currentMusic;
    private volatile boolean isPaused = true;
    private volatile long currentPosition = 0;

    // FFmpeg 进程管理
    private Process transcoderProcess;
    private ExecutorService streamExecutor;
    
    // 广播器：负责将转码后的数据分发给所有 HTTP 客户端
    private final StreamBroadcaster broadcaster = new StreamBroadcaster();

    public LiveStreamService(LocalCacheService localCacheService, ApplicationEventPublisher eventPublisher, AppProperties appProperties) {
        this.localCacheService = localCacheService;
        this.eventPublisher = eventPublisher;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        streamExecutor = Executors.newCachedThreadPool();
    }

    @PreDestroy
    public void cleanup() {
        stopTranscoding();
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
        }
    }

    // --- Public Control Methods ---

    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        log.info("LiveStreamService enabled: {}", enabled);
        checkState();
    }
    
    public boolean isEnabled() {
        return isEnabled.get();
    }

    public void addListener(OutputStream outputStream) {
        boolean previouslyHadNoListeners = !hasListeners.get();
        hasListeners.set(true);
        broadcaster.addClient(outputStream);
        
        if (previouslyHadNoListeners) {
            eventPublisher.publishEvent(new StreamStatusEvent(this, true));
        }
        
        checkState();
    }

    public void removeListener(OutputStream outputStream) {
        broadcaster.removeClient(outputStream);
        if (broadcaster.getClientCount() == 0) {
            if (hasListeners.compareAndSet(true, false)) {
                eventPublisher.publishEvent(new StreamStatusEvent(this, false));
            }
            // 无人收听时，延迟关闭或立即关闭？为了节省资源，这里选择立即停止转码
            // 如果希望保留一点缓冲，可以加个延迟任务
            checkState();
        }
    }

    // --- Event Handling ---

    @EventListener
    public void onPlayerState(PlayerStateEvent event) {
        if (!isEnabled.get()) return;

        var state = event.getState();
        this.isPaused = state.isPaused();
        
        if (state.nowPlaying() != null) {
            this.currentMusic = state.nowPlaying().music();
            this.currentPosition = state.nowPlaying().currentPosition();
        } else {
            this.currentMusic = null;
            this.currentPosition = 0;
        }

        checkState();
    }

    // --- Core Logic ---

    private synchronized void checkState() {
        boolean shouldRun = isEnabled.get() && hasListeners.get() && currentMusic != null && !isPaused;

        if (shouldRun) {
            startTranscodingIfNotRunning();
        } else {
            stopTranscoding();
            // 如果是因为暂停导致的停止，我们可以发送静音帧来保持连接不断开
            // 但为了简化，暂且让它静默 (StreamBroadcaster 会处理空闲时的静音数据填充)
            if (hasListeners.get()) {
                 broadcaster.sendSilence();
            }
        }
    }

    private synchronized void startTranscodingIfNotRunning() {
        // 如果当前这首歌正在转码，就不重启进程
        // 这里简化处理：每次状态改变（切歌、Seek）都重启转码进程以同步进度
        
        String inputSource = null;
        java.util.Map<String, String> httpHeaders = new java.util.HashMap<>();

        // 1. 尝试使用本地缓存 (优先)
        LocalCacheService.CacheEntry entry = localCacheService.getCacheEntry(currentMusic.id());
        if (entry != null && entry.getStatus() == org.thornex.musicparty.enums.CacheStatus.COMPLETED) {
            Path filePath = Paths.get(LocalResourceConfig.CACHE_DIR, entry.getFileName());
            if (Files.exists(filePath)) {
                inputSource = filePath.toAbsolutePath().toString();
                log.debug("Stream: Using local file for {}", currentMusic.name());
            }
        }

        // 2. 如果本地没有，使用网络 URL
        if (inputSource == null) {
            String url = currentMusic.url();
            if (url == null || url.isEmpty() || "PENDING_DOWNLOAD".equals(url)) {
                log.warn("Stream: Music source not ready for {}", currentMusic.name());
                stopTranscoding();
                return;
            }
            inputSource = url;
            log.debug("Stream: Using network URL for {}", currentMusic.name());

            // 针对特定平台添加必要 Header
            if ("bilibili".equals(currentMusic.platform())) {
                httpHeaders.put("Referer", "https://www.bilibili.com/");
                httpHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            }
        }

        stopTranscoding(); // 先杀掉旧的

        log.info("Stream: Starting transcoding for {} at {}ms", currentMusic.name(), currentPosition);

        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(appProperties.getFfmpegPath());

            // 如果是网络流，添加 Headers
            if (inputSource.startsWith("http") && !httpHeaders.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                httpHeaders.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
                command.add("-headers");
                command.add(sb.toString());
            }

            double startSeconds = currentPosition / 1000.0;
            command.add("-ss");
            command.add(String.format(java.util.Locale.US, "%.2f", startSeconds));
            command.add("-re");
            command.add("-i");
            command.add(inputSource);
            
            // 输出参数
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-ab");
            command.add("128k");
            command.add("-ac");
            command.add("2");
            command.add("-ar");
            command.add("44100");
            command.add("-f");
            command.add("mp3");
            command.add("pipe:1");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            transcoderProcess = pb.start();
            
            // 异步读取 stdout 并写入 broadcaster
            streamExecutor.submit(() -> {
                try (InputStream is = transcoderProcess.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) break;
                        broadcaster.broadcast(buffer, bytesRead);
                    }
                } catch (IOException e) {
                    // 进程被杀时会抛出 Stream closed，忽略
                } finally {
                    log.debug("Stream: Transcoding finished/stopped.");
                }
            });

        } catch (IOException e) {
            log.error("Stream: Failed to start ffmpeg", e);
        }
    }

    private synchronized void stopTranscoding() {
        if (transcoderProcess != null) {
            if (transcoderProcess.isAlive()) {
                transcoderProcess.destroy();
            }
            transcoderProcess = null;
        }
    }
}
