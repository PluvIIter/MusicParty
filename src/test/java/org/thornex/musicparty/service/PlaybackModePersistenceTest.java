package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaybackModePersistenceTest {
    @TempDir Path temporary;
    final AppProperties properties = new AppProperties();
    final MusicQueueManager queue = new MusicQueueManager(properties);
    final MusicPlayerService player = mock(MusicPlayerService.class);
    final ObjectMapper mapper = new ObjectMapper();

    QueuePersistenceService persistence() {
        properties.getQueue().setPersistenceFile(temporary.resolve("queue.json").toString());
        var chat = mock(ChatService.class);
        when(chat.getHistoryFull()).thenReturn(List.of());
        return new QueuePersistenceService(queue, chat, properties, mapper, player, mock(AuthController.class), mock(LiveStreamService.class));
    }

    @Test void unversionedAndUnknownSnapshotsAreRejectedWithoutChangingOrOverwritingData() throws Exception {
        for (String prefix : List.of("", "\"schemaVersion\":0,", "\"schemaVersion\":2,")) {
            var persistence = persistence();
            String contents = "{" + prefix + "\"queue\":[],\"settings\":{\"player\":{\"playMode\":\"SHUFFLE\"}}}";
            Files.writeString(temporary.resolve("queue.json"), contents);
            persistence.loadData();
            verifyNoInteractions(player);
            assertThrows(IOException.class, persistence::saveNow);
            assertThrows(IOException.class, () -> persistence.saveQueueSnapshot(List.of()));
            assertEquals(contents, Files.readString(temporary.resolve("queue.json")));
        }
    }

    @Test void explicitModeNeverRemapsAndCurrentSchemaSurvivesBothSnapshotPaths() throws Exception {
        var persistence = persistence();
        when(player.getPlayerSettings()).thenReturn(new SettingsSnapshot.PlayerSettings("SHUFFLE", false, false, 0.5, 15, false, false, false));
        var a = queue.add(new Music("a", "a", List.of(), 1000, "netease", "", null), new UserSummary("A", "A", "Alice", false), QueueItemStatus.READY);
        var b = queue.add(new Music("b", "b", List.of(), 1000, "netease", "", null), a.enqueuedBy(), QueueItemStatus.READY);
        queue.setMemberShuffle("A", false);
        queue.restorePlayedQueueIds(Set.of(a.queueId()));
        queue.reorder(b.queueId(), a.queueId(), "A", PlayMode.ROUND_ROBIN);
        for (boolean atomic : List.of(false, true)) {
            if (atomic) persistence.saveQueueSnapshot(queue.getQueueSnapshot()); else persistence.saveNow();
            var root = mapper.readTree(temporary.resolve("queue.json").toFile());
            assertEquals(1, root.path("schemaVersion").intValue());
            assertFalse(root.has("playbackModeVersion"));
            assertFalse(root.path("settings").path("player").has("fairShuffle"));
            queue.clearAll();
            persistence.loadData();
            assertEquals(List.of(b.queueId(), a.queueId()), queue.getQueueSnapshot().stream().map(MusicQueueItem::queueId).toList());
            assertFalse(queue.isMemberShuffle("A"));
            assertEquals(Set.of(a.queueId()), queue.getPlayedQueueIds());
        }
        verify(player, times(2)).applyPlayerSettings(argThat(settings -> "SHUFFLE".equals(settings.playMode())));
    }
}
