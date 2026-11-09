package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MusicQueueManagerUserLimitTest {
    private Music music(String id) {
        return new Music(id, "Song " + id, List.of("Artist"), 180_000L, "netease", null);
    }

    private UserSummary user(String token, String session) {
        return new UserSummary(token, session, token, false);
    }

    @Test
    void concurrentAddsCannotExceedMemberQuota() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getQueue().setMaxUserSongs(2);
        MusicQueueManager queue = new MusicQueueManager(properties);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(12);
        try {
            List<Future<MusicQueueItem>> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String id = String.valueOf(i);
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return queue.add(music(id), user("member", "session"), QueueItemStatus.READY);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<MusicQueueItem> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS) != null) accepted++;
            }
            assertEquals(2, accepted);
            assertEquals(2, queue.getQueueSnapshot().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void quotaUsesStableMemberTokenAcrossSessionsAndDoesNotBlockOthers() {
        AppProperties properties = new AppProperties();
        properties.getQueue().setMaxUserSongs(1);
        MusicQueueManager queue = new MusicQueueManager(properties);
        assertNotNull(queue.add(music("1"), user("alice", "old-session"), QueueItemStatus.READY));
        assertNull(queue.add(music("2"), user("alice", "new-session"), QueueItemStatus.READY));
        assertNotNull(queue.add(music("3"), user("bob", "another-session"), QueueItemStatus.READY));
        assertEquals(2, queue.getQueueSnapshot().size());
    }

    @Test
    void removingASongReleasesQuotaAndDuplicateAttemptsDoNotConsumeIt() {
        AppProperties properties = new AppProperties();
        properties.getQueue().setMaxUserSongs(1);
        MusicQueueManager queue = new MusicQueueManager(properties);
        UserSummary member = user("alice", "session");
        MusicQueueItem first = queue.add(music("1"), member, QueueItemStatus.READY);
        assertNotNull(first);
        assertNull(queue.add(music("1"), user("bob", "other-session"), QueueItemStatus.READY));
        assertNotNull(queue.add(music("2"), user("bob", "other-session"), QueueItemStatus.READY));
        queue.remove(first.queueId());
        assertNotNull(queue.add(music("3"), member, QueueItemStatus.READY));
    }
}
