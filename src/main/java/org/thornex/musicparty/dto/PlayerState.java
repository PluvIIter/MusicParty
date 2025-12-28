package org.thornex.musicparty.dto;

import java.util.List;

public record PlayerState(
        NowPlayingInfo nowPlaying,
        List<MusicQueueItem> queue,
        boolean isShuffle,
        List<UserSummary> onlineUsers,
        boolean isPaused, // NEW: True if the player is currently paused
        long pauseTimeMillis,
        long serverTimestamp,
        boolean isLoading
) {}