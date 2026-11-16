package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaConsumer;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 网易云 Cookie 失效时的明确提示（301/-462 业务码）。 */
class NeteaseSourceAuthTest {
    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    private static WebClient client(String body) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(json(body))).build();
    }

    private static NeteaseMusicApiService service(WebClient client) {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getNetease().setCookie("MUSIC_U=abc");
        return new NeteaseMusicApiService(client, properties);
    }

    @Test
    void playlistsRequireAValidCookie() {
        var api = service(client("{\"code\":301,\"msg\":\"需要登录\"}"));
        var error = assertThrows(ApiRequestException.class, () -> api.publicCollections("u1", "playlists", 0, 20).block());
        assertTrue(error.getMessage().startsWith("SOURCE_AUTH_REQUIRED"));
        assertTrue(error.getMessage().contains("Cookie"));
    }

    @Test
    void mediaRequiresAValidCookieWithoutFallingBackToTheLegacyRoute() {
        var calls = new AtomicInteger();
        var client = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(json("{\"code\":-462}"));
        }).build();
        var api = service(client);
        var error = assertThrows(ApiRequestException.class, () -> api.resolveMedia("1", MediaConsumer.SERVER).block());
        assertTrue(error.getMessage().startsWith("SOURCE_AUTH_REQUIRED"));
        assertEquals(1, calls.get());
    }

    @Test
    void missingMediaUrlMentionsMembershipOrCookie() {
        var api = service(client("{\"code\":200,\"data\":[{\"url\":null,\"code\":404}]}"));
        var error = assertThrows(ApiRequestException.class, () -> api.resolveMedia("1", MediaConsumer.SERVER).block());
        assertTrue(error.getMessage().startsWith("MEDIA_FORBIDDEN"));
        assertTrue(error.getMessage().contains("Cookie"));
    }

    @Test
    void searchAlsoReportsTheLoginRequirement() {
        var api = service(client("{\"code\":301,\"msg\":\"需要登录\"}"));
        var error = assertThrows(ApiRequestException.class, () -> api.searchMusic("x").block());
        assertTrue(error.getMessage().startsWith("SOURCE_AUTH_REQUIRED"));
    }

    @Test
    void collectionWithoutAConfiguredCookieKeepsTheCollectionMessage() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        var api = new NeteaseMusicApiService(client("{\"code\":301}"), properties);
        var error = assertThrows(ApiRequestException.class, () -> api.publicCollections("u1", "playlists", 0, 20).block());
        assertTrue(error.getMessage().startsWith("COLLECTION_NOT_PUBLIC"));
    }
}
