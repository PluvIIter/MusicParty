package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.handler.annotation.MessageMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MusicSocketControllerAlbumTest {
    @Test void obsoleteImportsAndFullStateResyncHaveNoWebsocketMappings() {
        var mappings = Arrays.stream(MusicSocketController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(MessageMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(MessageMapping.class).value())).toList();
        assertFalse(mappings.contains("/enqueue/album"));
        assertFalse(mappings.contains("/enqueue/playlist"));
        assertFalse(mappings.contains("/player/resync"));
        assertFalse(mappings.contains("/control/toggle-shuffle"));
    }
}
