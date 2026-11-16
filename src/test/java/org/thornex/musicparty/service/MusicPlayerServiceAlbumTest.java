package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MusicPlayerServiceAlbumTest {
    @Test void containerImportsHaveNoDuplicatePlayerEntryPoints() {
        var methods = Arrays.stream(MusicPlayerService.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        assertFalse(methods.contains("enqueueAlbum"));
        assertFalse(methods.contains("enqueuePlaylist"));
    }
}
