package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B 站 view 详情缓存：入队与预取共享同一次上游请求，失败不缓存，换凭据后重新请求。
 */
class BilibiliVideoInfoCacheTest {
    private static final String VIEW =
            "{\"code\":0,\"data\":{\"cid\":10,\"title\":\"Track\",\"duration\":1,\"owner\":{\"name\":\"Artist\"}}}";
    private static final String COOKIE = "SESSDATA=account; buvid3=device; buvid4=device4; bili_ticket=ticket";

    private static ClientResponse json(String value) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(value).build();
    }

    private static AppProperties properties() {
        var properties = new AppProperties();
        properties.getBilibili().setBaseUrl("https://bili.test");
        properties.getBilibili().setCookie(COOKIE);
        return properties;
    }

    private static BilibiliMusicApiService service(WebClient client, AppProperties properties) {
        var cookies = new BilibiliCookieService(client, properties);
        var wbi = new BilibiliWbiService(client, cookies);
        return new BilibiliMusicApiService(client, properties, wbi, cookies);
    }

    @Test
    void concurrentRequestsForTheSameVideoShareOneInFlightView() throws Exception {
        var properties = properties();
        var views = new AtomicInteger();
        Sinks.One<ClientResponse> pending = Sinks.one();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().endsWith("/view")) {
                views.incrementAndGet();
                return pending.asMono();
            }
            return Mono.just(json("{\"code\":0,\"data\":{}}"));
        }).build();
        var api = service(client, properties);
        var first = api.getMusic("BV1abc").toFuture();
        var second = api.getMusic("BV1abc").toFuture();
        assertEquals(1, views.get(), "两个并发请求应共享同一次在途 view");
        pending.tryEmitValue(json(VIEW));
        assertEquals("Track", first.get(5, TimeUnit.SECONDS).name());
        assertEquals("Track", second.get(5, TimeUnit.SECONDS).name());
        assertEquals(1, views.get());
    }

    @Test
    void completedViewIsReusedWithinTheCacheWindow() {
        var properties = properties();
        var views = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().endsWith("/view")) {
                views.incrementAndGet();
                return Mono.just(json(VIEW));
            }
            return Mono.just(json("{\"code\":0,\"data\":{}}"));
        }).build();
        var api = service(client, properties);
        assertNotNull(api.getMusic("BV1abc").block());
        assertNotNull(api.getMusic("BV1abc").block());
        assertEquals(1, views.get());
    }

    @Test
    void failedViewIsNotCachedSoTheNextCallRetries() {
        var properties = properties();
        var views = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().endsWith("/view")) {
                return Mono.just(views.incrementAndGet() == 1
                        ? json("{\"code\":-404,\"message\":\"not found\"}")
                        : json(VIEW));
            }
            return Mono.just(json("{\"code\":0,\"data\":{}}"));
        }).build();
        var api = service(client, properties);
        assertThrows(ApiRequestException.class, () -> api.getMusic("BV1abc").block());
        assertEquals("Track", api.getMusic("BV1abc").block().name());
        assertEquals(2, views.get());
    }

    @Test
    void cookieUpdateForcesANewViewRequest() {
        var properties = properties();
        var views = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().endsWith("/view")) {
                views.incrementAndGet();
                return Mono.just(json(VIEW));
            }
            return Mono.just(json("{\"code\":0,\"data\":{}}"));
        }).build();
        var api = service(client, properties);
        api.getMusic("BV1abc").block();
        api.updateCookie("SESSDATA=other; buvid3=device; buvid4=device4; bili_ticket=ticket");
        api.getMusic("BV1abc").block();
        assertEquals(2, views.get());
    }
}
