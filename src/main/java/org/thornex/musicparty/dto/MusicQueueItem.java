package org.thornex.musicparty.dto;

import org.thornex.musicparty.enums.QueueItemStatus;

public record MusicQueueItem(
        String queueId, // A unique ID for this specific item in the queue
        Music music,
        UserSummary enqueuedBy,
        QueueItemStatus status // MODIFIED: Changed from String to QueueItemStatus enum
) {
    public MusicQueueItem withStatus(QueueItemStatus newStatus) {
        return new MusicQueueItem(queueId, music, enqueuedBy, newStatus);
    }
}
