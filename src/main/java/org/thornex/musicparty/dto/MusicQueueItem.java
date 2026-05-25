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
        if (priority == null) {
            // Determine priority from queueId for backward compatibility
            if (queueId != null && queueId.startsWith("TOP-")) {
                priority = Priority.GLOBAL_TOP;
                queueId = queueId.substring(4);
            } else if (queueId != null && queueId.startsWith("USERTOP-")) {
                priority = Priority.USER_TOP;
                queueId = queueId.substring(8);
            } else {
                priority = Priority.REGULAR;
            }
        }
    }

    public MusicQueueItem(String queueId, Music music, UserSummary enqueuedBy, QueueItemStatus status) {
        this(queueId, music, enqueuedBy, status, Priority.REGULAR);
    }

    public MusicQueueItem withStatus(QueueItemStatus newStatus) {
        return new MusicQueueItem(queueId, music, enqueuedBy, newStatus, priority);
    }

    public MusicQueueItem withPriority(Priority newPriority) {
        return new MusicQueueItem(queueId, music, enqueuedBy, status, newPriority);
    }
}
