package org.thornex.musicparty.service.api;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.SavedAlbumPage;
import reactor.core.publisher.Mono;

@Service
public class SavedAlbumsService {
    public SavedAlbumsService(WebClient client, AppProperties properties) {
    }

    public Mono<SavedAlbumPage> getSaved(int offset, int limit) {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
                "不提供配置 Cookie 账号的收藏；仅支持绑定用户的公开收藏"));
    }
}
