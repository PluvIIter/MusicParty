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
        /** 每次房间级 seek 自增；客户端据此重锚本地时钟（否则回退会被当成迟到包忽略）。 */
        long seekNonce,
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
            int bilibiliMaxDurationMinutes,
            boolean voteSkipEnabled,
            double voteSkipThreshold,
            int voteSkipWaitTime,
            boolean neteaseCookieConfigured,
            PrivateDjConfigSummary privateDj,
            boolean allowPlaylistClaim,
            boolean retainPlaylist,
            /** 是否允许拖动进度条（管理面板开关，默认关闭）。 */
            boolean allowSeek
    ) {
        public record PrivateDjConfigSummary(
                String mode,
                boolean fillBlankEnabled,
                boolean joinQueueEnabled,
                boolean custodyEnabled
        ) {}
    }
}