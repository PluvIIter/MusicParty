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
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.StreamStatusEvent;
import org.thornex.musicparty.service.LocalCacheService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 直播流（radio）核心服务。
 * <p>
 * 架构：单 ffmpeg 进程共享编码 → {@link StreamBroadcaster} 非阻塞 fan-out → 每个收听者独立缓冲 + 虚拟线程泵。
 * <p>
 * 修复了原实现的三个并发缺陷：
 * <ol>
 *   <li><b>重启抖动</b>：只在【源变化 / 明显 seek / 进程未运行】时重启转码，听众加入/离开、普通房间事件不触发。</li>
 *   <li><b>头阻塞</b>：广播循环只做非阻塞 {@code offer}，慢客户端只影响自身的泵线程。</li>
 *   <li><b>线程泄漏</b>：连接由容器的 {@code ResponseBodyEmitter} 管理，经 onCompletion/onTimeout/onError 清理。</li>
 * </ol>
 */
@Service
@Slf4j
public class LiveStreamService {

    /** 同源 seek 重启的最小间隔，防止位置事件风暴导致反复重启 */
    private static final long MIN_SEEK_RESTART_INTERVAL_MS = 5000;
    /** ffmpeg 意外退出后的重启冷却，防止网络源 rebuffer 崩溃循环 */
    private static final long CRASH_RESTART_BACKOFF_MS = 30000;

    private final LocalCacheService localCacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;

    // 开关状态（管理员面板控制）
    private final AtomicBoolean isEnabled = new AtomicBoolean(false);
    // 是否有客户端连接
    private final AtomicBoolean hasListeners = new AtomicBoolean(false);

    // 播放器状态镜像（由 PlayerStateEvent 驱动）
    private volatile PlayableMusic currentMusic;
    private volatile boolean isPaused = true;
    private volatile long currentPosition = 0;

    // FFmpeg 进程管理（volatile：读者线程在 readLoop 中会跨线程比较）
    private volatile Process transcoderProcess;
    private ExecutorService streamExecutor;

    // 广播器：非阻塞分发给所有客户端
    private final StreamBroadcaster broadcaster = new StreamBroadcaster();

    // 统计唯一收听人数（按 IP 地址去重）
    private final Map<String, Integer> ipConnectionCount = new ConcurrentHashMap<>();

    // 转码会话状态（用于"仅在必要时重启"的判断）
    private volatile String runningSourceKey;
    private volatile long runningStartPosMs;
    private volatile long runningStartTimeMs;
    private volatile boolean hasProducedFirstByte;
    private volatile long lastSeekRestartTimeMs;
    private volatile long lastCrashRestartTimeMs;

    public LiveStreamService(LocalCacheService localCacheService, ApplicationEventPublisher eventPublisher, AppProperties appProperties) {
        this.localCacheService = localCacheService;
        this.eventPublisher = eventPublisher;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        streamExecutor = Executors.newCachedThreadPool();
        broadcaster.setOnClientRemoved(this::handleClientRemoved);
    }

    private void handleClientRemoved(StreamClient client) {
        // ipCounted 保证清理恰好执行一次（onCompletion 会在所有终态路径触发）
        if (!client.ipCounted.getAndSet(false)) {
            return;
        }
        if (client.getClientIp() != null) {
            ipConnectionCount.computeIfPresent(client.getClientIp(), (k, v) -> v > 1 ? v - 1 : null);
        }
        int currentCount = getStreamListenerCount();
        if (currentCount == 0) {
            if (hasListeners.compareAndSet(true, false)) {
                eventPublisher.publishEvent(new StreamStatusEvent(this, false, 0));
            }
            checkState();
        } else {
            eventPublisher.publishEvent(new StreamStatusEvent(this, true, currentCount));
        }
    }

