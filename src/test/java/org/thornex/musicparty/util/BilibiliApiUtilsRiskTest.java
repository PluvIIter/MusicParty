package org.thornex.musicparty.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliApiUtilsRiskTest {
    private static WebClient client(int status, String body, String contentType, AtomicInteger calls) {
        return WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                    .header("Content-Type", contentType).body(body).build());
        }).build();
    }

    @Test
    void viewRiskControlRetriesOnceThenReturnsFriendlyError() {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(ApiRequestException.class, () -> BilibiliApiUtils
                .getVideoInfo("BV1", client(412, "<html>challenge</html>", "text/html", calls), "http://bili.test", "")
                .block());
        assertTrue(error.getMessage().contains("风控"), error.getMessage());
        assertEquals(2, calls.get(), "412 应该重试一次");
    }

    @Test
    void viewSuccessIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        String json = "{\"code\":0,\"data\":{\"cid\":\"123\",\"duration\":10,\"title\":\"t\",\"owner\":{\"name\":\"a\"},\"pic\":\"http://i0.hdslb.com/x.jpg\"}}";
        var info = BilibiliApiUtils
                .getVideoInfo("BV1", client(200, json, "application/json", calls), "http://bili.test", "")
                .block();
        assertNotNull(info);
        assertEquals("123", info.cid());
        assertEquals(1, calls.get());
    }
}