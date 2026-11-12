package org.thornex.musicparty.dto;

public record AdminConfigUpdateRequest(
        Integer maxQueueSize,
        Integer maxHistorySize,
        Integer maxUserSongs,
        Integer maxPlaylistImportSize,
        Integer maxChatHistorySize,
        Long minChatIntervalMs,
        Integer maxChatMessageLength,
        Integer bilibiliMaxDurationMinutes,
        Boolean voteSkipEnabled,
        Double voteSkipThreshold,
        Integer voteSkipWaitTime,
        Boolean retainPlaylist,
        Boolean allowSeek,
        java.util.Map<String, SourceSettings> sources
) {}
