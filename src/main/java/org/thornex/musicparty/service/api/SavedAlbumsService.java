package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.SavedAlbumPage;
import org.thornex.musicparty.dto.SavedAlbumPage.SavedAlbum;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;

/** The collection belongs to the configured room account, not an arbitrary bound UID. */
@Service
public class SavedAlbumsService {
    private final WebClient client;
    private final AppProperties properties;
    public SavedAlbumsService(WebClient client, AppProperties properties) {
        this.client = client; this.properties = properties;
    }
    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
    public Mono<SavedAlbumPage> getSaved(int offset, int limit) {
        return Mono.defer(() -> {
            if (!properties.getNetease().isEnabled()) throw error(HttpStatus.CONFLICT, "网易云音乐源已被禁用");
            if (offset < 0 || offset > 100000 || limit < 1 || limit > 50) throw error(HttpStatus.BAD_REQUEST, "无效的分页参数");
            String cookie = properties.getNetease().getCookie();
            if (cookie == null || cookie.isBlank() || "YOUR_NETEASE_COOKIE_STRING_HERE".equals(cookie)) {
                throw error(HttpStatus.UNAUTHORIZED, "请联系管理员配置个人网易云账号 Cookie，游客账号没有收藏专辑");
            }
            return client.get().uri(properties.getNetease().getBaseUrl()
                    + "/album/sublist?limit={limit}&offset={offset}&cookie={cookie}&timestamp={timestamp}",
                    limit, offset, cookie, System.currentTimeMillis()).exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (status == 301 || status == 401) return response.releaseBody().then(Mono.error(loginExpired()));
                if (response.statusCode().isError()) return response.releaseBody().then(Mono.error(error(HttpStatus.BAD_GATEWAY, "收藏专辑读取失败，请稍后重试")));
                return response.bodyToMono(JsonNode.class).map(json -> page(json, offset, limit));
            });
        }).switchIfEmpty(Mono.error(error(HttpStatus.BAD_GATEWAY, "收藏专辑读取失败，请稍后重试")))
          .timeout(Duration.ofSeconds(10))
          .onErrorMap(e -> e instanceof ResponseStatusException ? e : error(HttpStatus.BAD_GATEWAY, "收藏专辑读取失败，请检查音乐接口连接后重试"));
    }
    private ResponseStatusException loginExpired() {
        return error(HttpStatus.UNAUTHORIZED, "网易云登录已失效，请联系管理员更新个人账号 Cookie");
    }
    private SavedAlbumPage page(JsonNode json, int offset, int limit) {
        int code = json.path("code").asInt(200);
        if (code == 301 || code == 401) throw loginExpired();
        JsonNode data = json.path("data");
        if (code != 200 || !data.isArray()) throw error(HttpStatus.BAD_GATEWAY, "收藏专辑读取失败，请稍后重试");
        Map<String, SavedAlbum> items = new LinkedHashMap<>();
        for (JsonNode album : data) {
            String id = album.path("id").asText("");
            if (!id.matches("[1-9][0-9]{0,19}")) continue;
            List<String> artists = new ArrayList<>();
            for (JsonNode artist : album.path("artists")) {
                String name = artist.path("name").asText("");
                if (!name.isBlank()) artists.add(name);
            }
            String cover = album.path("picUrl").asText("");
            if (cover.startsWith("http://")) cover = "https://" + cover.substring(7);
            items.putIfAbsent(id, new SavedAlbum(id, album.path("name").asText("未命名专辑"), List.copyOf(artists), cover, Math.max(0, album.path("size").asInt(0))));
        }
        int total = Math.max(0, json.path("count").asInt(offset + data.size()));
        boolean more = data.size() > 0 && (json.has("hasMore") ? json.path("hasMore").asBoolean() : offset + data.size() < total || data.size() >= limit);
        return new SavedAlbumPage(List.copyOf(items.values()), total, more, offset + data.size());
    }
}
