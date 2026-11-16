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
