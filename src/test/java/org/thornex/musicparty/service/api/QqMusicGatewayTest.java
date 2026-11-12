package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicGatewayTest {
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
