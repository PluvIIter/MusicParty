package org.thornex.musicparty.dto;

public record MusicQueueItem(
        String queueId, // A unique ID for this specific item in the queue
        Music music,
        UserSummary enqueuedBy,
        String status
) {
    public MusicQueueItem withStatus(String newStatus) {
        return new MusicQueueItem(queueId, music, enqueuedBy, newStatus);
    }
}
