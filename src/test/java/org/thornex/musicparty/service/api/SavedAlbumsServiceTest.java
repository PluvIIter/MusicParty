package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class SavedAlbumsServiceTest {
    @Test
    void neverReadsConfiguredCookieAccountCollection() {
        var props = new AppProperties();
        props.getNetease().setCookie("secret-fixture-cookie");
        var client = WebClient.builder().exchangeFunction(request -> {
            fail("Cookie-account collections must never contact upstream");
            return Mono.empty();
        }).build();
        var service = new SavedAlbumsService(client, props);
        var error = assertThrows(ResponseStatusException.class, () -> service.getSaved(0, 25).block());
        assertEquals(410, error.getStatusCode().value());
        assertFalse(error.getReason().contains("secret-fixture"));
    }
}
