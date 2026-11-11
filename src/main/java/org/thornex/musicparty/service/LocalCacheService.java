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
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.config.LocalResourceConfig;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.DownloadStatusEvent;
import org.thornex.musicparty.media.MediaUrlPolicy;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class LocalCacheService {
    private final WebClient webClient;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    private final Path root;
    private final Map<String, CacheEntry> cacheIndex = new ConcurrentHashMap<>();
    private final AtomicLong currentTotalSize = new AtomicLong();
    private final Sinks.Many<DownloadTask> downloadQueue = Sinks.many().unicast()
            .onBackpressureBuffer(new java.util.concurrent.ArrayBlockingQueue<>(256));
    private Disposable queueSubscription;
    private final Object queueEmitter = new Object();
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of(".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".wav");

    private record DownloadTask(String key, Mono<DownloadSource> source, Map<String, String> headers, Runnable beforeCommit) {}

    public record DownloadSource(String url, String extension, Map<String, String> headers, String mimeType, String representationKey) {
        public DownloadSource(String url, String extension) { this(url, extension, Map.of(), "application/octet-stream", "default"); }
        public DownloadSource(String url, String extension, Map<String, String> headers, String mimeType) {
            this(url, extension, headers, mimeType, "default");
        }
        public DownloadSource { headers = Map.copyOf(headers); java.util.Objects.requireNonNull(representationKey); }
    }

    @Data
    public static class CacheEntry {
        private String id;
        private String fileName;
        private String mimeType;
        private String representationKey;
        private volatile CacheStatus status;
        private long size;
        private volatile long lastAccessTime;
        private int pins;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LocalCacheService(WebClient client, ApplicationEventPublisher publisher, AppProperties properties) {
        this(client, publisher, properties, Paths.get(LocalResourceConfig.CACHE_DIR));
    }

    LocalCacheService(WebClient client, ApplicationEventPublisher publisher, AppProperties properties, Path root) {
        this.webClient = client;
        this.eventPublisher = publisher;
        this.appProperties = properties;
        this.root = root.toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            if (Files.isSymbolicLink(root)) throw new IOException("Media cache directory cannot be a symbolic link");
            Files.createDirectories(root);
            try (var files = Files.list(root)) {
                files.filter(path -> path.getFileName().toString().endsWith(".index")).forEach(this::restore);
            }
            java.util.Set<String> indexed = cacheIndex.values().stream().map(CacheEntry::getFileName).collect(java.util.stream.Collectors.toSet());
            try (var files = Files.list(root)) {
                files.filter(path -> {
                    String name = path.getFileName().toString();
                    return name.matches("[a-f0-9]{64}\\.[a-z0-9]+\\.part")
                            || (name.matches("[a-f0-9]{64}\\.(mp3|m4a|mp4|flac|ogg|wav)") && !indexed.contains(name));
                }).forEach(this::deleteControlled);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot initialize media cache", error);
        }
        queueSubscription = downloadQueue.asFlux().publishOn(Schedulers.boundedElastic(), 1).concatMap(task -> processTask(task)
                .onErrorResume(error -> Mono.empty()).then(Mono.delay(Duration.ofSeconds(3)))).subscribe();
    }

    private void restore(Path index) {
        try {
            Properties data = new Properties();
            try (InputStream input = Files.newInputStream(index)) { data.load(input); }
            String key = data.getProperty("key");
            String file = data.getProperty("file");
            String representation = data.getProperty("representation", "default");
            if (key == null || file == null || !file.matches("[a-f0-9]{64}\\.(mp3|m4a|mp4|flac|ogg|wav)")
                    || !file.startsWith(hash(key + ":representation:" + representation) + ".")) return;
            Path path = controlled(file);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0) return;
            CacheEntry entry = new CacheEntry();
            entry.setId(key); entry.setFileName(file); entry.setMimeType(data.getProperty("mime", "application/octet-stream"));
            entry.setRepresentationKey(representation);
            entry.setStatus(CacheStatus.COMPLETED); entry.setSize(Files.size(path));
            entry.setLastAccessTime(Files.getLastModifiedTime(path).toMillis());
            cacheIndex.put(key, entry); currentTotalSize.addAndGet(entry.getSize());
        } catch (Exception error) { log.warn("Ignoring invalid media cache index"); }
    }

    @PreDestroy
    public void cleanup() { if (queueSubscription != null) queueSubscription.dispose(); }

    public void submitDownload(String key, Mono<DownloadSource> source, Map<String, String> headers) {
        submitDownload(key, source, headers, () -> {});
    }

    public void submitDownload(String key, Mono<DownloadSource> source, Map<String, String> headers, Runnable beforeCommit) {
        synchronized (this) {
            CacheStatus status = getStatus(key);
            if (status == CacheStatus.COMPLETED || status == CacheStatus.PENDING || status == CacheStatus.DOWNLOADING) return;
            CacheEntry entry = new CacheEntry();
            entry.setId(key); entry.setStatus(CacheStatus.PENDING); entry.setLastAccessTime(System.currentTimeMillis());
            cacheIndex.put(key, entry);
        }
        publish(key);
        Sinks.EmitResult emitted;
        synchronized (queueEmitter) { emitted = downloadQueue.tryEmitNext(new DownloadTask(key, source, Map.copyOf(headers), beforeCommit)); }
        if (emitted.isFailure()) {
            cacheIndex.get(key).setStatus(CacheStatus.FAILED); publish(key);
        }
    }

    private Mono<Void> processTask(DownloadTask task) {
        return Mono.defer(() -> {
            CacheEntry entry = cacheIndex.get(task.key());
            entry.setStatus(CacheStatus.DOWNLOADING); publish(task.key());
            return task.source().switchIfEmpty(Mono.error(new IOException("No media source")))
                    .flatMap(source -> download(task, entry, source)).timeout(Duration.ofMinutes(5))
                    .doOnError(error -> { entry.setStatus(CacheStatus.FAILED); publish(task.key()); })
                    .then();
        });
    }

    private Mono<Void> download(DownloadTask task, CacheEntry entry, DownloadSource source) {
        return Mono.fromCallable(() -> {
            if (!EXTENSIONS.contains(source.extension())) throw new IOException("Unsupported media format");
            MediaUrlPolicy.validateUrl(source.url());
            Map<String, String> headers = new HashMap<>(task.headers()); headers.putAll(source.headers());
            MediaUrlPolicy.validateHeaders(headers);
            return headers;
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(headers -> {
            String name = hash(task.key() + ":representation:" + source.representationKey()) + source.extension();
            Path destination = controlled(name);
            Path partial = controlled(name + ".part");
            AtomicLong bytes = new AtomicLong();
            long limit = Math.min(MAX_DOWNLOAD_BYTES, appProperties.getCache().getMaxSize().toBytes());
            var buffers = webClient.get().uri(source.url()).headers(http -> headers.forEach(http::set))
                    .exchangeToFlux(response -> {
                        if (!response.statusCode().is2xxSuccessful()) return response.releaseBody()
                                .thenMany(reactor.core.publisher.Flux.error(new IOException("Media download rejected")));
                        String type = response.headers().contentType().map(Object::toString).orElse("");
                        if (type.startsWith("text/") || type.contains("json")) return response.releaseBody()
                                .thenMany(reactor.core.publisher.Flux.error(new IOException("Invalid media content")));
                        return response.bodyToFlux(DataBuffer.class);
                    }).handle((DataBuffer buffer, reactor.core.publisher.SynchronousSink<DataBuffer> sink) -> {
                        if (bytes.addAndGet(buffer.readableByteCount()) > limit) {
                            DataBufferUtils.release(buffer); sink.error(new IOException("Media exceeds cache quota"));
                        } else sink.next(buffer);
                    }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
            return DataBufferUtils.write(buffers, partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    .then(Mono.fromRunnable(() -> {
                        try {
                            validateFile(partial, source.extension());
                            task.beforeCommit().run();
                            synchronized (this) {
                                reserve(bytes.get(), task.key());
                                Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                                Properties index = new Properties(); index.setProperty("key", task.key());
                                index.setProperty("file", name); index.setProperty("mime", source.mimeType());
                                index.setProperty("representation", source.representationKey());
                                Path tempIndex = controlled(hash(task.key()) + ".index.part");
                                try (var output = Files.newOutputStream(tempIndex)) { index.store(output, null); }
                                Files.move(tempIndex, controlled(hash(task.key()) + ".index"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                                entry.setFileName(name); entry.setMimeType(source.mimeType()); entry.setRepresentationKey(source.representationKey()); entry.setSize(bytes.get());
                                entry.setLastAccessTime(System.currentTimeMillis()); entry.setStatus(CacheStatus.COMPLETED);
                                currentTotalSize.addAndGet(bytes.get());
                            }
                            publish(task.key());
                        } catch (IOException error) { throw new IllegalStateException("Cannot commit media cache", error); }
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .doFinally(signal -> deleteControlled(partial));
        }).then();
    }

    private static void validateFile(Path path, String extension) throws IOException {
        byte[] head;
        try (InputStream input = Files.newInputStream(path)) { head = input.readNBytes(16); }
        boolean valid = head.length >= 12 && switch (extension) {
            case ".m4a", ".mp4" -> new String(head, 4, 4, StandardCharsets.US_ASCII).equals("ftyp");
            case ".mp3" -> (head[0] == 'I' && head[1] == 'D' && head[2] == '3') || ((head[0] & 255) == 255 && (head[1] & 224) == 224);
            case ".flac" -> new String(head, 0, 4, StandardCharsets.US_ASCII).equals("fLaC");
            case ".ogg" -> new String(head, 0, 4, StandardCharsets.US_ASCII).equals("OggS");
            case ".wav" -> new String(head, 0, 4, StandardCharsets.US_ASCII).equals("RIFF");
            default -> false;
        };
        if (!valid) throw new IOException("Invalid media format");
    }

    private void reserve(long incoming, String except) throws IOException {
        long limit = appProperties.getCache().getMaxSize().toBytes();
        for (CacheEntry entry : cacheIndex.values().stream().filter(e -> e.getStatus() == CacheStatus.COMPLETED && e.getPins() == 0 && !e.getId().equals(except))
                .sorted(Comparator.comparingLong(CacheEntry::getLastAccessTime)).toList()) {
            if (currentTotalSize.get() + incoming <= limit) break;
            Files.deleteIfExists(controlled(entry.getFileName())); Files.deleteIfExists(controlled(hash(entry.getId()) + ".index"));
            cacheIndex.remove(entry.getId()); currentTotalSize.addAndGet(-entry.getSize());
        }
        if (currentTotalSize.get() + incoming > limit) throw new IOException("Media cache full");
    }

    public synchronized AutoCloseable pin(String key) {
        CacheEntry entry = cacheIndex.get(key);
        if (entry == null || getStatus(key) != CacheStatus.COMPLETED) throw new IllegalStateException("Media not ready");
        entry.setPins(entry.getPins() + 1);
        return () -> { synchronized (this) { entry.setPins(Math.max(0, entry.getPins() - 1)); } };
    }

    public Path localPath(String key) {
        CacheEntry entry = getCacheEntry(key);
        return entry != null && getStatus(key) == CacheStatus.COMPLETED ? controlled(entry.getFileName()) : null;
    }

    public String getLocalUrl(String key) {
        CacheEntry entry = getCacheEntry(key);
        return entry != null && getStatus(key) == CacheStatus.COMPLETED ? "/media/" + entry.getFileName() : null;
    }

    public CacheStatus getStatus(String key) {
        CacheEntry entry = cacheIndex.get(key);
        if (entry == null) return null;
        if (entry.getStatus() == CacheStatus.COMPLETED && !Files.isRegularFile(controlled(entry.getFileName()), LinkOption.NOFOLLOW_LINKS)) {
            if (cacheIndex.remove(key, entry)) currentTotalSize.addAndGet(-entry.getSize());
            return null;
        }
        return entry.getStatus();
    }

    public CacheEntry getCacheEntry(String key) {
        CacheEntry entry = cacheIndex.get(key);
        if (entry != null) entry.setLastAccessTime(System.currentTimeMillis());
        return entry;
    }

    private Path controlled(String name) {
        Path path = root.resolve(name).normalize();
        if (!path.getParent().equals(root) || Files.isSymbolicLink(path)) throw new IllegalArgumentException("Invalid cache path");
        return path;
    }

    private void deleteControlled(Path path) {
        Path target = path.toAbsolutePath().normalize();
        if (!target.getParent().equals(root)) throw new IllegalArgumentException("Invalid cache path");
        try { Files.deleteIfExists(target); } catch (IOException error) { log.warn("Cannot remove media cache file"); }
    }

    private void publish(String key) {
        int suffix = key.lastIndexOf(":credentials:");
        eventPublisher.publishEvent(new DownloadStatusEvent(this, suffix < 0 ? key : key.substring(0, suffix)));
    }

    private static String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
