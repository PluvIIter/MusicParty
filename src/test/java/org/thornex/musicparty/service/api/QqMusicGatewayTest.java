package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicGatewayTest {
    @Test
    void authenticatedMonoCannotDispatchRevokedSnapshotWhenSubscriptionIsDelayed() {
        for (String replacement : java.util.List.of("", "{\"musicid\":123,\"musickey\":\"new\"}")) {
            var properties = properties();
            var credentials = new QqMusicCredentialService(properties, MAPPER);
            credentials.updateCookie("{\"musicid\":123,\"musickey\":\"old\"}");
            var gateway = new QqMusicGateway(client((request, body) -> {
                fail("revoked snapshot reached private gateway");
                return Mono.empty();
            }), properties, credentials);
            var pending = gateway.authenticatedRequest("media", Map.of("id", "songMID"), credentials.snapshot());
            credentials.updateCookie(replacement);
            var failure = assertThrows(org.thornex.musicparty.exception.ApiRequestException.class, pending::block);
            assertTrue(failure.getMessage().startsWith("CREDENTIAL_CHANGED"));
        }
    }

    @Test
    void httpFixtureSerializesRequestBodyAndDecodesEnvelope() {
        var client = client((request, body) -> {
            assertEquals("search", body.path("params").path("query").asText());
            return Mono.just(data(json("[]")));
        });
        var result = client.post().uri("http://gateway.test/v1/search").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("params", Map.of("query", "search"))).retrieve().bodyToMono(com.fasterxml.jackson.databind.JsonNode.class).block();
        assertTrue(result.path("data").isArray());
    }
}
