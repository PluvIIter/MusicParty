package org.thornex.musicparty.dto;

public record AdminConfigUpdateRequest(
    Integer maxSize,
    Integer historySize,
    Integer maxUserSongs,
    Integer maxPlaylistImportSize,
    Integer maxChatHistorySize,
    Long minChatIntervalMs,
    Integer maxChatMessageLength,
    Boolean neteaseEnabled,
    Boolean bilibiliEnabled,
    Integer bilibiliMaxDurationMinutes,
    Boolean voteSkipEnabled,
    Double voteSkipThreshold,
    Integer voteSkipWaitTime,
        Boolean retainPlaylist,
    Boolean allowSeek,
    java.util.Map<String, SourceSettings> sources
) {
    public AdminConfigUpdateRequest(Integer maxSize, Integer historySize, Integer maxUserSongs,
                                    Integer maxPlaylistImportSize, Integer maxChatHistorySize, Long minChatIntervalMs,
                                    Integer maxChatMessageLength, Boolean neteaseEnabled, Boolean bilibiliEnabled,
                                    Integer bilibiliMaxDurationMinutes, Boolean voteSkipEnabled, Double voteSkipThreshold,
                                    Integer voteSkipWaitTime, Boolean retainPlaylist, Boolean allowSeek) {
        this(maxSize, historySize, maxUserSongs, maxPlaylistImportSize, maxChatHistorySize, minChatIntervalMs,
                maxChatMessageLength, neteaseEnabled, bilibiliEnabled, bilibiliMaxDurationMinutes, voteSkipEnabled,
                voteSkipThreshold, voteSkipWaitTime, retainPlaylist, allowSeek, null);
    }
}
