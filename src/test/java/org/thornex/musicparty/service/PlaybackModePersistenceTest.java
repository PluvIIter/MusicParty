package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.stream.LiveStreamService;
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
    @Test void legacyFairRandomMigratesButExplicitNewRandomDoesNot() throws Exception {
        var persistence = persistence();
        for (String fair : List.of("true", "null", "false")) {
            for (boolean versioned : List.of(false, true)) {
                Files.writeString(temporary.resolve("queue.json"), "{\"queue\":[]," + (versioned ? "\"playbackModeVersion\":2," : "")
                        + "\"settings\":{\"player\":{\"playMode\":\"SHUFFLE\",\"fairShuffle\":" + fair + "}}}");
                persistence.loadData();
                String expected = !versioned && !fair.equals("false") ? "ROUND_ROBIN" : "SHUFFLE";
                verify(player).applyPlayerSettings(argThat(settings -> expected.equals(settings.playMode())));
                clearInvocations(player);
            }
        }
    }
    @Test void preferencesOrderAndModeVersionSurviveBothSnapshotPaths() throws Exception {
        var persistence = persistence();
        when(player.getPlayerSettings()).thenReturn(new SettingsSnapshot.PlayerSettings("SHUFFLE", true, false, false, 0.5, 15, false, false, false));
        var a = queue.add(new Music("a", "a", List.of(), 1000, "netease", ""), new UserSummary("A", "A", "Alice", false), QueueItemStatus.READY);
        var b = queue.add(new Music("b", "b", List.of(), 1000, "netease", ""), a.enqueuedBy(), QueueItemStatus.READY);
        queue.setMemberShuffle("A", false);
        queue.restorePlayedQueueIds(Set.of(a.queueId()));
        queue.reorder(b.queueId(), a.queueId(), "A");
        for (boolean atomic : List.of(false, true)) {
            if (atomic) persistence.saveQueueSnapshot(queue.getQueueSnapshot()); else persistence.saveNow();
            assertEquals(2, mapper.readTree(temporary.resolve("queue.json").toFile()).get("playbackModeVersion").intValue());
            queue.clearAll();
            persistence.loadData();
            assertEquals(List.of(b.queueId(), a.queueId()), queue.getQueueSnapshot().stream().map(MusicQueueItem::queueId).toList());
            assertFalse(queue.isMemberShuffle("A"));
            assertEquals(Set.of(a.queueId()), queue.getPlayedQueueIds());
        }
        verify(player, times(2)).applyPlayerSettings(argThat(settings -> "SHUFFLE".equals(settings.playMode())));
    }
}
