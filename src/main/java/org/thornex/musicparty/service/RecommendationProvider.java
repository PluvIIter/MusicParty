package org.thornex.musicparty.service;

import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import reactor.core.publisher.Mono;

import java.util.List;

public interface RecommendationProvider {
    String sourceId();
    Mono<List<PrivateDjSegment>> nextSegments(String mode);
    Mono<PlayableMusic> getPlayable(PrivateDjSegment segment);
}
