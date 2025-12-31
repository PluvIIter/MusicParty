package org.thornex.musicparty.service.api;

import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import reactor.core.publisher.Mono;

import java.util.List;

public interface IMusicApiService {
    String getPlatformName();
    Mono<List<Music>> searchMusic(String keyword);
    Mono<PlayableMusic> getPlayableMusic(String musicId);
    Mono<List<Playlist>> getUserPlaylists(String userId);
    Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit);
    Mono<List<UserSearchResult>> searchUsers(String keyword);
    Mono<String> getLyric(String musicId);
    default void prefetchMusic(String musicId) {};
}
