package org.thornex.musicparty.service.api;

import org.thornex.musicparty.dto.AlbumInfo;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.List;

public interface IMusicApiService {
    org.thornex.musicparty.source.SourceDescriptor descriptor();
    Mono<Music> getMusic(String musicId);
    Mono<org.thornex.musicparty.media.ResolvedMedia> resolveMedia(String musicId, org.thornex.musicparty.media.MediaConsumer consumer);
    default org.thornex.musicparty.source.DeliveryMode defaultDeliveryMode() {
        return org.thornex.musicparty.source.DeliveryMode.DIRECT_URL;
    }
    default long credentialRevision() { return 0; }
    default String externalUrl(String id) { return null; }

    default Mono<AlbumInfo> getAlbumForSong(String musicId) {
        return Mono.error(new ApiRequestException("此音源暂不支持专辑"));
    }
    default Mono<AlbumInfo> getAlbum(String albumId) {
        return Mono.error(new ApiRequestException("此音源暂不支持专辑"));
    }
    String getPlatformName();
    Mono<List<Music>> searchMusic(String keyword);
    Mono<PlayableMusic> getPlayableMusic(String musicId);
    Mono<List<Playlist>> getUserPlaylists(String userId);
    Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit);
    Mono<List<UserSearchResult>> searchUsers(String keyword);
    Mono<String> getLyric(String musicId);
    default Mono<org.thornex.musicparty.source.SourcePage<Playlist>> publicCollections(
            String userId, String category, int offset, int limit) {
        return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持公开收藏分页"));
    }
    default Mono<org.thornex.musicparty.source.SourcePage<Music>> publicCollectionTracks(
            String userId, String category, int offset, int limit) {
        return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持公开歌曲收藏"));
    }
    default Mono<org.thornex.musicparty.source.SourcePage<Music>> playlistPage(
            String playlistId, int offset, int limit) {
        return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持容器分页"));
    }
    default Mono<Playlist> playlistDetail(String playlistId) {
        return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持容器详情"));
    }
    default void prefetchMusic(String musicId) {};
}
