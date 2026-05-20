package org.thornex.musicparty.dto;

import java.util.List;

public record PlayerState(
        NowPlayingInfo nowPlaying,
        List<MusicQueueItem> queue,
        boolean isShuffle,
        boolean isFairShuffle,
        boolean allowOfflineShuffle,
        List<UserSummary> onlineUsers,
        boolean isPaused,
        boolean isPauseLocked,
        boolean isSkipLocked,
        boolean isShuffleLocked,
        boolean isLoading,
        int streamListenerCount,
        boolean isStreamEnabled,
        AppConfigSummary config
) {
    public record AppConfigSummary(
            int maxQueueSize,
            int maxHistorySize,
            int maxUserSongs,
            int maxPlaylistImportSize,
            int maxChatHistorySize,
            long minChatIntervalMs,
            int maxChatMessageLength,
            boolean neteaseEnabled,
            boolean bilibiliEnabled
    ) {}
}