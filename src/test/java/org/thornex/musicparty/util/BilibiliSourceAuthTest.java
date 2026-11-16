package org.thornex.musicparty.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

/** B 站 Cookie 失效（-101 账号未登录）时要明确提示，而不是笼统的上游失败。 */
class BilibiliSourceAuthTest {
    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    @Test
    void signedOutViewReportsAnInvalidCookie() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(json("{\"code\":-101,\"message\":\"账号未登录\"}")))
                .build();
        var error = assertThrows(ApiRequestException.class,
                () -> BilibiliApiUtils.getVideoInfo("BV1test", client, "https://bili.test", "SESSDATA=bad").block());
        assertTrue(error.getMessage().startsWith("SOURCE_AUTH_REQUIRED"));
        assertTrue(error.getMessage().contains("Cookie"));
    }

    @Test
    void otherUpstreamCodesKeepTheirExistingMessage() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(json("{\"code\":-404,\"message\":\"啥都木有\"}")))
                .build();
        var error = assertThrows(ApiRequestException.class,
                () -> BilibiliApiUtils.getVideoInfo("BV1test", client, "https://bili.test", "SESSDATA=x").block());
        assertFalse(error.getMessage().startsWith("SOURCE_AUTH_REQUIRED"));
        assertTrue(error.getMessage().contains("Bilibili video info"));
    }
}
