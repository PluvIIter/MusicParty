package org.thornex.musicparty.dto;

public record PlayerEvent(
        String type,
        String message,
        String user
) {}
