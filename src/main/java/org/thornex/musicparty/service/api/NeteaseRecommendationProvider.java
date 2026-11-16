package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaDeliveryService;
import org.thornex.musicparty.service.RecommendationProvider;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NeteaseRecommendationProvider implements RecommendationProvider {
    private final NeteaseMusicApiService api;
    private final MediaDeliveryService mediaDelivery;

    @Override
    public String sourceId() {
        return "netease";
    }

    @Override
    public java.util.Set<String> supportedModes() {
        return java.util.Set.of("FM", "DJ");
    }

    @Override
    public long credentialRevision() {
        return api.credentialRevision();
    }

    @Override
    public Mono<List<PrivateDjSegment>> nextSegments(String mode) {
        return Mono.defer(() -> switch (mode == null ? "" : mode) {
            case "FM" -> api.fetchPersonalFm().map(NeteaseRecommendationProvider::parseFmBatch);
            case "DJ" -> api.fetchAidjRcmd(null, null).map(NeteaseRecommendationProvider::parseDjBatch);
            default -> Mono.error(new ApiRequestException("CAPABILITY_UNAVAILABLE: 不支持的推荐模式"));
        });
    }

    @Override
    public Mono<PlayableMusic> getPlayable(PrivateDjSegment segment) {
        return Mono.defer(() -> {
            if (segment instanceof PrivateDjSegment.Song song) {
                return mediaDelivery.getPlayableMusic(sourceId(), song.songId());
            }
            if (segment instanceof PrivateDjSegment.Voice voice) {
                return mediaDelivery.prepareVoice(sourceId(), voice);
            }
            return Mono.error(new ApiRequestException("RECOMMENDATION_UNAVAILABLE: 推荐内容无效"));
        });
    }

    static List<PrivateDjSegment> parseFmBatch(JsonNode root) {
        NeteaseMusicApiService.validateRecommendationResponse(root);
        JsonNode data = root.path("data");
        if (!data.isArray()) throw invalidResponse();
        List<PrivateDjSegment> segments = new ArrayList<>();
        for (JsonNode song : data) segments.add(songSegment(song));
        return requireContent(segments);
    }

    static List<PrivateDjSegment> parseDjBatch(JsonNode root) {
        NeteaseMusicApiService.validateRecommendationResponse(root);
        JsonNode resources = root.path("data").path("aiDjResources");
        if (!resources.isArray()) throw invalidResponse();
        Map<String, String> songCovers = new HashMap<>();
        for (JsonNode resource : resources) {
            if ("song".equals(resource.path("type").asText())) {
                PrivateDjSegment.Song song = songSegment(resource.path("value").path("songData"));
                songCovers.put(song.songId(), song.coverUrl());
            }
        }
        List<PrivateDjSegment> segments = new ArrayList<>();
        for (JsonNode resource : resources) {
            switch (resource.path("type").asText()) {
                case "song" -> segments.add(songSegment(resource.path("value").path("songData")));
                case "audio" -> {
                    JsonNode audioList = resource.path("value").path("audioList");
                    if (!audioList.isArray() || audioList.isEmpty()) throw invalidResponse();
                    for (JsonNode audio : audioList) {
                        String url = toHttps(audio.path("audioUrl").asText(""));
                        if (url.isBlank()) throw invalidResponse();
                        String relatedId = null;
                        JsonNode related = audio.path("introductionRelatedSongIds");
                        if (related.isArray() && !related.isEmpty()) relatedId = songId(related.path(0));
                        double seconds = audio.path("duration").asDouble(0);
                        long durationMs = Math.round(seconds * 1000);
                        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 600 || durationMs <= 0) {
                            throw invalidResponse();
                        }
                        segments.add(new PrivateDjSegment.Voice(url, opaqueId(audio.path("audioId")),
                                durationMs, relatedId,
                                relatedId == null ? null : songCovers.get(relatedId)));
                    }
                }
                default -> { }
            }
        }
        return requireContent(segments);
    }

    private static PrivateDjSegment.Song songSegment(JsonNode song) {
        List<String> artists = new ArrayList<>();
        JsonNode artistNodes = song.has("artists") ? song.path("artists") : song.path("ar");
        for (JsonNode artist : artistNodes) {
            String name = artist.path("name").asText("");
            if (!name.isBlank()) artists.add(name);
        }
        JsonNode album = song.has("album") ? song.path("album") : song.path("al");
        long duration = song.path("duration").asLong(song.path("dt").asLong(0));
        if (duration < 0) throw invalidResponse();
        return new PrivateDjSegment.Song(songId(song.path("id")), song.path("name").asText("未知歌曲"),
                List.copyOf(artists), duration, toHttps(album.path("picUrl").asText("")));
    }

    private static String songId(JsonNode value) {
        String id = opaqueId(value);
        if (!id.matches("[1-9][0-9]*")) throw invalidResponse();
        return id;
    }

    private static String opaqueId(JsonNode value) {
        if (!value.isTextual() && !value.isIntegralNumber()) throw invalidResponse();
        String id = value.asText();
        if (id.isBlank() || id.length() > 512 || id.chars().anyMatch(Character::isISOControl)) throw invalidResponse();
        return id;
    }

    private static List<PrivateDjSegment> requireContent(List<PrivateDjSegment> segments) {
        if (segments.isEmpty()) throw new ApiRequestException("RECOMMENDATION_UNAVAILABLE: 网易云推荐暂无内容");
        return List.copyOf(segments);
    }

    private static ApiRequestException invalidResponse() {
        return new ApiRequestException("RECOMMENDATION_UNAVAILABLE: 网易云推荐响应无效");
    }

    private static String toHttps(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }
}
