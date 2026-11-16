package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * B 站 DASH 音频的镜像选择：优先固定档位，保留 backupUrl 备选，
 * 解析时跳过挂掉的镜像（主地址 403 时用备份）。
 */
class BilibiliAudioCandidatesTest {

    private static BilibiliMusicApiService service(WebClient client) {
        return new BilibiliMusicApiService(client, new AppProperties(),
                mock(BilibiliWbiService.class), mock(BilibiliCookieService.class));
    }

    private static WebClient client(java.util.function.Function<String, HttpStatus> status) {
        return WebClient.builder().exchangeFunction(request -> {
            HttpStatus code = status.apply(request.url().toString());
            return Mono.just(ClientResponse.create(code)
                    .header("Content-Type", "audio/mp4")
                    .body(Flux.empty()).build());
        }).build();
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void preferredQualityKeepsItsBackupMirrorsInOrder() throws Exception {
        var audio = json("""
                [{"id":30216,"baseUrl":"https://cdn/a64.m4a","backupUrl":["https://cdn/a64-alt.m4a"]},
                 {"id":30280,"base_url":"https://cdn/a192.m4a","backup_url":["https://cdn/a192-alt.m4a","https://cdn/a192.m4a"]}]
                """);
        assertEquals(List.of("https://cdn/a192.m4a", "https://cdn/a192-alt.m4a"),
                BilibiliMusicApiService.pickAudioCandidates(audio));
    }

    @Test
    void fallsBackToTheHighestQualityWhenNoPreferredIdExists() throws Exception {
        var audio = json("""
                [{"id":30100,"baseUrl":"https://cdn/top.m4a"},
                 {"id":30050,"baseUrl":"https://cdn/low.m4a"}]
                """);
        assertEquals(List.of("https://cdn/top.m4a"), BilibiliMusicApiService.pickAudioCandidates(audio));
    }

    @Test
    void missingUrlsYieldNoCandidates() throws Exception {
        assertTrue(BilibiliMusicApiService.pickAudioCandidates(json("""
                [{"id":30280}]
                """)).isEmpty());
    }

    @Test
    void firstReachableUsesABackupWhenThePrimaryIsRejected() {
        // 主地址 403、备份 200：跳过挂掉的镜像。
        var api = service(client(url -> url.contains("primary") ? HttpStatus.FORBIDDEN : HttpStatus.OK));
        assertEquals("https://cdn/backup.m4a",
                api.firstReachable(List.of("https://cdn/primary.m4a", "https://cdn/backup.m4a"), Map.of()).block());
    }

    @Test
    void firstReachableKeepsThePrimaryWhenEverythingFails() {
        // 全部探测失败时仍返回主地址，让下载阶段暴露真实错误。
        var api = service(client(url -> HttpStatus.FORBIDDEN));
        assertEquals("https://cdn/primary.m4a",
                api.firstReachable(List.of("https://cdn/primary.m4a", "https://cdn/backup.m4a"), Map.of()).block());
    }
}
