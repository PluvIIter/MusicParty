package org.thornex.musicparty.util;

import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public class BilibiliApiUtils {

    // 内部记录类，用于一次性返回 CID 和 Music 详情
    public record BilibiliVideoInfo(String cid, Music music) {}

    public static long durationToMillis(String durationStr) {
        if (durationStr == null || durationStr.isEmpty()) return 0;
        String[] parts = durationStr.split(":");
        long millis = 0;
        try {
            if (parts.length == 2) millis = (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            else if (parts.length == 3) millis = (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000;
        } catch (NumberFormatException e) { return 0; }
        return millis;
    }

    /**
     * @param cookie 完整 Cookie 头（含 SESSDATA、buvid3、bili_ticket 等）
     */
    private static WebClient.RequestHeadersSpec<?> buildRequest(String uri, String cookie, WebClient webClient) {
        return webClient.get().uri(uri)
                .header("Cookie", cookie)
                .header("Referer", "https://www.bilibili.com/");
    }

    public static Mono<String> getVideoCid(String bvid, WebClient webClient, String baseUrl, String cookie) {
        return getVideoInfo(bvid, webClient, baseUrl, cookie).map(BilibiliVideoInfo::cid);
    }

    public static Mono<Music> getVideoDetails(String bvid, WebClient webClient, String baseUrl, String cookie) {
        return getVideoInfo(bvid, webClient, baseUrl, cookie).map(BilibiliVideoInfo::music);
    }

    /**
     * 🟢 核心方法：一次请求获取 CID 和 视频详情
     */
    public static Mono<BilibiliVideoInfo> getVideoInfo(String bvid, WebClient webClient, String baseUrl, String cookie) {
        return buildRequest(baseUrl + "/x/web-interface/view?bvid=" + bvid, cookie, webClient)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .handle((jsonNode, sink) -> {
                    if (jsonNode.path("code").asInt() != 0) {
                        sink.error(new ApiRequestException("Could not get Bilibili video info: " + jsonNode.path("message").asText()));
                        return;
                    }
                    JsonNode data = jsonNode.path("data");
                    String cid = data.path("cid").asText();

                    // 获取时长（API 返回的是秒）
                    long durationMs = data.path("duration").asLong() * 1000;

                    Music music = new Music(
                            bvid,
                            data.path("title").asText(),
                            List.of(data.path("owner").path("name").asText()),
                            durationMs,
                            "bilibili",
                            data.path("pic").asText()
                    );

                    sink.next(new BilibiliVideoInfo(cid, music));
                });
    }
}