    @PreDestroy
    public void cleanup() {
        stopTranscoding();
        broadcaster.closeAll();
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

    /** 唯一收听人数（按 IP 去重）。PlayerState 与 StreamStatusEvent 消费方依赖此语义。 */
    public int getStreamListenerCount() {
        return ipConnectionCount.size();
    }

    /**
     * 注册一个收听者连接。容量不足时返回 false（由控制器关闭该连接）。
     * 容量判定放在这里原子执行，避免控制器先查后加的竞态。
     */
    public synchronized boolean addListener(StreamClient client) {
        int maxClients = appProperties.getStream().getMaxClients();
        if (broadcaster.getClientCount() >= maxClients) {
            log.warn("Stream: max clients reached ({}), rejecting connection", maxClients);
            return false;
        }
        hasListeners.set(true);
        broadcaster.addClient(client);
        if (client.getClientIp() != null) {
            client.ipCounted.set(true);
            ipConnectionCount.merge(client.getClientIp(), 1, Integer::sum);
        }
        eventPublisher.publishEvent(new StreamStatusEvent(this, true, getStreamListenerCount()));
        checkState();
        return true;
    }

    /** 移除一个收听者连接。幂等：重复调用 / 并发终态回调汇聚到同一路径，清理恰好一次。 */
    public void removeListener(StreamClient client) {
        client.close();
        if (broadcaster.removeClient(client)) {
            // handleClientRemoved 已通过回调执行
        }
    }

    // --- Event Handling ---

    @EventListener
    public void onPlayerState(PlayerStateEvent event) {
        if (!isEnabled.get()) {
            return;
        }
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

    // --- Core State Machine ---

    private synchronized void checkState() {
        boolean shouldRun = isEnabled.get() && hasListeners.get() && currentMusic != null && !isPaused && resolveTarget() != null;
        if (shouldRun) {
            startTranscodingIfNeeded();
        } else {
            stopTranscoding();
        }
    }

    /**
     * 仅在真正需要时启动/重启转码：
     * <ul>
     *   <li>进程未运行 → 启动；</li>
     *   <li>源（歌曲/缓存/URL）变化 → 重启（切歌，不受节流限制）；</li>
     *   <li>位置相对实时进度漂移超过阈值 → 重启（seek，带最小间隔）；</li>
     *   <li>其他情况（听众加入/离开、点赞、普通事件）→ 保持运行，不重启。</li>
     * </ul>
     */
    private synchronized void startTranscodingIfNeeded() {
        TranscodeTarget target = resolveTarget();
        if (target == null) {
            stopTranscoding();
            return;
        }
        boolean alive = transcoderProcess != null && transcoderProcess.isAlive();
        if (!alive) {
            startTranscoding(target);
            return;
        }
        if (!target.key().equals(runningSourceKey)) {
            log.info("Stream: source changed -> restarting for {}", currentMusic.name());
            doRestart();
            return;
        }
        if (hasProducedFirstByte) {
            long expectedLive = runningStartPosMs + (System.currentTimeMillis() - runningStartTimeMs);
            if (Math.abs(currentPosition - expectedLive) > appProperties.getStream().getSeekThresholdMs()) {
                restartForSeek();
            }
        }
    }

    /** 切歌 / 源变化：立即重启，不受节流限制。 */
    private synchronized void doRestart() {
        stopTranscoding();
        TranscodeTarget target = resolveTarget();
        if (target != null) {
            startTranscoding(target);
        }
    }

    /** seek（同源漂移）：带最小间隔，防位置事件风暴反复重启。 */
    private synchronized void restartForSeek() {
        long now = System.currentTimeMillis();
        if (now - lastSeekRestartTimeMs < MIN_SEEK_RESTART_INTERVAL_MS) {
            log.debug("Stream: seek restart throttled");
            return;
        }
        lastSeekRestartTimeMs = now;
        log.info("Stream: position drifted -> restarting at {}ms", currentPosition);
        doRestart();
    }

    private synchronized void startTranscoding(TranscodeTarget target) {
        stopTranscoding();

        runningSourceKey = target.key();
        runningStartPosMs = currentPosition;
        hasProducedFirstByte = false;

        double startSeconds = currentPosition / 1000.0;

        List<String> command = new ArrayList<>();
        command.add(appProperties.getFfmpegPath());

        // 网络源需要携带的请求头（如 Bilibili 防盗链）
        if (target.input().startsWith("http") && !target.headers().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            target.headers().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
            command.add("-headers");
            command.add(sb.toString());
        }
        command.add("-ss");
        command.add(String.format(Locale.US, "%.2f", startSeconds));
        command.add("-re");
        command.add("-i");
        command.add(target.input());
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

        log.info("Stream: starting transcoding for {} at {}ms", currentMusic.name(), currentPosition);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            transcoderProcess = pb.start();
            streamExecutor.submit(() -> readLoop(transcoderProcess, target));
        } catch (IOException e) {
            log.error("Stream: failed to start ffmpeg", e);
            transcoderProcess = null;
        }
    }

    private synchronized void stopTranscoding() {
        // 清空各客户端缓冲，丢弃暂停/切歌前的过期音频
        broadcaster.flushAll();
        if (transcoderProcess != null) {
            if (transcoderProcess.isAlive()) {
                transcoderProcess.destroy();
            }
            transcoderProcess = null;
        }
    }

    /**
     * 读取 ffmpeg stdout 并分发给所有客户端。
     * 运行在平台线程池（读管道在原生阻塞，虚拟线程无收益）。
     */
    private void readLoop(Process process, TranscodeTarget target) {
        int chunkSize = appProperties.getStream().getChunkSizeBytes();
        try (InputStream is = process.getInputStream()) {
            byte[] buf = new byte[chunkSize];
            int n;
            while ((n = is.read(buf)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (n <= 0) {
                    continue;
                }
                // 锚定"首个输出字节"的时刻：网络源启动期（1-5s）内的任意事件不应触发 seek 重启
                if (!hasProducedFirstByte && process == transcoderProcess) {
                    hasProducedFirstByte = true;
                    runningStartTimeMs = System.currentTimeMillis();
                }
                broadcaster.broadcast(Arrays.copyOf(buf, n));
            }
        } catch (IOException e) {
            // 进程被杀或管道关闭，忽略
        } finally {
            log.debug("Stream: transcoding finished/stopped.");
            handleTranscoderExit();
        }
    }

    /** ffmpeg 意外退出时的自愈看门狗。主动 stopTranscoding 会将字段置 null，不会误触发。 */
    private synchronized void handleTranscoderExit() {
        Process process = transcoderProcess;
        if (process != null && !process.isAlive()) {
            transcoderProcess = null;
            boolean shouldRun = isEnabled.get() && hasListeners.get() && currentMusic != null && !isPaused && resolveTarget() != null;
            if (shouldRun) {
                long now = System.currentTimeMillis();
                if (now - lastCrashRestartTimeMs < CRASH_RESTART_BACKOFF_MS) {
                    log.error("Stream: transcoder died repeatedly, backing off restart");
                    return;
                }
                lastCrashRestartTimeMs = now;
                log.warn("Stream: transcoder died unexpectedly, restarting");
                doRestart();
            }
        }
    }

    /**
     * 解析当前歌曲的转码源。优先本地缓存文件，否则使用网络 URL。
     *
     * @return 转码目标；源未就绪（PENDING_DOWNLOAD / 空 URL）时返回 null
     */
    private TranscodeTarget resolveTarget() {
        LocalCacheService.CacheEntry entry = localCacheService.getCacheEntry(currentMusic.id());
        if (entry != null && entry.getStatus() == CacheStatus.COMPLETED) {
            Path filePath = Paths.get(LocalResourceConfig.CACHE_DIR, entry.getFileName());
            if (Files.exists(filePath)) {
                return new TranscodeTarget(
                        filePath.toAbsolutePath().toString(),
                        Map.of(),
                        "file:" + entry.getFileName() + "#" + currentMusic.id());
            }
        }

        String url = currentMusic.url();
        if (url == null || url.isEmpty() || "PENDING_DOWNLOAD".equals(url)) {
            log.warn("Stream: music source not ready for {}", currentMusic.name());
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        if ("bilibili".equals(currentMusic.platform())) {
            headers.put("Referer", "https://www.bilibili.com/");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }
        // key 必须含 music id：两首歌可能共用同一 URL
        return new TranscodeTarget(url, headers, "net:" + currentMusic.platform() + ":" + url + "#" + currentMusic.id());
    }

    private record TranscodeTarget(String input, Map<String, String> headers, String key) {
    }
}
