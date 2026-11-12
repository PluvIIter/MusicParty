package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.RecommendationProvider;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

@Service
public class QqMusicRecommendationProvider implements RecommendationProvider {
    private final QqMusicApiService api;
    private final MediaDeliveryService delivery;

    public QqMusicRecommendationProvider(QqMusicApiService api, MediaDeliveryService delivery) {
        this.api = api;
        this.delivery = delivery;
    }

    @Override public String sourceId() { return "qqmusic"; }
    @Override public long credentialRevision() { return api.credentialRevision(); }
    @Override public Set<String> supportedModes() { return Set.of("FM"); }

    @Override
    public Mono<List<PrivateDjSegment>> nextSegments(String mode) {
        return Mono.defer(() -> {
            if (!"FM".equals(mode)) return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: QQ音乐仅支持私人FM"));
            return api.recommendations().map(songs -> songs.stream().<PrivateDjSegment>map(song ->
                    new PrivateDjSegment.Song(song.id(), song.name(), song.artists(), song.duration(), song.coverUrl())).toList());
        });
    }

    @Override
    public Mono<PlayableMusic> getPlayable(PrivateDjSegment segment) {
        return Mono.defer(() -> {
            if (!(segment instanceof PrivateDjSegment.Song song)) {
                return Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: QQ音乐不支持DJ语音"));
            }
            return delivery.getPlayableMusic(sourceId(), song.songId());
        });
    }
}
