package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicWebClientTest {
    @Test void delayedPrivateSubscriptionRejectsRevokedSnapshot() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"old\"}");
        var web = new QqMusicWebClient(client((request, ignored) -> { fail("revoked request dispatched"); return Mono.empty(); }), properties, credentials);
        var pending = web.privateGet("/recommend/get_guess_recommend", Map.of(), credentials.snapshot(), true);
        credentials.updateCookie("");
        assertTrue(assertThrows(ApiRequestException.class, pending::block).getMessage().startsWith("CREDENTIAL_CHANGED"));
    }

    @Test void queryValuesRoundTripThroughFormDecodingWithoutChangingReservedCharacters() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        for (String keyword : java.util.List.of("C++", "中文 + test", "a&b=c%20?/#{}")) {
            var web = new QqMusicWebClient(client((request, ignored) -> {
                String raw = request.url().getRawQuery();
                assertEquals(keyword, java.net.URLDecoder.decode(raw.substring("keyword=".length()), java.nio.charset.StandardCharsets.UTF_8));
                assertFalse(raw.contains("+"));
                if (keyword.contains("+")) assertTrue(raw.contains("%2B"));
                return Mono.just(data(json("{\"song\":[]}")));
            }), properties, credentials);
            assertTrue(web.publicGet("/search/search_by_type", Map.of("keyword", keyword)).block().path("song").isArray());
        }
    }

    @Test void publicCallsClearDefaultHeadersAndNativeEnvelopeDoesNotReflectErrors() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var client = client((request, ignored) -> {
            assertNull(request.headers().getFirst("Cookie"));
            assertNull(request.headers().getFirst("Authorization"));
            assertEquals("keyword=中文 + test", request.url().getQuery());
            assertTrue(request.url().getRawQuery().contains("%E4%B8%AD"));
            return Mono.just(data(json("{\"song\":[]}")));
        }).mutate().defaultHeader("Cookie", "musickey=secret").defaultHeader("Authorization", "Bearer secret").build();
        var web = new QqMusicWebClient(client, properties, credentials);
        assertTrue(web.publicGet("/search/search_by_type", Map.of("keyword", "中文 + test")).block().path("song").isArray());
    }
}
