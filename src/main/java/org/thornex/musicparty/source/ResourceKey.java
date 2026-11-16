package org.thornex.musicparty.source;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;

public record ResourceKey(String sourceId, String id) {
    public ResourceKey {
        if (sourceId == null || !sourceId.matches("[a-z][a-z0-9-]{0,63}")
                || id == null || id.isBlank() || id.length() > 512
                || id.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid resource identity");
        }
    }

    public static ResourceKey of(Music music) {
        return new ResourceKey(music.platform(), music.id());
    }

    public static ResourceKey of(PlayableMusic music) {
        return new ResourceKey(music.platform(), music.id());
    }

    public static String encode(String sourceId, String id) {
        return new ResourceKey(sourceId, id).encoded();
    }

    public String encoded() {
        return sourceId + ":" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }
}
