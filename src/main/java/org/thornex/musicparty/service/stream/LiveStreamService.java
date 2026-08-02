package org.thornex.musicparty.service.stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.config.LocalResourceConfig;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.StreamStatusEvent;
import org.thornex.musicparty.service.LocalCacheService;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.TimeUnit;
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
    /** 退出码 0 时判定"自然播完"的位置容差：位置距歌曲末尾超过该值即视为异常退出，需重启 */
    private static final long NATURAL_END_TOLERANCE_MS = 10000;
    /** 静音填充的分块大小，约等于 128kbps 一秒的数据量 */
    private static final int SILENCE_CHUNK_BYTES = 16 * 1024;
    /** 看门狗：转码器启动后超过该时长仍无首个输出字节 → 判定卡住，重启 */
    private static final long TRANSCODER_STARTUP_TIMEOUT_MS = 15000;
    /** 看门狗：转码器运行中超过该时长无任何输出 → 判定停滞（网络源卡住），重启 */
    private static final long TRANSCODER_STALL_TIMEOUT_MS = 10000;

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
    private volatile long transcoderStartTimeMs;
    private volatile long lastTranscoderOutputMs;
    private ExecutorService streamExecutor;

    // 广播器：非阻塞分发给所有客户端
    private final StreamBroadcaster broadcaster = new StreamBroadcaster();

    // 常驻静音基底：预生成的 MP3 静音，无歌/暂停/转码间隙时广播，保证连接始终能收到数据
    private volatile byte[] silenceChunk;
    private Thread silenceThread;
    // 当前是否有歌曲转码器在产出真实音频（true 时静音填充暂停）
    private final AtomicBoolean songActive = new AtomicBoolean(false);

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
        // 预生成 MP3 静音作为常驻基底，保证任何连接随时能收到数据（不依赖歌曲转码器状态）
        silenceChunk = generateSilence();
        if (silenceChunk != null && silenceChunk.length > 0) {
            silenceThread = Thread.ofPlatform().daemon().name("stream-silence-filler").start(this::silenceLoop);
        } else {
            log.warn("Stream: silence fallback unavailable; idle connections may stall");
        }
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
        if (silenceThread != null) {
            silenceThread.interrupt();
        }
        stopTranscoding();
        broadcaster.closeAll();
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
        }
    }

    /**
     * 一次性生成约 10 秒的 MP3 静音（128kbps 44.1kHz 立体声，与歌曲转码输出格式一致），
     * 供静音填充线程在无歌/暂停/转码间隙时广播。
     */
    private byte[] generateSilence() {
        try {
            List<String> command = List.of(
                    appProperties.getFfmpegPath(),
                    "-loglevel", "error",
                    "-f", "lavfi",
                    "-i", "anullsrc=r=44100:cl=stereo",
                    "-t", "10",
                    "-c:a", "libmp3lame",
                    "-b:a", "128k",
                    "-ac", "2",
                    "-ar", "44100",
                    "-f", "mp3",
                    "pipe:1");
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = process.getInputStream()) {
                is.transferTo(bos);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Stream: silence generation timed out");
                return null;
            }
            byte[] data = bos.toByteArray();
            log.info("Stream: generated {} bytes of silence", data.length);
            return data.length > 0 ? data : null;
        } catch (Exception e) {
            log.warn("Stream: failed to generate silence, silence fallback disabled", e);
            return null;
        }
    }

    /**
     * 静音填充循环：无歌/暂停/转码器启动或死亡的空窗期，按实时码率（约 16KB/s）广播静音，
     * 让已连接的客户端缓冲始终有数据、永不判断流。有歌曲转码器产出时（songActive）自动让位。
     */
    private void silenceLoop() {
        int offset = 0;
        while (!Thread.currentThread().isInterrupted()) {
            boolean shouldBroadcast = isEnabled.get() && broadcaster.getClientCount() > 0
                    && !songActive.get() && silenceChunk != null && silenceChunk.length > 0;
            if (shouldBroadcast) {
                int len = Math.min(SILENCE_CHUNK_BYTES, silenceChunk.length - offset);
                byte[] chunk = new byte[len];
                System.arraycopy(silenceChunk, offset, chunk, 0, len);
                offset = (offset + len) % silenceChunk.length;
                broadcaster.broadcast(chunk);
                try {
                    Thread.sleep(1000); // 16KB/s ≈ 128kbps 实时速率
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
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
        // 转码器不随连接数启停：只要开关开启且有歌在播就持续转码（"常驻热转码"）。
        // 否则新连接会先吃到几秒静音（转码器冷启动），VRC 等不到真实音频就判定失败重试。
        boolean shouldRun = isEnabled.get() && currentMusic != null && !isPaused && resolveTarget() != null;
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
        // 仅在锚点有效（本进程已产出首字节）时才做漂移检测，避免残留旧进程锚点导致伪重启
        if (hasProducedFirstByte && runningStartTimeMs != 0) {
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
        runningStartTimeMs = 0; // 锚点置无效：新进程产出首字节时才会重新锚定
        hasProducedFirstByte = false;

        double startSeconds = launchPos / 1000.0;

        List<String> command = new ArrayList<>();
        command.add(appProperties.getFfmpegPath());
        command.add("-loglevel");
        command.add("error"); // stderr 只输出错误，便于看门狗/日志诊断拉流失败

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
        // HTTP 拉流自动重连：网易云 CDN 偶发切断连接（TLS IO error: End of file），
        // 让 ffmpeg 自动重连继续拉取，而不是直接退出触发看门狗 15s 循环
        command.add("-reconnect");
        command.add("1");
        command.add("-reconnect_delay_max");
        command.add("5");
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
            long now = System.currentTimeMillis();
            transcoderStartTimeMs = now;
            lastTranscoderOutputMs = now;
            ProcessBuilder pb = new ProcessBuilder(command);
            transcoderProcess = pb.start();
            streamExecutor.submit(() -> readLoop(transcoderProcess, target));
            // 异步读取 stderr：ffmpeg 卡住/拉流失败的原因会打到应用日志
            streamExecutor.submit(() -> readErrorLoop(transcoderProcess));
        } catch (IOException e) {
            log.error("Stream: failed to start ffmpeg", e);
            transcoderProcess = null;
        }
    }

    /** 读取 ffmpeg stderr 并打日志（配合 -loglevel error，只有真正的错误会出现）。 */
    private void readErrorLoop(Process process) {
        try (InputStream is = process.getErrorStream();
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("Stream ffmpeg: {}", line);
            }
        } catch (IOException e) {
            // 进程被杀或管道关闭，忽略
        }
    }

    private synchronized void stopTranscoding() {
        // 清空各客户端缓冲，丢弃暂停/切歌前的过期音频
        broadcaster.flushAll();
        // 交还静音基底：此后由静音填充线程维持连接数据流
        songActive.set(false);
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
                lastTranscoderOutputMs = System.currentTimeMillis();
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
                    // 切换到真实音频：标记 songActive 让静音填充让位，
                    // 并清空客户端已缓冲的静音，避免歌前先播一段静音
                    songActive.set(true);
                    broadcaster.flushAll();
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

        // 退出码 0：只在"确实产出过音频 且 位置接近歌曲末尾"时才视为自然播完；
        // 否则（未产出首字节 / 远未到末尾就退出）是异常，需要重启恢复
        // （否则会出现"新歌转码器静默退出 → 流静默死等"，见 mabataki 案例）
        if (process.exitValue() == 0) {
            boolean nearEnd = currentMusic != null && currentMusic.duration() > 0
                    && estimatePlayerPosition() >= currentMusic.duration() - NATURAL_END_TOLERANCE_MS;
            if (hasProducedFirstByte && nearEnd) {
                songActive.set(false);
                log.debug("Stream: transcoder reached natural EOF");
                return;
            }
            log.warn("Stream: transcoder exited 0 without completing the song (produced={}, pos={}ms, dur={}ms), restarting",
                    hasProducedFirstByte, estimatePlayerPosition(),
                    currentMusic != null ? currentMusic.duration() : -1);
        }

        boolean shouldRun = isEnabled.get() && currentMusic != null && !isPaused && resolveTarget() != null;
        if (shouldRun) {
            long now = System.currentTimeMillis();
            if (now - lastCrashRestartTimeMs < CRASH_RESTART_BACKOFF_MS) {
                songActive.set(false);
                log.error("Stream: transcoder died repeatedly, backing off restart");
                return;
            }
            lastCrashRestartTimeMs = now;
            log.warn("Stream: transcoder died unexpectedly, restarting");
            doRestart(); // doRestart -> stopTranscoding 会置 songActive=false，空窗由静音基底覆盖
        } else {
            songActive.set(false);
        }
    }

    /**
     * 转码器看门狗：检测"进程活着但没在产出"的情况（网络源拉流卡住 / 静默失败），
     * 超时后杀掉重启——否则当前歌曲会整首静音，VRC 只能等到切歌才有声。
     */
    @Scheduled(fixedRate = 5000)
    public void transcodeWatchdog() {
        Process process = transcoderProcess;
        if (process == null || !process.isAlive()) {
            return; // 未运行或已退出（退出由 handleTranscoderExit 处理）
        }
        long now = System.currentTimeMillis();
        if (!hasProducedFirstByte && now - transcoderStartTimeMs > TRANSCODER_STARTUP_TIMEOUT_MS) {
            log.warn("Stream: transcoder started but produced no output in {}ms, restarting", TRANSCODER_STARTUP_TIMEOUT_MS);
            restartForWatchdog();
        } else if (hasProducedFirstByte && now - lastTranscoderOutputMs > TRANSCODER_STALL_TIMEOUT_MS) {
            log.warn("Stream: transcoder stalled (no output for {}ms), restarting", TRANSCODER_STALL_TIMEOUT_MS);
            restartForWatchdog();
        }
    }

    /** 看门狗触发的重启，带 30s 崩溃冷却防反复。 */
    private synchronized void restartForWatchdog() {
        long now = System.currentTimeMillis();
        if (now - lastCrashRestartTimeMs < CRASH_RESTART_BACKOFF_MS) {
            log.error("Stream: transcoder keeps failing, backing off watchdog restart");
            return;
        }
        lastCrashRestartTimeMs = now;
        doRestart();
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
        if ("netease".equals(currentMusic.platform())) {
            // 网易云 CDN 对非浏览器请求（ffmpeg 默认 UA、无 Referer）有限流/丢弃行为，
            // 实测会导致 ffmpeg TLS 连接被切断（IO error: End of file）→ 转码器无输出 → 断断续续。
            // 带上浏览器 UA + Referer 后请求特征接近真实浏览器，显著降低被切断概率。
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Referer", "https://music.163.com/");
        } else if ("bilibili".equals(currentMusic.platform())) {
            headers.put("Referer", "https://www.bilibili.com/");
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }
        // key 必须含 music id：两首歌可能共用同一 URL
        return new TranscodeTarget(url, headers, "net:" + currentMusic.platform() + ":" + url + "#" + currentMusic.id());
    }

    private record TranscodeTarget(String input, Map<String, String> headers, String key) {
    }
}
