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

import static org.junit.jupiter.api.Assertions.*;

class NeteaseSourceCookieTest {
    private ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    @Test void configuredAccountQueriesTargetWithoutPrivacyFilteringAndUpdatesDeferredRequests() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getNetease().setCookie("MUSIC_U=accountA");
        List<String> cookies = new ArrayList<>();
        WebClient client = WebClient.builder().defaultHeader("Cookie", "MUSIC_U=unrelated")
                .defaultCookie("MUSIC_U", "unrelated").defaultHeader("Authorization", "Bearer reverse-proxy")
                .exchangeFunction(request -> {
            assertTrue(request.cookies().isEmpty());
            assertEquals("Bearer reverse-proxy", request.headers().getFirst("Authorization"));
            assertTrue(request.url().getQuery().contains("uid=222"));
            assertFalse(request.url().toString().contains("MUSIC_U"));
            cookies.add(request.headers().getFirst("Cookie"));
            return Mono.just(json("{\"code\":200,\"more\":false,\"playlist\":[{\"id\":333,\"name\":\"Accessible private\",\"privacy\":10,\"trackCount\":1}]}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties);
        var pending = api.publicCollections("222", "playlists", 0, 10);
        api.updateCookie("MUSIC_U=replacement");
        assertEquals("333", pending.block().items().getFirst().id());
        api.updateCookie("");
        assertEquals("333", pending.block().items().getFirst().id());
        assertEquals(2, cookies.size());
        assertEquals("MUSIC_U=replacement", cookies.getFirst());
        assertNull(cookies.get(1));
    }

    private ClientResponse statusJson() {
        return json("{\"code\":200,\"data\":{\"code\":200,\"account\":{\"id\":123,\"nickname\":\"listener\"}}}");
    }

    @Test void refreshCookieRequiresSuccessfulBodyAndReplacesCookie() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getNetease().setCookie("MUSIC_U=old");
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if ("/login/status".equals(request.url().getPath())) {
                assertEquals("MUSIC_U=new; __csrf=csrf", request.headers().getFirst("Cookie"));
                return Mono.just(statusJson());
            }
            assertEquals("/login/refresh", request.url().getPath());
            assertTrue(request.url().getQuery().startsWith("timestamp="));
            assertEquals("MUSIC_U=old", request.headers().getFirst("Cookie"));
            return Mono.just(json("{\"code\":200,\"cookie\":\"MUSIC_U=new; __csrf=csrf\"}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties);
        api.refreshCookie();
        assertEquals("MUSIC_U=new; __csrf=csrf", requestCookie(api, properties));
    }

    @Test void refreshCookieStripsSetCookieAttributes() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getNetease().setCookie("MUSIC_U=old");
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if ("/login/status".equals(request.url().getPath())) return Mono.just(statusJson());
            return Mono.just(json("{\"code\":200,\"cookie\":\"MUSIC_U=new; Max-Age=1296000; Expires=Wed, 09 Dec 2026 08:00:00 GMT; Path=/; __csrf=csrf\"}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties);
        api.refreshCookie();
        assertEquals("MUSIC_U=new; __csrf=csrf", requestCookie(api, properties));
    }

    @Test void refreshCookieKeepsOldCookieWhenTheReturnedCookieIsNotLoggedIn() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getNetease().setCookie("MUSIC_U=old");
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if ("/login/status".equals(request.url().getPath())) {
                return Mono.just(json("{\"code\":200,\"data\":{\"code\":200}}"));
            }
            return Mono.just(json("{\"code\":200,\"cookie\":\"MUSIC_U=anonymous\"}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties);
        assertThrows(ApiRequestException.class, api::refreshCookie);
        assertEquals("MUSIC_U=old", requestCookie(api, properties));
    }

    private String requestCookie(NeteaseMusicApiService api, AppProperties properties) {
        var captured = new String[1];
        var client = WebClient.builder().exchangeFunction(request -> {
            captured[0] = request.headers().getFirst("Cookie");
            return Mono.just(json("{\"code\":200,\"playlist\":[]}"));
        }).build();
        new NeteaseMusicApiService(client, properties).publicCollections("1", "playlists", 0, 1).block();
        return captured[0];
    }

    @Test void refreshCookieKeepsOldCookieWhenUpstreamRejectsIt() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getNetease().setCookie("MUSIC_U=old");
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json("{\"code\":301}"))).build();
        var api = new NeteaseMusicApiService(client, properties);
        assertThrows(ApiRequestException.class, api::refreshCookie);
        assertEquals("MUSIC_U=old", requestCookie(api, properties));
    }

    @Test void permissionFailureKeepsTargetAndNeverFallsBackToAnonymous() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getNetease().setCookie("MUSIC_U=accountA");
        List<String> targets = new ArrayList<>();
        WebClient client = WebClient.builder().defaultHeader("Cookie", "MUSIC_U=unrelated")
                .defaultCookie("MUSIC_U", "unrelated").defaultHeader("Authorization", "Bearer reverse-proxy")
                .exchangeFunction(request -> {
            assertTrue(request.cookies().isEmpty());
            assertEquals("Bearer reverse-proxy", request.headers().getFirst("Authorization"));
            assertEquals("MUSIC_U=accountA", request.headers().getFirst("Cookie"));
            targets.add(request.url().getQuery());
            return Mono.just(json("{\"code\":403,\"message\":\"permission denied\"}"));
        }).build();
        var api = new NeteaseMusicApiService(client, properties);
        assertThrows(ApiRequestException.class, () -> api.publicCollections("222", "playlists", 0, 10).block());
        assertEquals(List.of("uid=222&offset=0&limit=10"), targets);
    }
}
