package org.thornex.musicparty.dto;

import java.util.List;

public record PlayerState(
        NowPlayingInfo nowPlaying,
        List<MusicQueueItem> queue,
        String playMode,
        boolean isShuffle,
        boolean isFairShuffle,
        boolean allowOfflineShuffle,
        List<UserSummary> onlineUsers,
        boolean isPaused,
        boolean isPauseLocked,
        boolean isSkipLocked,
        boolean isPlayModeLocked,
        boolean isLoading,
        int streamListenerCount,
        boolean isStreamEnabled,
        boolean isVoteSkipEnabled,
        double voteSkipThreshold,
        int voteSkipWaitTime,
        int currentVotes,
        int eligibleUsers,
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
            boolean bilibiliEnabled,
            boolean voteSkipEnabled,
            double voteSkipThreshold,
            int voteSkipWaitTime
    ) {}
}