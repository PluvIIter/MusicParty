package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.*;
import org.thornex.musicparty.source.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class QqMusicApiService implements IMusicApiService, SourceCredentialManager {
    private static final String SOURCE = "qqmusic";
    private static final Set<String> QUALITIES = Set.of("MP3_128", "MP3_320", "FLAC");
    private final QqMusicGateway gateway;
    private final QqMusicCredentialService credentials;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public QqMusicApiService(QqMusicGateway gateway, QqMusicCredentialService credentials, AppProperties properties, ObjectMapper mapper) {
        this.gateway = gateway;
        this.credentials = credentials;
        this.properties = properties;
        this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public String getPlatformName() { return SOURCE; }
    @Override public boolean defaultDisabled() { return true; }
    @Override public DeliveryMode defaultDeliveryMode() { return DeliveryMode.SERVER_DOWNLOAD; }
    @Override public long credentialRevision() { return credentials.snapshot().revision(); }
    @Override public String credentialSourceId() { return SOURCE; }
    @Override public String credentialType() { return "qqmusic-credential"; }
    @Override public String credentialLabel() { return "QQ音乐登录凭据（JSON）"; }
    @Override public boolean isCredentialConfigured() { return credentials.snapshot().configured(); }
    @Override public void updateCredential(String value) { updateCookie(value); }
    public void updateCookie(String value) { credentials.updateCookie(value); }
    public boolean isCookieConfigured() { return isCredentialConfigured(); }

    @Override
    public SourceDescriptor descriptor() {
        return new SourceDescriptor(SOURCE, "QQ音乐", "music",
                new SourceCapabilities(true, true, true, true, true, true, !"FLAC".equals(properties.getQqmusic().getQuality()), true),
                Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD),
                List.of(new CollectionCategory("playlists", "公开歌单", "playlist", "CONTAINER")));
    }

    @Override
    public String externalUrl(String id) {
        return "https://y.qq.com/n/ryqq/songDetail/" + mid(id);
    }

    @Override
    public Mono<List<Music>> searchMusic(String query) {
        return Mono.defer(() -> gateway.publicRequest("search", Map.of("query", query(query))).map(this::tracks));
    }

    @Override
    public Mono<Music> getMusic(String id) {
        return Mono.defer(() -> gateway.publicRequest("track", Map.of("id", mid(id))).map(node -> track(node, id)));
    }

    @Override
    public Mono<String> getLyric(String id) {
        return Mono.defer(() -> gateway.publicRequest("lyrics", Map.of("id", mid(id))).map(node -> {
            if (!node.isTextual() || node.textValue().length() > 500_000) throw invalid();
            return node.textValue();
        }));
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String query) {
        return Mono.defer(() -> gateway.publicRequest("users", Map.of("query", query(query))).map(node -> {
            if (!node.isArray() || node.size() > 100) throw invalid();
            List<UserSearchResult> users = new ArrayList<>();
            for (JsonNode user : node) {
                requireSource(user);
                String id = text(user, "id", 512);
                userReference(id, false);
                users.add(new UserSearchResult(id, text(user, "name", 1024), optionalUrl(user, "avatarUrl"), SOURCE));
            }
            return List.copyOf(users);
        }));
    }

    @Override
    public Mono<SourcePage<Playlist>> publicCollections(String userId, String category, int offset, int limit) {
        return Mono.defer(() -> {
            if (!"playlists".equals(category)) throw unsupported();
            userReference(userId, true);
            pageBounds(offset, limit);
            return gateway.publicRequest("collections", Map.of("userId", userId, "category", category, "offset", offset, "limit", limit))
                    .map(node -> page(node, offset, limit, item -> playlist(item, null)));
        });
    }

    @Override
    public Mono<Playlist> containerDetail(String type, String id) {
        return Mono.defer(() -> {
            if (!"playlist".equals(type)) throw unsupported();
            return gateway.publicRequest("container", Map.of("type", type, "id", playlistId(id))).map(node -> playlist(node, id));
        });
    }

    @Override
    public Mono<SourcePage<Music>> containerPage(String type, String id, int offset, int limit) {
        return Mono.defer(() -> {
            if (!"playlist".equals(type)) throw unsupported();
            pageBounds(offset, limit);
            return gateway.publicRequest("tracks", Map.of("type", type, "id", playlistId(id), "offset", offset, "limit", limit))
                    .map(node -> page(node, offset, limit, item -> track(item, null)));
        });
    }

    @Override
    public Mono<AlbumInfo> getAlbum(String id) {
        return Mono.defer(() -> gateway.publicRequest("album", Map.of("id", mid(id))).map(node -> album(node, id)));
    }

    @Override
    public Mono<AlbumInfo> getAlbumForSong(String id) {
        return Mono.defer(() -> gateway.publicRequest("track-album", Map.of("id", mid(id))).map(node -> album(node, null)));
    }

    @Override
    public Mono<ResolvedMedia> resolveMedia(String id, MediaConsumer consumer) {
        return Mono.defer(() -> resolve(id, quality(), consumer, "media"));
    }

    @Override
    public Mono<ResolvedMedia> resolveFallbackMedia(String id) {
        return Mono.defer(() -> {
            if ("MP3_128".equals(quality())) return Mono.error(unsupported());
            return resolve(id, "MP3_128", MediaConsumer.SERVER, "media-fallback");
        });
    }

    private Mono<ResolvedMedia> resolve(String id, String quality, MediaConsumer consumer, String operation) {
        return Mono.defer(() -> {
            mid(id);
            if (consumer == null) throw invalid();
            var snapshot = credentials.snapshot();
            snapshot.requireValid();
            return getMusic(id).flatMap(metadata -> gateway.authenticatedRequest(operation,
                    Map.of("id", id, "quality", quality, "consumer", consumer.name()), snapshot)
                    .map(node -> {
                        credentials.requireCurrent(snapshot);
                        if (!id.equals(text(node, "id", 128))) throw invalid();
                        String extension = text(node, "extension", 12);
                        String mime = text(node, "mimeType", 64);
                        if ("FLAC".equals(quality) ? !".flac".equals(extension) || !"audio/flac".equals(mime)
                                : !".mp3".equals(extension) || !"audio/mpeg".equals(mime)) throw invalid();
                        Instant expiry;
                        try { expiry = Instant.parse(text(node, "expiresAt", 64)); }
                        catch (Exception error) { throw invalid(); }
                        if (!expiry.isAfter(Instant.now().plusSeconds(5))) throw invalid();
                        Map<String, String> headers = headers(node.path("headers"));
                        if (!node.path("browserSafe").isBoolean()) throw invalid();
                        boolean browserSafe = node.path("browserSafe").booleanValue();
                        if (consumer == MediaConsumer.BROWSER && (!browserSafe || !headers.isEmpty())) throw invalid();
                        String representation = text(node, "representationKey", 512);
                        if (!representation.matches(java.util.regex.Pattern.quote(quality) + ":[A-Za-z0-9]+:[1-9][0-9]{0,18}")) throw invalid();
                        String expectedTimeline = timeline(id, metadata.duration());
                        if (!expectedTimeline.equals(text(node, "timelineKey", 512))) throw new ApiRequestException("MEDIA_CONTENT_CHANGED");
                        return new ResolvedMedia(new ResourceKey(SOURCE, id), representation,
                                requiredUrl(node, "url"), mime, extension, expiry, headers, browserSafe, expectedTimeline);
                    }));
        });
    }

    public Mono<List<Music>> recommendations() {
        return Mono.defer(() -> {
            var snapshot = credentials.snapshot();
            return gateway.authenticatedRequest("fm", Map.of(), snapshot).map(node -> {
                credentials.requireCurrent(snapshot);
                List<Music> result = tracks(node);
                if (result.isEmpty()) throw new ApiRequestException("RECOMMENDATION_UNAVAILABLE: QQ音乐电台暂无内容");
                return result;
            });
        });
    }

    private Music track(JsonNode node, String expected) {
        requireSource(node);
        String id = mid(text(node, "id", 128));
        if (expected != null && !expected.equals(id)) throw invalid();
        JsonNode duration = node.path("duration");
        if (!duration.isIntegralNumber() || !duration.canConvertToLong() || duration.longValue() <= 0 || duration.longValue() > 86_400_000) throw invalid();
        JsonNode names = node.path("artists");
        if (!names.isArray() || names.size() > 100) throw invalid();
        List<String> artists = new ArrayList<>();
        for (JsonNode name : names) {
            if (!name.isTextual() || name.textValue().isBlank() || name.textValue().length() > 1024) throw invalid();
            artists.add(name.textValue());
        }
        String external = optionalUrl(node, "externalUrl");
        return new Music(id, text(node, "name", 2048), List.copyOf(artists), duration.longValue(), SOURCE,
                optionalUrl(node, "coverUrl"), external == null ? externalUrl(id) : external);
    }

    private List<Music> tracks(JsonNode node) {
        return tracks(node, 1000);
    }

    private List<Music> tracks(JsonNode node, int maximum) {
        if (!node.isArray() || node.size() > maximum) throw invalid();
        List<Music> result = new ArrayList<>();
        for (JsonNode item : node) result.add(track(item, null));
        return List.copyOf(result);
    }

    private Playlist playlist(JsonNode node, String expected) {
        requireSource(node);
        String id = playlistId(text(node, "id", 32));
        if (expected != null && !expected.equals(id)) throw invalid();
        int count = count(node.path("trackCount"));
        return new Playlist(id, text(node, "name", 2048), optionalUrl(node, "coverImgUrl"), count, SOURCE);
    }

    private AlbumInfo album(JsonNode node, String expected) {
        String id = mid(text(node, "id", 128));
        if (expected != null && !expected.equals(id)) throw invalid();
        int count = count(node.path("trackCount"));
        List<Music> songs = tracks(node.path("songs"), 2000);
        if (songs.isEmpty() || count != songs.size() || songs.stream().map(Music::id).distinct().count() != songs.size()) throw invalid();
        return new AlbumInfo(id, text(node, "name", 2048), List.of(), optionalUrl(node, "coverUrl"), count, songs);
    }

    private <T> SourcePage<T> page(JsonNode node, int offset, int limit, java.util.function.Function<JsonNode, T> mapping) {
        JsonNode items = node.path("items");
        if (!items.isArray() || items.size() > limit || !node.has("nextOffset") || !node.has("total")) throw invalid();
        Long total = node.get("total").isNull() ? null : nonnegative(node.get("total"));
        Long next = node.get("nextOffset").isNull() ? null : nonnegative(node.get("nextOffset"));
        if (next != null && (next <= offset || next > (long) offset + limit || next > 100_000 || total != null && next >= total)) throw invalid();
        if (total != null && ((long) offset + items.size() > total && !items.isEmpty())) throw invalid();
        List<T> output = new ArrayList<>();
        items.forEach(item -> output.add(mapping.apply(item)));
        return new SourcePage<>(List.copyOf(output), next == null ? null : next.intValue(), total);
    }

    private Map<String, String> headers(JsonNode node) {
        if (!node.isObject() || node.size() > 8) throw invalid();
        Map<String, String> headers = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!Set.of("referer", "user-agent", "accept").contains(entry.getKey().toLowerCase(Locale.ROOT))
                    || !entry.getValue().isTextual() || entry.getValue().textValue().length() > 4096
                    || entry.getValue().textValue().chars().anyMatch(Character::isISOControl)) throw invalid();
            headers.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(headers);
    }

    private void userReference(String value, boolean requireUin) {
        if (value == null || value.length() > 512 || !value.startsWith("q1.")) throw invalid();
        try {
            JsonNode data = mapper.readTree(Base64.getUrlDecoder().decode(value.substring(3)));
            if (!data.isObject() || data.isEmpty() || data.size() > 2) throw invalid();
            data.fieldNames().forEachRemaining(key -> { if (!Set.of("uin", "euin").contains(key)) throw invalid(); });
            if (data.has("uin") && (!data.path("uin").isTextual() || !data.path("uin").asText().matches("[1-9][0-9]{0,19}"))) throw invalid();
            if (data.has("euin") && (!data.path("euin").isTextual() || !data.path("euin").asText().matches("[A-Za-z0-9*_=+/-]{1,256}"))) throw invalid();
            if (requireUin && !data.has("uin")) throw new ApiRequestException("CAPABILITY_UNAVAILABLE: 此公开用户标识不支持创建歌单读取");
        } catch (ApiRequestException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }

    private String timeline(String id, long duration) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + duration + ":full").getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private String quality() {
        String quality = properties.getQqmusic().getQuality();
        if (!QUALITIES.contains(quality == null ? "" : quality)) throw new ApiRequestException("INVALID_REQUEST: QQ音乐音质配置无效");
        return quality;
    }

    private String mid(String id) {
        if (id == null || !id.matches("[A-Za-z0-9]{1,128}") || id.matches("[0-9]+")) throw invalid();
        return id;
    }

    private String playlistId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) throw invalid();
        return id;
    }

    private String query(String query) {
        if (query == null || query.isBlank() || query.length() > 200 || query.chars().anyMatch(Character::isISOControl)) throw invalid();
        return query;
    }

    private void pageBounds(int offset, int limit) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) throw invalid();
    }

    private long nonnegative(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
        return value.longValue();
    }

    private int count(JsonNode value) {
        long count = nonnegative(value);
        if (count > 1_000_000) throw invalid();
        return (int) count;
    }

    private void requireSource(JsonNode node) {
        if (!SOURCE.equals(node.path("platform").asText())) throw invalid();
    }

    private String text(JsonNode node, String key, int maximum) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum
                || value.textValue().chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.textValue();
    }

    private String requiredUrl(JsonNode node, String key) {
        String url = optionalUrl(node, key);
        if (url == null) throw invalid();
        return url;
    }

    private String optionalUrl(JsonNode node, String key) {
        if (!node.hasNonNull(key) || node.path(key).asText().isEmpty()) return null;
        String value = text(node, key, 8192);
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) throw invalid();
        } catch (Exception error) { throw invalid(); }
        return value;
    }

    private ApiRequestException invalid() { return new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐资源或响应无效"); }
    private ApiRequestException unsupported() { return new ApiRequestException("CAPABILITY_UNAVAILABLE: QQ音乐不支持此操作"); }
}
