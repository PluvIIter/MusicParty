package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.LocalResourceConfig;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.DownloadStatusEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class LocalCacheService {

    private final WebClient webClient;
    // 限制 200MB
    private static final long MAX_CACHE_SIZE = 1024 * 1024 * 1024;
    private static final long DOWNLOAD_COOLDOWN_SECONDS = 3;

    // 内存中维护缓存文件的元数据
    private final Map<String, CacheEntry> cacheIndex = new ConcurrentHashMap<>();
    private final AtomicLong currentTotalSize = new AtomicLong(0);
    private final ApplicationEventPublisher eventPublisher;
    private final Sinks.Many<DownloadTask> downloadQueue = Sinks.many().unicast().onBackpressureBuffer();
    private Disposable queueSubscription;

    private record DownloadTask(
            String musicId,
            Mono<String> urlProvider,
            Map<String, String> headers,
            String extension
    ) {}

    public LocalCacheService(WebClient webClient, ApplicationEventPublisher eventPublisher) {
        this.webClient = webClient;
        this.eventPublisher = eventPublisher;
    }

    @Data
    public static class CacheEntry {
        private String id;
        private String fileName;
        private CacheStatus status;
        private long size;
        private long lastAccessTime;
        private String originalUrl; // 用于重试或记录
    }

    @PostConstruct
    public void init() {
        // 初始化时扫描目录，重建索引和计算大小
        File dir = new File(LocalResourceConfig.CACHE_DIR);
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String id = f.getName().split("\\.")[0]; // 假设文件名是 id.mp3
                CacheEntry entry = new CacheEntry();
                entry.setId(id);
                entry.setFileName(f.getName());
                entry.setStatus(CacheStatus.COMPLETED);
                entry.setSize(f.length());
                entry.setLastAccessTime(System.currentTimeMillis());

                cacheIndex.put(id, entry);
                currentTotalSize.addAndGet(f.length());
            }
        }
        log.info("LocalCacheService initialized. Current cache size: {} bytes", currentTotalSize.get());

        this.queueSubscription = downloadQueue.asFlux()
                .concatMap(task ->
                        processTask(task)
                                .onErrorResume(e -> {
                                    log.error("Unexpected error in download queue processing", e);
                                    return Mono.empty(); // 吞掉异常，防止队列崩溃
                                })
                                // 🟢 关键：强制冷却时间，防止风控
                                .then(Mono.delay(Duration.ofSeconds(DOWNLOAD_COOLDOWN_SECONDS)))
                )
                .subscribe();
    }

    @PreDestroy
    public void cleanup() {
        if (queueSubscription != null && !queueSubscription.isDisposed()) {
            queueSubscription.dispose();
        }
    }

    /**
     * 提交下载任务
     * @param musicId 音乐ID（作为文件名）
     * @param urlProvider 提供下载链接的 Mono（因为链接可能是动态获取的）
     * @param headers 下载需要的请求头（Referer, Cookie等）
     * @param extension 文件扩展名 (如 .m4a, .mp3)
     */
    public void submitDownload(String musicId, Mono<String> urlProvider, Map<String, String> headers, String extension) {
        if (cacheIndex.containsKey(musicId) && cacheIndex.get(musicId).getStatus() == CacheStatus.COMPLETED) {
            log.info("Music {} already cached.", musicId);
            touch(musicId); // 更新访问时间
            return;
        }

        // 2. 检查是否正在处理或排队 (关键去重)
        if (cacheIndex.containsKey(musicId)) {
            CacheStatus status = cacheIndex.get(musicId).getStatus();
            if (status == CacheStatus.DOWNLOADING || status == CacheStatus.PENDING) {
                log.debug("Task {} is already pending or downloading, skip enqueue.", musicId);
                return; // 直接返回，不要重复 emit
            }
        }

        // 初始化条目
        CacheEntry entry = new CacheEntry();
        entry.setId(musicId);
        entry.setStatus(CacheStatus.PENDING); // 🟢 状态：排队中
        entry.setLastAccessTime(System.currentTimeMillis());
        cacheIndex.put(musicId, entry);

        eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));

        Sinks.EmitResult result = downloadQueue.tryEmitNext(new DownloadTask(musicId, urlProvider, headers, extension));

        if (result.isFailure()) {
            log.error("Failed to enqueue download task for {}", musicId);
            entry.setStatus(CacheStatus.FAILED);
            eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
        } else {
            log.info("Download enqueued: {}", musicId);
        }
    }

    private Mono<Void> processTask(DownloadTask task) {
        String musicId = task.musicId();
        CacheEntry entry = cacheIndex.get(musicId);

        // 双重检查：如果任务在排队期间被移除了，就跳过
        if (entry == null) return Mono.empty();

        // 🟢 状态变更：PENDING -> DOWNLOADING
        entry.setStatus(CacheStatus.DOWNLOADING);
        eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
        log.info("Processing download: {}", musicId);

        return task.urlProvider()
                .flatMap(url -> {
                    entry.setOriginalUrl(url);
                    String fileName = musicId + task.extension();
                    entry.setFileName(fileName);
                    Path destPath = Paths.get(LocalResourceConfig.CACHE_DIR, fileName);

                    return webClient.get()
                            .uri(url)
                            .headers(httpHeaders -> task.headers().forEach(httpHeaders::add))
                            .retrieve()
                            .bodyToFlux(DataBuffer.class)
                            .collectList()
                            .publishOn(Schedulers.boundedElastic())
                            .doOnSuccess(dataBuffers -> {
                                try {
                                    try (var os = Files.newOutputStream(destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                                        for (DataBuffer buffer : dataBuffers) {
                                            byte[] bytes = new byte[buffer.readableByteCount()];
                                            buffer.read(bytes);
                                            os.write(bytes);
                                            DataBufferUtils.release(buffer);
                                        }
                                    }
                                    long size = Files.size(destPath);
                                    entry.setSize(size);
                                    entry.setStatus(CacheStatus.COMPLETED);
                                    currentTotalSize.addAndGet(size);
                                    log.info("Download completed: {}", fileName);
                                    eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
                                    ensureCapacity();
                                } catch (IOException e) {
                                    throw new RuntimeException("File write error", e);
                                }
                            });
                })
                // 错误处理
                .doOnError(error -> {
                    log.error("Download Task failed for {}: {}", musicId, error.getMessage());
                    entry.setStatus(CacheStatus.FAILED);
                    eventPublisher.publishEvent(new DownloadStatusEvent(this, musicId));
                })
                // 这里的 onErrorResume 保证即使这个任务失败，Flux 链也不会断，会继续执行 delay 和下一个任务
                .onErrorResume(e -> Mono.empty())
                .then(); // 转为 Mono<Void>
    }

    /**
     * LRU 清理策略
     */
    private synchronized void ensureCapacity() {
        if (currentTotalSize.get() <= MAX_CACHE_SIZE) return;

        log.info("Cache limit exceeded. Cleaning up...");

        // 按最后访问时间排序
        cacheIndex.values().stream()
                .filter(e -> e.getStatus() == CacheStatus.COMPLETED) // 只删已完成的
                .sorted(Comparator.comparingLong(CacheEntry::getLastAccessTime))
                .forEach(entry -> {
                    if (currentTotalSize.get() <= MAX_CACHE_SIZE) return; // 容量够了就停

                    try {
                        Path path = Paths.get(LocalResourceConfig.CACHE_DIR, entry.getFileName());
                        Files.deleteIfExists(path);
                        currentTotalSize.addAndGet(-entry.getSize());
                        cacheIndex.remove(entry.getId());
                        log.info("Evicted: {}", entry.getFileName());
                    } catch (IOException e) {
                        log.error("Failed to delete {}", entry.getFileName(), e);
                    }
                });
    }

    /**
     * 获取文件访问 URL
     * 返回: /media/id.ext
     */
    public String getLocalUrl(String musicId) {
        CacheEntry entry = cacheIndex.get(musicId);
        if (entry != null && entry.getStatus() == CacheStatus.COMPLETED) {
            touch(musicId);
            return "/media/" + entry.getFileName();
        }
        return null;
    }

    public CacheStatus getStatus(String musicId) {
        if (!cacheIndex.containsKey(musicId)) return null;
        return cacheIndex.get(musicId).getStatus();
    }

    private void touch(String musicId) {
        CacheEntry entry = cacheIndex.get(musicId);
        if (entry != null) {
            entry.setLastAccessTime(System.currentTimeMillis());
        }
    }
}