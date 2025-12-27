package org.thornex.musicparty.util;

import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public class BilibiliApiUtils {

    public static long durationToMillis(String durationStr) {
        // ... (no changes in this method)
        if (durationStr == null || durationStr.isEmpty()) return 0;
        String[] parts = durationStr.split(":");
        long millis = 0;
        try {
            if (parts.length == 2) millis = (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            else if (parts.length == 3) millis = (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000;
        } catch (NumberFormatException e) { return 0; }
        return millis;
    }

    // UPDATED: Added sessdata parameter
    private static WebClient.RequestHeadersSpec<?> buildRequest(String uri, String sessdata, WebClient webClient) {
        return webClient.get().uri(uri)
                .header("Cookie", "SESSDATA=" + sessdata)
                .header("Referer", "https://www.bilibili.com/");
    }

    // UPDATED: Added sessdata parameter
    public static Mono<String> getVideoCid(String bvid, WebClient webClient, String baseUrl, String sessdata) {
        return buildRequest(baseUrl + "/x/web-interface/view?bvid={bvid}", sessdata, webClient)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    if (jsonNode.path("code").asInt() != 0) {
                        throw new ApiRequestException("Could not get Bilibili video info: " + jsonNode.path("message").asText());
                    }
                    return jsonNode.path("data").path("cid").asText();
                });
    }

    // UPDATED: Added sessdata parameter
    public static Mono<Music> getVideoDetails(String bvid, WebClient webClient, String baseUrl, String sessdata) {
        return buildRequest(baseUrl + "/x/web-interface/view?bvid={bvid}", sessdata, webClient)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(jsonNode -> {
                    if (jsonNode.path("code").asInt() != 0) throw new ApiRequestException("Could not get Bilibili video info: " + jsonNode.path("message").asText());
                    JsonNode data = jsonNode.path("data");
                    return new Music(
                            bvid,
                            data.path("title").asText(),
                            List.of(data.path("owner").path("name").asText()),
                            data.path("duration").asLong() * 1000,
                            "bilibili",
                            data.path("pic").asText() // NEW
                    );
                });
    }
}