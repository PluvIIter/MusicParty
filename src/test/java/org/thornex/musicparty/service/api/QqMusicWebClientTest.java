package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicWebClientTest {
    @Test void queryValuesRoundTripThroughFormDecodingWithoutChangingReservedCharacters() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties);
        for (String keyword : java.util.List.of("C++", "中文 + test", "a&b=c%20?/#{}")) {
            var web = new QqMusicWebClient(client((request, ignored) -> {
                String raw = request.url().getRawQuery();
                assertEquals(keyword, java.net.URLDecoder.decode(raw.substring("keyword=".length()), java.nio.charset.StandardCharsets.UTF_8));
                assertFalse(raw.contains("+"));
                if (keyword.contains("+")) assertTrue(raw.contains("%2B"));
                return Mono.just(data(json("{\"song\":[]}")));
            }), properties, credentials);
            assertTrue(web.get("/search/search_by_type", Map.of("keyword", keyword)).block().path("song").isArray());
        }
    }

    @Test void configuredCookieIsForwardedAndClearedWhenEmpty() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties);
        var seen = new java.util.ArrayList<String>();
        var web = new QqMusicWebClient(client((request, ignored) -> {
            seen.add(request.headers().getFirst("Cookie"));
            assertEquals("/user/123/created_songlists", request.url().getPath());
            return Mono.just(data(json("{\"playlists\":[]}")));
        }), properties, credentials);
        credentials.updateCookie("musicid=999; musickey=accountA");
        web.get("/user/123/created_songlists", Map.of()).block();
        credentials.updateCookie("musicid=888; musickey=accountC");
        web.get("/user/123/created_songlists", Map.of()).block();
        credentials.updateCookie("");
        web.get("/user/123/created_songlists", Map.of()).block();
        assertEquals("musicid=999; musickey=accountA", seen.get(0));
        assertEquals("musicid=888; musickey=accountC", seen.get(1));
        assertNull(seen.get(2));
    }

    @Test void unrelatedDefaultCredentialsAreNeverForwarded() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties);
        var client = client((request, ignored) -> {
            assertNull(request.headers().getFirst("Cookie"));
            assertNull(request.headers().getFirst("Authorization"));
            assertEquals("keyword=中文 + test", request.url().getQuery());
            assertTrue(request.url().getRawQuery().contains("%E4%B8%AD"));
            return Mono.just(data(json("{\"song\":[]}")));
        }).mutate().defaultHeader("Cookie", "musickey=secret").defaultHeader("Authorization", "Bearer secret")
                .defaultCookie("musicid", "123").defaultCookie("musickey", "cookie-secret").build();
        var web = new QqMusicWebClient(client, properties, credentials);
        assertTrue(web.get("/search/search_by_type", Map.of("keyword", "中文 + test")).block().path("song").isArray());
    }

    @Test void nonSuccessfulStatusIsReportedAsUpstreamFailure() {
        var properties = properties();
        var web = new QqMusicWebClient(client((request, ignored) ->
                Mono.just(response(HttpStatus.BAD_GATEWAY, "{\"detail\":\"upstream down\"}"))),
                properties, new QqMusicCredentialService(properties));
        var error = assertThrows(ApiRequestException.class,
                () -> web.get("/search/search_by_type", Map.of()).block());
        assertTrue(error.getMessage().startsWith("UPSTREAM_UNAVAILABLE"));
        assertFalse(error.getMessage().contains("upstream down"));
    }

    @Test void envelopesAreUnwrappedWithoutInspectingBusinessCodes() {
        var properties = properties();
        var web = new QqMusicWebClient(client((request, ignored) ->
                Mono.just(response(HttpStatus.OK, "{\"code\":-1,\"msg\":\"ignored\",\"data\":{\"playlists\":[]}}"))),
                properties, new QqMusicCredentialService(properties));
        assertTrue(web.get("/user/123/created_songlists", Map.of()).block().path("playlists").isArray());
    }
}
