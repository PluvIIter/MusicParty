package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.*;
import org.thornex.musicparty.service.stream.LiveStreamService;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirstPlayPersistenceTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private MusicQueueItem add(MusicQueueManager q, String id) {
        return q.add(new Music(id, id, List.of("Test"), 1000, "netease", ""), new UserSummary("A", "A", "A", false), QueueItemStatus.READY);
    }
    private QueuePersistenceService persistence(MusicQueueManager q, AppProperties props) {
        ChatService chat = mock(ChatService.class);
        when(chat.getHistoryFull()).thenReturn(List.of());
        return new QueuePersistenceService(q, chat, props, mapper, mock(MusicPlayerService.class), mock(AuthController.class), mock(LiveStreamService.class));
    }
    @Test void roundTripRetainsFirstPlayLedgerInExistingSnapshot() throws Exception {
        AppProperties props = new AppProperties();
        props.getQueue().setRetainPlaylist(true);
        props.getQueue().setPersistenceFile(temp.resolve("queue.json").toString());
        MusicQueueManager q = new MusicQueueManager(props);
        MusicQueueItem old = add(q, "old"), fresh = add(q, "new");
        q.clearPlaybackFailure(old);
        // Song history can contain the same music from an earlier deleted queue entry.
        q.addToHistory(fresh.music());
        persistence(q, props).saveNow();
        assertEquals(old.queueId(), mapper.readTree(temp.resolve("queue.json").toFile()).path("playedQueueIds").get(0).asText());
        MusicQueueManager restored = new MusicQueueManager(props);
        persistence(restored, props).loadData();
        assertEquals(Set.of(old.queueId()), restored.getPlayedQueueIds());
        assertEquals(fresh.queueId(), restored.pollNext(PlayMode.SHUFFLE, true, false,
                Map.of("old", QueueItemStatus.READY, "new", QueueItemStatus.READY), Set.of("A")).queueId());
    }
    @Test void oldSnapshotMigratesOnlyKnownHistoryAndEmptyLedgerIsAuthoritative() throws Exception {
        AppProperties props = new AppProperties();
        props.getQueue().setRetainPlaylist(true);
        Path file = temp.resolve("old.json"); props.getQueue().setPersistenceFile(file.toString());
        MusicQueueManager q = new MusicQueueManager(props);
        MusicQueueItem old = add(q, "old"); add(q, "new");
        mapper.writeValue(file.toFile(), Map.of("queue", q.getQueueSnapshot(), "history", List.of(old.music())));
        MusicQueueManager restored = new MusicQueueManager(props);
        persistence(restored, props).loadData();
        assertEquals(Set.of(old.queueId()), restored.getPlayedQueueIds());
        mapper.writeValue(file.toFile(), Map.of("queue", q.getQueueSnapshot(), "history", List.of(old.music()), "playedQueueIds", List.of()));
        persistence(restored, props).loadData();
        assertTrue(restored.getPlayedQueueIds().isEmpty());
    }
}
