package org.thornex.musicparty.dto;

public record UserSearchResult(
        String id,
        String name,
        String avatarUrl,
        String platform
) {}
