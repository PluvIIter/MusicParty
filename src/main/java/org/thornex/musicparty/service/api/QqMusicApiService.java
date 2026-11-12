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
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class QqMusicApiService implements IMusicApiService, SourceCredentialManager {
    private static final String SOURCE = "qqmusic";
    private static final int PAGE_SIZE = 100;
    private final QqMusicWebClient web;
    private final QqMusicCredentialService credentials;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public QqMusicApiService(QqMusicWebClient web, QqMusicCredentialService credentials, AppProperties properties, ObjectMapper mapper) {
        this.web = web;
        this.credentials = credentials;
        this.properties = properties;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public String getPlatformName() { return SOURCE; }
    @Override public boolean defaultDisabled() { return true; }
    @Override public DeliveryMode defaultDeliveryMode() { return DeliveryMode.SERVER_DOWNLOAD; }
    @Override public long credentialRevision() { return credentials.snapshot().revision(); }
    @Override public String credentialSourceId() { return SOURCE; }
    @Override public String credentialType() { return "qqmusic-credential"; }
    @Override public String credentialLabel() { return "QQ音乐Web登录凭据（JSON）"; }
    @Override public boolean isCredentialConfigured() { return credentials.snapshot().configured(); }
    @Override public void updateCredential(String value) { credentials.updateCookie(value); }
    public void updateCookie(String value) { updateCredential(value); }
    public boolean isCookieConfigured() { return isCredentialConfigured(); }
    @Override public String externalUrl(String id) { return "https://y.qq.com/n/ryqq/songDetail/" + mid(id); }

    @Override public SourceDescriptor descriptor() {
        return new SourceDescriptor(SOURCE, "QQ音乐", "music",
                new SourceCapabilities(true, true, true, true, true, true, !"FLAC".equals(properties.getQqmusic().getQuality()), true),
                Set.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD),
                List.of(new CollectionCategory("playlists", "公开歌单", "playlist", "CONTAINER")));
    }

    @Override public Mono<List<Music>> searchMusic(String keyword) {
        return Mono.defer(() -> web.publicGet("/search/search_by_type", Map.of("keyword", query(keyword), "search_type", 0, "num", 20, "page", 1))
                .map(data -> tracks(data.path("song"))));
    }

    private Mono<JsonNode> detail(String id) {
        return web.publicGet("/song/" + mid(id) + "/detail", Map.of()).map(data -> {
            JsonNode track = data.path("track");
            if (!id.equals(track.path("mid").asText())) throw invalid();
            return track;
        });
    }

    @Override public Mono<Music> getMusic(String id) { return Mono.defer(() -> detail(id).map(this::track)); }

    @Override public Mono<String> getLyric(String id) {
        return Mono.defer(() -> web.publicGet("/song/" + mid(id) + "/lyric", Map.of()).map(data -> {
            JsonNode lyric = data.path("lyric");
            if (!lyric.isTextual() || lyric.textValue().length() > 500_000) throw invalid();
            return lyric.textValue();
        }));
    }

    @Override public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        return Mono.defer(() -> web.publicGet("/search/search_by_type", Map.of("keyword", query(keyword), "search_type", 8, "num", 20, "page", 1))
                .map(data -> {
                    List<UserSearchResult> result = new ArrayList<>();
                    for (JsonNode user : array(data.path("user"), 100)) {
                        String uin = user.path("uin").asText("");
                        String euin = user.path("encrypt_uin").asText("");
                        Map<String, String> reference = new TreeMap<>();
                        if (uin.matches("[1-9][0-9]{0,19}")) reference.put("uin", uin);
                        if (!euin.isBlank() && euin.length() <= 256 && !euin.chars().anyMatch(Character::isISOControl)) reference.put("euin", euin);
                        if (reference.isEmpty()) continue;
                        String id;
                        try { id = "q1." + Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(reference)); }
                        catch (Exception error) { throw invalid(); }
                        result.add(new UserSearchResult(id, name(user.path("title").asText(user.path("name").asText(""))), url(user.path("pic").asText("")), SOURCE));
                    }
                    return List.copyOf(result);
                }));
    }

    @Override public Mono<SourcePage<Playlist>> publicCollections(String userId, String category, int offset, int limit) {
        return Mono.defer(() -> {
            if (!"playlists".equals(category)) throw unsupported();
            bounds(offset, limit);
            String uin = userUin(userId);
            return web.publicGet("/user/" + uin + "/created_songlists", Map.of()).map(data -> {
                JsonNode raw = array(data.path("playlists"), 10000);
                if ((data.has("finished") && !data.path("finished").asBoolean())
                        || data.path("total").asLong(raw.size()) > raw.size()) throw new ApiRequestException("COLLECTION_INCOMPLETE");
                List<Playlist> result = new ArrayList<>();
                int end = Math.min(raw.size(), offset + limit);
                for (int index = Math.min(offset, raw.size()); index < end; index++) {
                    JsonNode entry = raw.get(index);
                    if (entry.path("invalid").asBoolean(false) || entry.path("dir_show").asInt(0) == 2) continue;
                    result.add(playlist(entry));
                }
                return new SourcePage<>(result, end < raw.size() ? end : null, (long) raw.size());
            });
        });
    }

    @Override public Mono<Playlist> containerDetail(String type, String id) {
        return Mono.defer(() -> {
            if (!"playlist".equals(type)) throw unsupported();
            return songlist(id, 1).map(data -> {
                Playlist playlist = playlist(data.path("info"));
                if (!id.equals(playlist.id())) throw invalid();
                return playlist;
            });
        });
    }

    private Mono<JsonNode> songlist(String id, int page) {
        return web.publicGet("/songlist/" + numeric(id) + "/detail", Map.of("num", PAGE_SIZE, "page", page)).map(data -> {
            if (data.path("code").asInt(0) != 0 || data.path("subcode").asInt(0) != 0) throw new ApiRequestException("COLLECTION_NOT_PUBLIC");
            return data;
        });
    }

    @Override public Mono<SourcePage<Music>> containerPage(String type, String id, int offset, int limit) {
        return Mono.defer(() -> {
            if (!"playlist".equals(type)) throw unsupported();
            bounds(offset, limit);
            return collectSonglist(id, offset, limit, null, new ArrayList<>(), 0);
        });
    }

    private Mono<SourcePage<Music>> collectSonglist(String id, int cursor, int remaining, Long expectedTotal, List<Music> output, int requests) {
        if (requests > 2) return Mono.error(invalid());
        return songlist(id, cursor / PAGE_SIZE + 1).flatMap(data -> {
            long total = nonnegative(data.path("total"));
            if (expectedTotal != null && expectedTotal != total) throw new ApiRequestException("COLLECTION_CHANGED");
            JsonNode raw = array(data.path("songs"), PAGE_SIZE);
            int start = cursor % PAGE_SIZE;
            int take = Math.min(remaining, Math.max(0, raw.size() - start));
            for (int index = start; index < start + take; index++) output.add(track(raw.get(index)));
            int next = cursor + take;
            boolean more = next < total;
            if (more && take == 0) throw new ApiRequestException("PAGINATION_STALLED");
            if (take < remaining && next < total && raw.size() == PAGE_SIZE) {
                return collectSonglist(id, next, remaining - take, total, output, requests + 1);
            }
            return Mono.just(new SourcePage<>(List.copyOf(output), more ? next : null, total));
        });
    }

    @Override public Mono<AlbumInfo> getAlbum(String id) {
        return Mono.defer(() -> web.publicGet("/album/" + mid(id) + "/detail", Map.of()).flatMap(data -> {
            JsonNode album = data.path("album");
            if (!id.equals(album.path("mid").asText())) throw invalid();
            return albumSongs(id, 1, null, new ArrayList<>()).map(songs -> new AlbumInfo(id,
                    name(album.path("name").asText()), artists(data.path("singers")), cover(album), songs.size(), songs));
        })).timeout(Duration.ofSeconds(30));
    }

    private Mono<List<Music>> albumSongs(String id, int page, Long expected, List<Music> output) {
        if (page > 20) return Mono.error(new ApiRequestException("COLLECTION_INCOMPLETE"));
        return web.publicGet("/album/" + id + "/songs", Map.of("num", PAGE_SIZE, "page", page)).flatMap(data -> {
            if (!id.equals(data.path("album_mid").asText())) throw invalid();
            long total = nonnegative(data.path("total_num"));
            if (total > 2000 || (expected != null && expected != total)) throw new ApiRequestException("COLLECTION_INCOMPLETE");
            List<Music> songs = tracks(data.path("song_list"));
            output.addAll(songs);
            if (output.size() > total) throw invalid();
            if (output.size() < total) {
                if (songs.isEmpty()) throw new ApiRequestException("PAGINATION_STALLED");
                return albumSongs(id, page + 1, total, output);
            }
            if (output.isEmpty() || output.stream().map(Music::id).distinct().count() != output.size()) throw invalid();
            return Mono.just(List.copyOf(output));
        });
    }

    @Override public Mono<AlbumInfo> getAlbumForSong(String id) {
        return Mono.defer(() -> detail(id).flatMap(song -> getAlbum(mid(song.path("album").path("mid").asText()))));
    }

    @Override public Mono<ResolvedMedia> resolveMedia(String id, MediaConsumer consumer) {
        return Mono.defer(() -> resolve(id, quality(), consumer));
    }

    @Override public Mono<ResolvedMedia> resolveFallbackMedia(String id) {
        return Mono.defer(() -> {
            if (quality().equals("MP3_128")) throw unsupported();
            return resolve(id, "MP3_128", MediaConsumer.SERVER);
        });
    }

    private Mono<ResolvedMedia> resolve(String id, String quality, MediaConsumer consumer) {
        return Mono.defer(() -> {
            var snapshot = credentials.snapshot();
            snapshot.requireValid();
            if (consumer == null) throw invalid();
            return detail(id).flatMap(song -> {
                credentials.requireCurrent(snapshot);
                Music metadata = track(song);
                JsonNode file = song.path("file");
                String mediaMid = identifier(file.path("media_mid").asText());
                String field = quality.equals("FLAC") ? "size_flac" : quality.equals("MP3_320") ? "size_320mp3" : "size_128mp3";
                long size = nonnegative(file.path(field));
                if (size == 0) throw new ApiRequestException("MEDIA_UNAVAILABLE");
                int fileType = quality.equals("FLAC") ? 7 : quality.equals("MP3_320") ? 12 : 13;
                String extension = quality.equals("FLAC") ? ".flac" : ".mp3";
                String filename = (quality.equals("FLAC") ? "F000" : quality.equals("MP3_320") ? "M800" : "M500") + mediaMid + extension;
                return web.privateGet("/song/" + mid(id) + "/url", Map.of("file_type", fileType, "song_type", song.path("type").asInt(1), "media_mid", mediaMid), snapshot, false)
                        .map(data -> {
                            credentials.requireCurrent(snapshot);
                            JsonNode items = array(data.path("data"), 20);
                            JsonNode authorized = null;
                            for (JsonNode item : items) if (id.equals(item.path("mid").asText()) && filename.equals(item.path("filename").asText())) { authorized = item; break; }
                            if (authorized == null || !authorized.path("result").isIntegralNumber() || authorized.path("result").intValue() != 0
                                    || !authorized.path("ekey").asText("").isEmpty() || authorized.path("purl").asText("").isBlank()) throw new ApiRequestException("MEDIA_UNAVAILABLE");
                            long expiration = nonnegative(data.path("expiration"));
                            if (expiration <= 30) throw new ApiRequestException("MEDIA_UNAVAILABLE");
                            String purl = authorized.path("purl").asText();
                            URI relative;
                            try { relative = URI.create(purl); }
                            catch (Exception error) { throw invalid(); }
                            if (!relative.getPath().equals(filename) && !relative.getPath().equals("/" + filename)) throw new ApiRequestException("MEDIA_UNAVAILABLE");
                            if (relative.getRawAuthority() != null && !relative.isAbsolute()) throw new ApiRequestException("MEDIA_UNAVAILABLE");
                            String location = relative.isAbsolute() ? url(purl) : "https://isure.stream.qqmusic.qq.com/" + (purl.startsWith("/") ? purl.substring(1) : purl);
                            String host = URI.create(location).getHost();
                            if (!host.equals("qq.com") && !host.endsWith(".qq.com")) throw new ApiRequestException("MEDIA_UNAVAILABLE");
                            return new ResolvedMedia(new ResourceKey(SOURCE, id), quality + ":" + mediaMid + ":" + size,
                                    location, quality.equals("FLAC") ? "audio/flac" : "audio/mpeg", extension,
                                    Instant.now().plusSeconds(Math.min(expiration - 30, 3600)), Map.of(), true, timeline(id, mediaMid, metadata.duration()));
                        });
            });
        });
    }

    public Mono<List<Music>> recommendations() {
        return Mono.defer(() -> {
            var snapshot = credentials.snapshot();
            return web.privateGet("/recommend/get_guess_recommend", Map.of(), snapshot, true).map(data -> {
                credentials.requireCurrent(snapshot);
                List<Music> songs = tracks(data.path("songs"));
                if (songs.isEmpty()) throw new ApiRequestException("RECOMMENDATION_UNAVAILABLE");
                return songs;
            });
        });
    }

    private Music track(JsonNode song) {
        String id = mid(song.path("mid").asText());
        long seconds = nonnegative(song.path("interval"));
        if (seconds == 0 || seconds > 86400) throw invalid();
        return new Music(id, name(song.path("name").asText(song.path("title").asText())), artists(song.path("singer")), seconds * 1000,
                SOURCE, cover(song.path("album")), externalUrl(id));
    }

    private List<Music> tracks(JsonNode songs) {
        List<Music> result = new ArrayList<>();
        for (JsonNode song : array(songs, 2000)) result.add(track(song));
        return List.copyOf(result);
    }

    private List<String> artists(JsonNode singers) {
        List<String> result = new ArrayList<>();
        for (JsonNode singer : array(singers, 100)) {
            String name = singer.path("name").asText("");
            if (!name.isBlank()) result.add(name(name));
        }
        return List.copyOf(result);
    }

    private Playlist playlist(JsonNode item) {
        return new Playlist(numeric(item.path("id").asText()), name(item.path("title").asText()),
                url(item.path("picurl").asText("")), Math.toIntExact(nonnegative(item.path("songnum"))), SOURCE);
    }

    private String cover(JsonNode album) {
        String mid = album.path("mid").asText(album.path("pmid").asText(""));
        return mid.isBlank() ? null : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + identifier(mid) + ".jpg";
    }

    private String userUin(String reference) {
        try {
            if (reference == null || reference.length() > 512 || !reference.startsWith("q1.")) throw invalid();
            JsonNode value = mapper.readTree(Base64.getUrlDecoder().decode(reference.substring(3)));
            return numeric(value.path("uin").asText());
        } catch (Exception error) { throw new ApiRequestException("USER_ID_UNAVAILABLE: 此用户标识不支持公开歌单读取"); }
    }

    private String timeline(String id, String mediaMid, long duration) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((id + ":" + mediaMid + ":" + duration + ":full").getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private String quality() {
        String value = properties.getQqmusic().getQuality();
        if (!Set.of("MP3_128", "MP3_320", "FLAC").contains(value == null ? "" : value)) throw invalid();
        return value;
    }

    private JsonNode array(JsonNode node, int max) { if (!node.isArray() || node.size() > max) throw invalid(); return node; }
    private String identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9]{1,128}")) throw invalid(); return value; }
    private String mid(String value) { identifier(value); if (value.matches("[0-9]+")) throw invalid(); return value; }
    private String numeric(String value) { if (value == null || !value.matches("[1-9][0-9]{0,19}")) throw invalid(); return value; }
    private long nonnegative(JsonNode value) { if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid(); return value.longValue(); }
    private String query(String value) { if (value == null || value.isBlank() || value.length() > 200 || value.chars().anyMatch(Character::isISOControl)) throw invalid(); return value; }
    private String name(String value) { return org.springframework.web.util.HtmlUtils.htmlUnescape(query(value.replaceAll("<[^>]*>", ""))); }
    private void bounds(int offset, int limit) { if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw invalid(); }
    private String url(String value) {
        if (value == null || value.isBlank()) return null;
        try { URI uri = URI.create(value); if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw invalid(); }
        catch (Exception error) { throw invalid(); }
        return value;
    }
    private ApiRequestException invalid() { return new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐资源或响应无效"); }
    private ApiRequestException unsupported() { return new ApiRequestException("CAPABILITY_UNAVAILABLE: QQ音乐不支持此操作"); }
}
