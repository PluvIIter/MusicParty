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
    /** 首字节重锚定时允许吸收的最大启动偏移：超出部分视为"启动窗口内的 seek"，触发重启对齐 */
    private static final long MAX_STARTUP_OFFSET_MS = 8000;

    private final LocalCacheService localCacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;

    // 开关状态（管理员面板控制）
    private final AtomicBoolean isEnabled = new AtomicBoolean(false);

    // 播放器状态镜像（由 PlayerStateEvent 驱动）
    private volatile PlayableMusic currentMusic;
    private volatile boolean isPaused = true;
    private volatile long currentPosition = 0;

    // 最近一次 PlayerStateEvent 的时刻，用于估算播放器实时位置（事件稀疏时避免误判 seek）
    private volatile long lastPlayerEventTimeMs;

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
    private volatile long launchStartPosMs;
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
        // 只在确实计过 IP 时递减去重计数（ipCounted 保证恰好一次）
        if (client.ipCounted.compareAndSet(true, false) && client.getClientIp() != null) {
            ipConnectionCount.computeIfPresent(client.getClientIp(), (k, v) -> v > 1 ? v - 1 : null);
        }
        // 以广播器当前连接数为准判断是否还有收听者，
        // 避免与并发的 addListener 产生"陈旧清空"竞态（旧实现用 CAS 布尔量，会被新连接误清）
        boolean anyConnected = broadcaster.getClientCount() > 0;
        if (anyConnected) {
            eventPublisher.publishEvent(new StreamStatusEvent(this, true, getStreamListenerCount()));
        } else {
            eventPublisher.publishEvent(new StreamStatusEvent(this, false, 0));
            checkState();
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

    /** 当前连接的收听者数量（按连接数）。用于容量判定与 MusicPlayerService 的空闲守卫。 */
    public int getStreamConnectionCount() {
        return broadcaster.getClientCount();
    }

    /**
     * 注册一个收听者连接。容量不足时返回 false（由控制器关闭该连接）。
     * 容量判定放在这里原子执行，避免控制器先查后加的竞态。
     */
    public boolean addListener(StreamClient client) {
        synchronized (this) {
            int maxClients = appProperties.getStream().getMaxClients();
            if (broadcaster.getClientCount() >= maxClients) {
                log.warn("Stream: max clients reached ({}), rejecting connection", maxClients);
                return false;
            }
            broadcaster.addClient(client);
            if (client.getClientIp() != null) {
                client.ipCounted.set(true);
                ipConnectionCount.merge(client.getClientIp(), 1, Integer::sum);
            }
        }
        // 事件与状态机必须在本服务锁外执行：StreamStatusEvent 会同步回调 MusicPlayerService，
        // 若在持锁时派发，会与 playNextInQueue/topSong(持 MPS 锁) → broadcastFullPlayerState
        // → onPlayerState → checkState(取 LSS 锁) 形成锁逆序死锁（事件同步派发，无 @EnableAsync）。
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
        this.lastPlayerEventTimeMs = System.currentTimeMillis();
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

    /**
     * 估算播放器当前实时位置。
     * PlayerStateEvent 只在离散事件时触发，安静房间内本服务的 currentPosition 会陈旧；
     * 播放器未暂停时按墙钟推进，据此估算实时位置，避免把"长时间无事件"误判为 seek。
     */
    private long estimatePlayerPosition() {
        if (lastPlayerEventTimeMs == 0) {
            return currentPosition;
        }
        if (isPaused) {
            return currentPosition;
        }
        return currentPosition + (System.currentTimeMillis() - lastPlayerEventTimeMs);
    }

    private synchronized void checkState() {
        boolean shouldRun = isEnabled.get() && broadcaster.getClientCount() > 0 && currentMusic != null && !isPaused && resolveTarget() != null;
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
            // 同一源近期崩溃过则进入冷却，避免每次事件都重新拉起死源（绕过 handleTranscoderExit 的冷却）
            long now = System.currentTimeMillis();
            if (target.key().equals(runningSourceKey) && now - lastCrashRestartTimeMs < CRASH_RESTART_BACKOFF_MS) {
                log.warn("Stream: source recently crashed, skipping restart (backoff)");
                return;
            }
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
            if (Math.abs(estimatePlayerPosition() - expectedLive) > appProperties.getStream().getSeekThresholdMs()) {
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
        log.info("Stream: position drifted -> restarting at {}ms", estimatePlayerPosition());
        doRestart();
    }

    private synchronized void startTranscoding(TranscodeTarget target) {
        stopTranscoding();

        long launchPos = estimatePlayerPosition();
        runningSourceKey = target.key();
        launchStartPosMs = launchPos;
        runningStartPosMs = launchPos;
        hasProducedFirstByte = false;

        double startSeconds = launchPos / 1000.0;

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

        log.info("Stream: starting transcoding for {} at {}ms", currentMusic.name(), estimatePlayerPosition());

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
                    // 以"首个输出字节"时刻重新锚定：吸收 ffmpeg 启动延迟(网络源可达 1-5s)，
                    // 否则 expectedLive 会恒落后播放器一个固定偏移，导致每个事件都误判 seek。
                    // 锚定量被 MAX_STARTUP_OFFSET_MS 钳制：启动窗口内的大幅 seek 不会被吸收掉，
                    // 仍会被漂移检测捕获并触发重启对齐。
                    long estimate = estimatePlayerPosition();
                    long anchor = launchStartPosMs + Math.max(-MAX_STARTUP_OFFSET_MS,
                            Math.min(MAX_STARTUP_OFFSET_MS, estimate - launchStartPosMs));
                    runningStartPosMs = anchor;
                    runningStartTimeMs = System.currentTimeMillis();
                    // 成功产出 = 源已恢复健康：清除崩溃冷却，避免"已恢复的源"在 30s 内
                    // 因暂停/恢复被错误静默拦截（真正死源不会产出首字节，冷却仍会生效）
                    lastCrashRestartTimeMs = 0;
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
        if (process == null || process.isAlive()) {
            return;
        }
        transcoderProcess = null;

        // 退出码 0 = 歌曲自然播完（ffmpeg EOF），由播放器在 1s 内自然推进下一首，不算崩溃，
        // 不在此处重启（否则会在歌曲末尾反复 -ss 到 EOF 空转，并白白消耗崩溃冷却额度）
        if (process.exitValue() == 0) {
            log.debug("Stream: transcoder reached natural EOF");
            return;
        }

        boolean shouldRun = isEnabled.get() && broadcaster.getClientCount() > 0 && currentMusic != null && !isPaused && resolveTarget() != null;
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
