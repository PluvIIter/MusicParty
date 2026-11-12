package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.media.MediaConsumer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliVideoInfoCacheTest {
    private final BilibiliCookieService cookies = mock(BilibiliCookieService.class);
    private final BilibiliWbiService wbi = mock(BilibiliWbiService.class);
    private final AtomicReference<String> account = new AtomicReference<>("SESSDATA=old");
    private final List<String> viewCookies = new ArrayList<>();
    private final List<String> mediaCookies = new ArrayList<>();

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    private static ClientResponse video(String cid) {
        return json("{\"code\":0,\"data\":{\"cid\":\"" + cid
                + "\",\"title\":\"Track\",\"duration\":1,\"owner\":{\"name\":\"Artist\"}}}");
    }

    private BilibiliMusicApiService adapter(Function<String, Mono<ClientResponse>> view) {
        when(cookies.getPublicCookieHeader()).thenReturn(Mono.just("buvid3=device; buvid4=device4"));
        when(cookies.getCookieHeader()).thenAnswer(invocation -> Mono.just(account.get()));
        doAnswer(invocation -> { account.set(invocation.getArgument(0)); return null; }).when(cookies).updateCookie(anyString());
        var props = new AppProperties();
        props.getBilibili().setBaseUrl("http://bili.test");
        var client = WebClient.builder().exchangeFunction(request -> {
            String cookie = request.headers().getFirst("Cookie");
            if (request.url().getPath().endsWith("/view")) {
                viewCookies.add(cookie);
                return view.apply(cookie);
            }
            mediaCookies.add(cookie);
            return Mono.just(json("{\"code\":0,\"data\":{\"timelength\":1000,\"durl\":[{\"url\":\"https://cdn.test/video\"}]}}"));
        }).build();
        return new BilibiliMusicApiService(client, props, wbi, cookies);
    }

    @Test
    void metadataAndPrefetchSharePublicViewButMediaStillUsesAccountCookie() {
        var adapter = adapter(cookie -> Mono.just(video("10")));
        assertNotNull(adapter.getMusic("BV1abc").block());
        var media = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).block();
        assertNotNull(media);
        assertTrue(media.timelineKey().contains("cid:10"));
        assertEquals(List.of("buvid3=device; buvid4=device4"), viewCookies);
        assertEquals(List.of("SESSDATA=old"), mediaCookies);
        verify(cookies, never()).updateCookie(anyString());
    }

    @Test
    void accountMetadataNeverSatisfiesPublicMetadataRequest() {
        var adapter = adapter(cookie -> Mono.just(video(cookie.contains("SESSDATA") ? "private" : "public")));
        assertTrue(adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).block().timelineKey().contains("cid:private"));
        assertNotNull(adapter.getMusic("BV1abc").block());
        assertEquals(List.of("SESSDATA=old", "buvid3=device; buvid4=device4"), viewCookies);
        assertTrue(adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).block().timelineKey().contains("cid:public"));
        assertEquals(2, viewCookies.size());
    }

    @Test
    void simultaneousMetadataAndPrefetchShareInFlightPublicView() {
        var response = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> response.asMono());
        var first = adapter.getMusic("BV1abc").toFuture();
        var second = adapter.getMusic("BV1abc").toFuture();
        var media = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).toFuture();
        assertEquals(1, viewCookies.size());
        response.tryEmitValue(video("10"));
        assertNotNull(first.join());
        assertNotNull(second.join());
        assertTrue(media.join().timelineKey().contains("cid:10"));
        assertEquals(1, viewCookies.size());
    }

    @Test
    void failedPublicLookupFallsBackToAccountWithoutExposingAccountMetadata() {
        var publicResponse = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> cookie.contains("SESSDATA") ? Mono.just(video("account")) : publicResponse.asMono());
        assertNotNull(adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).block());
        var metadata = adapter.getMusic("BV1abc").toFuture();
        var media = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).toFuture();
        publicResponse.tryEmitValue(json("{\"code\":-403,\"message\":\"login required\"}"));
        assertThrows(java.util.concurrent.CompletionException.class, metadata::join);
        assertTrue(media.join().timelineKey().contains("cid:account"));
        assertEquals(List.of("SESSDATA=old", "buvid3=device; buvid4=device4"), viewCookies);
    }

    @Test
    void emptyPublicLookupFallsBackToFreshAccountLookup() {
        var publicResponse = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> cookie.contains("SESSDATA") ? Mono.just(video("account")) : publicResponse.asMono());
        var metadata = adapter.getMusic("BV1abc").toFuture();
        var media = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).toFuture();
        publicResponse.tryEmitValue(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").build());
        assertNull(metadata.join());
        assertTrue(media.join().timelineKey().contains("cid:account"));
        assertEquals(List.of("buvid3=device; buvid4=device4", "SESSDATA=old"), viewCookies);
    }

    @Test
    void simultaneousAccountResolvesShareInFlightView() {
        var response = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> response.asMono());
        var first = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).toFuture();
        var second = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).toFuture();
        assertEquals(1, viewCookies.size());
        response.tryEmitValue(video("10"));
        assertNotNull(first.join());
        assertNotNull(second.join());
    }

    @Test
    void oldAccountResponseCannotRepopulateCacheAfterCookieUpdate() {
        var old = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> cookie.equals("SESSDATA=old") ? old.asMono() : Mono.just(video("new")));
        var first = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).toFuture();
        adapter.updateCookie("SESSDATA=new");
        assertEquals(1, adapter.credentialRevision());
        old.tryEmitValue(video("old"));
        assertNotNull(first.join());
        assertTrue(adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).block().timelineKey().contains("cid:new"));
        assertEquals(List.of("SESSDATA=old", "SESSDATA=new"), viewCookies);
        verify(wbi).invalidateCache();
    }

    @Test
    void oldPublicResponseCannotReplaceNewPublicEntry() {
        var old = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> viewCookies.size() == 1 ? old.asMono() : Mono.just(video("new")));
        var first = adapter.getMusic("BV1abc").toFuture();
        adapter.updateCookie("SESSDATA=new");
        assertNotNull(adapter.getMusic("BV1abc").block());
        old.tryEmitValue(video("old"));
        assertNotNull(first.join());
        assertTrue(adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).block().timelineKey().contains("cid:new"));
        assertEquals(2, viewCookies.size());
    }

    @Test
    void failureAndEmptyCompletionDoNotPoisonLaterRetries() {
        var adapter = adapter(cookie -> switch (viewCookies.size()) {
            case 1 -> Mono.error(new IllegalStateException("unavailable"));
            case 2 -> Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").build());
            default -> Mono.just(video("10"));
        });
        assertThrows(IllegalStateException.class, () -> adapter.getMusic("BV1abc").block());
        assertNull(adapter.getMusic("BV1abc").block());
        assertNotNull(adapter.getMusic("BV1abc").block());
        assertEquals(3, viewCookies.size());
    }

    @Test
    void lateFailureAndEmptyAfterCookieUpdateCannotRemoveNewEntry() {
        for (boolean empty : List.of(false, true)) {
            viewCookies.clear();
            var old = Sinks.<ClientResponse>one();
            var adapter = adapter(cookie -> viewCookies.size() == 1 ? old.asMono() : Mono.just(video("new")));
            var first = adapter.getMusic("BV1abc").toFuture();
            adapter.updateCookie("SESSDATA=new");
            assertNotNull(adapter.getMusic("BV1abc").block());
            if (empty) {
                old.tryEmitValue(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").build());
                assertNull(first.join());
            } else {
                old.tryEmitError(new IllegalStateException("old failure"));
                assertThrows(java.util.concurrent.CompletionException.class, first::join);
            }
            assertNotNull(adapter.getMusic("BV1abc").block());
            assertEquals(2, viewCookies.size());
        }
    }

    @Test
    void cancelledSubscriberDoesNotDiscardSharedInFlightResult() {
        var response = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> response.asMono());
        var subscription = adapter.getMusic("BV1abc").subscribe();
        subscription.dispose();
        var second = adapter.getMusic("BV1abc").toFuture();
        response.tryEmitValue(video("10"));
        assertNotNull(second.join());
        assertEquals(1, viewCookies.size());
    }

    @Test
    void concurrentThreadsShareOneViewRequest() throws Exception {
        var response = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> response.asMono());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var ready = new java.util.concurrent.CountDownLatch(8);
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<java.util.concurrent.CompletableFuture<org.thornex.musicparty.dto.Music>>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return adapter.getMusic("BV1abc").toFuture();
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<java.util.concurrent.CompletableFuture<org.thornex.musicparty.dto.Music>>();
            for (var future : futures) results.add(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, viewCookies.size());
            response.tryEmitValue(video("10"));
            for (var result : results) assertNotNull(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void timedOutInFlightViewCanBeRetried() {
        var adapter = adapter(cookie -> viewCookies.size() == 1 ? Mono.never() : Mono.just(video("10")));
        var error = assertThrows(RuntimeException.class, () -> adapter.getMusic("BV1abc").block(Duration.ofSeconds(20)));
        assertInstanceOf(java.util.concurrent.TimeoutException.class, error.getCause());
        assertNotNull(adapter.getMusic("BV1abc").block());
        assertEquals(2, viewCookies.size());
    }

    @Test
    void cacheRemainsBoundedAndEvictedRequestsCannotRestoreOldEntries() {
        var old = Sinks.<ClientResponse>one();
        var adapter = adapter(cookie -> viewCookies.size() == 1 ? old.asMono() : Mono.just(video("new")));
        var first = adapter.getMusic("BVfirst").toFuture();
        for (int i = 0; i < 513; i++) assertNotNull(adapter.getMusic("BV" + i).block());
        Map<?, ?> cache = (Map<?, ?>) ReflectionTestUtils.getField(adapter, "videoInfoCache");
        assertTrue(cache.size() <= 512);
        old.tryEmitValue(video("old"));
        assertNotNull(first.join());
        int before = viewCookies.size();
        assertNotNull(adapter.getMusic("BVfirst").block());
        assertEquals(before + 1, viewCookies.size());
    }

    @Test
    void expiredViewIsRefetchedAndDistinctVideosDoNotShareEntries() {
        var adapter = adapter(cookie -> Mono.just(video(String.valueOf(viewCookies.size()))));
        assertNotNull(adapter.getMusic("BV1abc").block());
        Map<?, ?> cache = (Map<?, ?>) ReflectionTestUtils.getField(adapter, "videoInfoCache");
        cache.values().forEach(entry -> ReflectionTestUtils.setField(entry, "expiresAt", Instant.EPOCH));
        assertNotNull(adapter.getMusic("BV1abc").block());
        assertNotNull(adapter.getMusic("BV2abc").block());
        assertEquals(3, viewCookies.size());
    }
}
