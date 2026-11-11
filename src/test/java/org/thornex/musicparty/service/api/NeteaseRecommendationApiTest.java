package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NeteaseRecommendationApiTest {
    private AppProperties properties(String cookie) {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("http://sidecar.test");
        properties.getNetease().setCookie(cookie);
        return properties;
    }

    private ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", "application/json").body(body).build();
    }

    @Test void nativeEndpointsUseConfiguredCookieAndRefreshTimestampOnlyWhenSubscribed() {
        var properties = properties("MUSIC_U=secret; csrf=abc");
        List<String> routes = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            routes.add(request.url().getPath());
            assertTrue(request.url().getQuery().contains("cookie=MUSIC_U=secret; csrf=abc"));
            assertTrue(request.url().getQuery().contains("timestamp="));
            assertNull(request.headers().getFirst("Cookie"));
            return Mono.just(response(HttpStatus.OK, "{\"code\":200,\"data\":[]}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties);
        var fm = api.fetchPersonalFm();
        var dj = api.fetchAidjRcmd(null, null);
        assertTrue(routes.isEmpty());
        fm.block();
        dj.block();
        assertEquals(List.of("/personal_fm", "/aidj/content/rcmd"), routes);
        assertTrue(api.descriptor().capabilities().recommendations());
    }

    @Test void cookieUpdatesApplyToDeferredRecommendations() {
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            assertTrue(request.url().getQuery().contains("cookie=MUSIC_U=new"));
            return Mono.just(response(HttpStatus.OK, "{\"code\":200,\"data\":[]}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties("MUSIC_U=old"));
        var request = api.fetchPersonalFm();
        api.updateCookie("MUSIC_U=new");
        request.block();
        assertEquals(1, api.credentialRevision());
    }

    @Test void missingOrPlaceholderCookieFailsWithoutAnyRequest() {
        AtomicInteger requests = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.just(response(HttpStatus.OK, "{}"));
        }).build();
        for (String cookie : new String[]{null, "", "  ", "YOUR_NETEASE_COOKIE_STRING_HERE"}) {
            var api = new NeteaseMusicApiService(client, properties(cookie));
            assertTrue(assertThrows(ApiRequestException.class, () -> api.fetchPersonalFm().block())
                    .getMessage().contains("RECOMMENDATION_AUTH_REQUIRED"));
            assertThrows(ApiRequestException.class, () -> api.fetchAidjRcmd(null, null).block());
        }
        assertEquals(0, requests.get());
    }

    @Test void businessAndTransportErrorsAreExplicitAndDoNotLeakResponseOrCookie() {
        for (String body : List.of("{\"code\":301,\"message\":\"MUSIC_U=secret\"}",
                "{\"code\":200,\"data\":{\"code\":401}}", "{\"code\":500}", "{}")) {
            WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(response(HttpStatus.OK, body))).build();
            var api = new NeteaseMusicApiService(client, properties("MUSIC_U=secret"));
            var error = assertThrows(ApiRequestException.class, () -> api.fetchPersonalFm().block());
            assertFalse(error.getMessage().contains("secret"));
            assertTrue(error.getMessage().startsWith("RECOMMENDATION_"));
        }
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.BAD_GATEWAY)) {
            WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(response(status, "MUSIC_U=secret"))).build();
            var api = new NeteaseMusicApiService(client, properties("MUSIC_U=secret"));
            var error = assertThrows(ApiRequestException.class, () -> api.fetchAidjRcmd(null, null).block());
            assertFalse(error.getMessage().contains("secret"));
            assertTrue(error.getMessage().startsWith(status == HttpStatus.BAD_GATEWAY
                    ? "RECOMMENDATION_UNAVAILABLE" : "RECOMMENDATION_AUTH_REQUIRED"));
        }
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.error(new IllegalStateException("secret"))).build();
        var api = new NeteaseMusicApiService(client, properties("MUSIC_U=secret"));
        assertFalse(assertThrows(ApiRequestException.class, () -> api.fetchPersonalFm().block()).getMessage().contains("secret"));
    }

    @Test void coordinatesAreOptionalButMustBeValidPair() {
        AtomicInteger requests = new AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            assertTrue(request.url().getQuery().contains("latitude=31.2"));
            assertTrue(request.url().getQuery().contains("longitude=121.5"));
            return Mono.just(response(HttpStatus.OK, "{\"code\":200,\"data\":{}}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties("MUSIC_U=secret"));
        api.fetchAidjRcmd(31.2, 121.5).block();
        assertThrows(ApiRequestException.class, () -> api.fetchAidjRcmd(31.2, null).block());
        assertThrows(ApiRequestException.class, () -> api.fetchAidjRcmd(Double.NaN, 121.5).block());
        assertThrows(ApiRequestException.class, () -> api.fetchAidjRcmd(31.2, 181.0).block());
        assertEquals(1, requests.get());
    }

    @Test void publicMetadataAndCollectionsNeverReceiveRecommendationCookie() {
        List<String> routes = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            routes.add(request.url().getPath());
            assertFalse(request.url().getQuery().contains("cookie="));
            assertNull(request.headers().getFirst("Cookie"));
            String body = switch (request.url().getPath()) {
                case "/user/playlist" -> "{\"code\":200,\"playlist\":[]}";
                case "/playlist/detail" -> "{\"code\":200,\"playlist\":{\"id\":1}}";
                case "/playlist/track/all" -> "{\"code\":200,\"songs\":[]}";
                case "/song/detail" -> "{\"songs\":[{\"id\":1}]}";
                case "/album" -> "{\"album\":{\"id\":1},\"songs\":[{\"id\":1}]}";
                default -> "{}";
            };
            return Mono.just(response(HttpStatus.OK, body));
        }).build();
        var api = new NeteaseMusicApiService(client, properties("MUSIC_U=secret"));
        api.searchMusic("track").block();
        api.searchUsers("listener").block();
        api.getMusic("1").block();
        api.getLyric("1").block();
        api.getAlbum("1").block();
        api.publicCollections("1", "playlists", 0, 10).block();
        api.playlistDetail("1").block();
        api.getPlaylistMusics("1", 0, 10).block();
        assertEquals(8, routes.size());
    }
}
