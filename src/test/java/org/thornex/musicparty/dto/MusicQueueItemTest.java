package org.thornex.musicparty.dto;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.enums.Priority;
import org.thornex.musicparty.enums.QueueItemStatus;

import static org.junit.jupiter.api.Assertions.*;

class MusicQueueItemTest {
    @Test void canonicalPriorityNeverInterpretsQueueIdAsMigrationMetadata() {
        MusicQueueItem item = new MusicQueueItem("TOP-resource", null, null, QueueItemStatus.READY, Priority.REGULAR);
        assertEquals("TOP-resource", item.queueId()); assertEquals(Priority.REGULAR, item.priority());
        assertEquals("TOP-resource", item.withPriority(Priority.GLOBAL_TOP).queueId());
        assertEquals(Priority.REGULAR, item.withStatus(QueueItemStatus.FAILED).priority());
    }

    @Test void missingPriorityIsRejectedInsteadOfInferringLegacyPrefixes() {
        for (String id : new String[]{"plain", "TOP-old", "USERTOP-old"}) {
            assertThrows(NullPointerException.class, () -> new MusicQueueItem(id, null, null, QueueItemStatus.READY, null));
        }
    }
}
