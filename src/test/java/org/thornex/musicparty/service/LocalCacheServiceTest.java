package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.event.DownloadStatusEvent;
import org.thornex.musicparty.source.ResourceKey;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LocalCacheServiceTest {
    @TempDir Path directory;

    private LocalCacheService cache(AppProperties properties, byte[] bytes, CopyOnWriteArrayList<String> events) {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "audio/mpeg")
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))).build())).build();
        ApplicationEventPublisher publisher = event -> events.add(((DownloadStatusEvent) event).getMusicId());
        LocalCacheService cache = new LocalCacheService(client, publisher, properties, directory); cache.init(); return cache;
    }

    private byte[] mp3(int length) {
        byte[] data = new byte[length]; data[0] = 'I'; data[1] = 'D'; data[2] = '3'; return data;
    }

    private void await(LocalCacheService cache, String key, CacheStatus expected) {
        Flux.interval(Duration.ofMillis(10)).filter(tick -> cache.getStatus(key) == expected).next().block(Duration.ofSeconds(5));
    }

    @Test void streamingCommitHashesOpaqueIdAndRestoresNamespacedIndex() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        String resource = ResourceKey.encode("stub", "../../a:特殊/1");
        String key = resource + ":credentials:0";
        LocalCacheService cache = cache(new AppProperties(), mp3(1024), events);
        try {
            cache.submitDownload(key, Mono.just(new LocalCacheService.DownloadSource("https://93.184.216.34/audio", ".mp3", Map.of(), "audio/mpeg", "test-audio")), Map.of(), () -> {});
            await(cache, key, CacheStatus.COMPLETED);
            Path file = cache.localPath(key);
            assertEquals(directory.toAbsolutePath(), file.getParent());
            assertTrue(file.getFileName().toString().matches("[0-9a-f]{64}\\.mp3"));
            assertEquals(1024, Files.size(file));
            assertNull(cache.localPath(ResourceKey.encode("other", "../../a:特殊/1") + ":credentials:0"));
            assertTrue(events.stream().allMatch(resource::equals));
            assertFalse(Files.exists(Path.of(file + ".part")));
        } finally { cache.cleanup(); }
        LocalCacheService restored = cache(new AppProperties(), mp3(1024), new CopyOnWriteArrayList<>());
        try { assertEquals(CacheStatus.COMPLETED, restored.getStatus(key)); }
        finally { restored.cleanup(); }
    }

    @Test void invalidFormatAndQuotaNeverBecomeReadyOrLeavePartial() throws Exception {
        AppProperties props = new AppProperties(); props.getCache().setMaxSize(DataSize.ofBytes(32));
        LocalCacheService cache = cache(props, mp3(64), new CopyOnWriteArrayList<>());
        String key = ResourceKey.encode("stub", "large");
        try {
            cache.submitDownload(key, Mono.just(new LocalCacheService.DownloadSource("https://93.184.216.34/audio", ".mp3", Map.of(), "audio/mpeg", "test-audio")), Map.of(), () -> {});
            await(cache, key, CacheStatus.FAILED);
            assertNull(cache.localPath(key));
            Flux.interval(Duration.ofMillis(10)).filter(tick -> {
                try (var files = Files.list(directory)) { return files.noneMatch(path -> path.toString().endsWith(".part")); }
                catch (Exception error) { throw new RuntimeException(error); }
            }).next().block(Duration.ofSeconds(3));
        } finally { cache.cleanup(); }
    }

    @Test void malformedContentIsRejected() {
        LocalCacheService cache = cache(new AppProperties(), "<html>not audio</html>".getBytes(), new CopyOnWriteArrayList<>());
        try {
            cache.submitDownload("bad", Mono.just(new LocalCacheService.DownloadSource("https://93.184.216.34/audio", ".mp3", Map.of(), "audio/mpeg", "test-audio")), Map.of(), () -> {});
            await(cache, "bad", CacheStatus.FAILED);
            assertNull(cache.localPath("bad"));
        } finally { cache.cleanup(); }
    }

    @Test void disabledOrChangedCredentialBeforeCommitDiscardsAsset() {
        LocalCacheService cache = cache(new AppProperties(), mp3(64), new CopyOnWriteArrayList<>());
        try {
            cache.submitDownload("changed", Mono.just(new LocalCacheService.DownloadSource("https://93.184.216.34/audio", ".mp3", Map.of(), "audio/mpeg", "test-audio")), Map.of(),
                    () -> { throw new IllegalStateException("Authorization changed"); });
            await(cache, "changed", CacheStatus.FAILED); assertNull(cache.localPath("changed"));
        } finally { cache.cleanup(); }
    }

    @Test void voiceLaneBypassesBlockedSongAndPrefetchBacklog() throws Exception {
        LocalCacheService cache = cache(new AppProperties(), mp3(64), new CopyOnWriteArrayList<>());
        java.util.concurrent.CountDownLatch songStarted = new java.util.concurrent.CountDownLatch(1);
        try {
            cache.submitDownload("blocked-song", Mono.defer(() -> {
                songStarted.countDown(); return Mono.never();
            }), Map.of(), () -> {});
            assertTrue(songStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) cache.submitDownload("backlog-" + i, Mono.never(), Map.of(), () -> {});
            long start = System.nanoTime();
            cache.submitVoiceDownload("priority-voice", Mono.just(new LocalCacheService.DownloadSource("https://93.184.216.34/voice", ".mp3", Map.of(), "audio/mpeg", "test-audio")), Map.of(), () -> {});
            await(cache, "priority-voice", CacheStatus.COMPLETED);
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
            assertEquals(CacheStatus.DOWNLOADING, cache.getStatus("blocked-song"));
            assertEquals(CacheStatus.PENDING, cache.getStatus("backlog-31"));
        } finally { cache.cleanup(); }
    }

    @Test void incompleteIndexCannotReceiveImplicitMimeOrRepresentationDefaults() throws Exception {
        String key = "stub:encoded:credentials:0";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((key + ":representation:default").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String file = hash + ".mp3";
        Files.write(directory.resolve(file), mp3(64));
        java.util.Properties oldIndex = new java.util.Properties(); oldIndex.setProperty("key", key); oldIndex.setProperty("file", file);
        try (var output = Files.newOutputStream(directory.resolve(hash + ".index"))) { oldIndex.store(output, null); }
        LocalCacheService cache = cache(new AppProperties(), mp3(64), new CopyOnWriteArrayList<>());
        try { assertNull(cache.getStatus(key)); assertNull(cache.localPath(key)); }
        finally { cache.cleanup(); }
    }

    @Test void bareFilesNeverGuessSource() throws Exception {
        Files.write(directory.resolve("123.mp3"), mp3(64));
        LocalCacheService cache = cache(new AppProperties(), mp3(64), new CopyOnWriteArrayList<>());
        try { assertNull(cache.getStatus("123")); assertNull(cache.getStatus(ResourceKey.encode("stub", "123"))); }
        finally { cache.cleanup(); }
    }
}
