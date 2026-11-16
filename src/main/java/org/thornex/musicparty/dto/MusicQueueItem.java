package org.thornex.musicparty.dto;

import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.Priority;

public record MusicQueueItem(
        String queueId, // A unique ID for this specific item in the queue
        Music music,
        UserSummary enqueuedBy,
        QueueItemStatus status, // MODIFIED: Changed from String to QueueItemStatus enum
        Priority priority
) {
    public MusicQueueItem {
        java.util.Objects.requireNonNull(priority);
    }

    public MusicQueueItem withStatus(QueueItemStatus newStatus) {
        return new MusicQueueItem(queueId, music, enqueuedBy, newStatus, priority);
    }

    public MusicQueueItem withPriority(Priority newPriority) {
        return new MusicQueueItem(queueId, music, enqueuedBy, status, newPriority);
    }
}
