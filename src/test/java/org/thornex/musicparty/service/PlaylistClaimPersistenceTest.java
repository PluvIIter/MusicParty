package org.thornex.musicparty.service;

import org.thornex.musicparty.source.ResourceKey;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.stream.LiveStreamService;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaylistClaimPersistenceTest {
    @TempDir Path directory;
    private QueuePersistenceService persistence(AppProperties props,MusicQueueManager queue,ObjectMapper mapper){
        var chat=mock(ChatService.class);when(chat.getHistoryFull()).thenReturn(List.of());
        return new QueuePersistenceService(queue,chat,props,mapper,mock(MusicPlayerService.class),mock(AuthController.class),mock(LiveStreamService.class));
    }
    @Test void settingAndClaimedOwnersSurviveNormalLoad() throws Exception {
        var props=new AppProperties();props.getQueue().setPersistenceFile(directory.resolve("queue.json").toString());props.getQueue().setAllowPlaylistClaim(true);
        var queue=new MusicQueueManager(props);
        var item=new MusicQueueItem("stable-id",new Music("1","Fixture",List.of("Fixture"),180000,"netease","", null),new UserSummary("current","session","Recovered",false),QueueItemStatus.READY, org.thornex.musicparty.enums.Priority.REGULAR);
        persistence(props,queue,new ObjectMapper()).saveQueueSnapshot(List.of(item));
        props.getQueue().setAllowPlaylistClaim(false);
        persistence(props,queue,new ObjectMapper()).loadData();
        assertTrue(props.getQueue().isAllowPlaylistClaim());assertEquals(List.of(item),queue.getQueueSnapshot());
        props.getQueue().setAllowPlaylistClaim(false);persistence(props,queue,new ObjectMapper()).saveData();props.getQueue().setAllowPlaylistClaim(true);
        persistence(props,queue,new ObjectMapper()).loadData();assertFalse(props.getQueue().isAllowPlaylistClaim());
    }
    private static MusicQueueItem item(String queueId, String musicId, String token) {
        return new MusicQueueItem(queueId, new Music(musicId, "Fixture", List.of("Fixture"), 180000, "netease", "", null),
                new UserSummary(token, "session", "Recovered", false), QueueItemStatus.READY, org.thornex.musicparty.enums.Priority.REGULAR);
    }
    @Test void proposedSnapshotKeepsStableFirstPlayIdsWithoutInferringFromMusicHistory() throws Exception {
        var props = new AppProperties();
        props.getQueue().setRetainPlaylist(true);
        Path file = directory.resolve("ledger.json");
        props.getQueue().setPersistenceFile(file.toString());
        var queue = new MusicQueueManager(props);
        var heard = item("heard-id", "heard", "source");
        var fresh = item("fresh-id", "fresh", "source");
        var removed = item("removed-id", "removed", "source");
        queue.restore(List.of(heard, fresh, removed), List.of(fresh.music()));
        queue.clearPlaybackFailure(heard);
        queue.clearPlaybackFailure(removed);
        var proposed = List.of(item("heard-id", "heard", "target"), item("fresh-id", "fresh", "target"),
                item("replacement-id", "removed", "target"));

        persistence(props, queue, new ObjectMapper()).saveQueueSnapshot(proposed);

        assertEquals(Set.of("heard-id", "removed-id"), queue.getPlayedQueueIds());
        assertEquals(List.of(heard, fresh, removed), queue.getQueueSnapshot());
        var restored = new MusicQueueManager(props);
        persistence(props, restored, new ObjectMapper()).loadData();
        assertEquals(proposed, restored.getQueueSnapshot());
        assertEquals(Set.of("heard-id"), restored.getPlayedQueueIds());
        restored.remove("replacement-id");
        assertEquals("fresh-id", restored.pollNext(org.thornex.musicparty.enums.PlayMode.SHUFFLE, false,
                Map.of(ResourceKey.encode("netease", "heard"), QueueItemStatus.READY, ResourceKey.encode("netease", "fresh"), QueueItemStatus.READY), Set.of("target")).queueId());
    }
    @Test void proposedSnapshotWritesAuthoritativeEmptyLedger() throws Exception {
        var props = new AppProperties();
        props.getQueue().setRetainPlaylist(true);
        props.getQueue().setPersistenceFile(directory.resolve("empty-ledger.json").toString());
        var queue = new MusicQueueManager(props);
        var fresh = item("fresh-id", "historic-music", "source");
        queue.restore(List.of(fresh), List.of(fresh.music()));
        persistence(props, queue, new ObjectMapper()).saveQueueSnapshot(List.of(fresh));

        var restored = new MusicQueueManager(props);
        persistence(props, restored, new ObjectMapper()).loadData();

        assertEquals(List.of(fresh), restored.getQueueSnapshot());
        assertTrue(restored.getPlayedQueueIds().isEmpty());
    }
    @Test void failedProposedSnapshotDoesNotPruneLiveLedger() throws Exception {
        var props = new AppProperties();
        props.getQueue().setRetainPlaylist(true);
        Path file = directory.resolve("failed-ledger.json");
        props.getQueue().setPersistenceFile(file.toString());
        Files.writeString(file, "previous snapshot");
        var queue = new MusicQueueManager(props);
        var heard = item("heard-id", "heard", "source");
        queue.restore(List.of(heard), List.of());
        queue.clearPlaybackFailure(heard);
        var mapper = mock(ObjectMapper.class);
        doThrow(new IOException("fixture write failure")).when(mapper).writeValue(any(File.class), any());

        assertThrows(IOException.class, () -> persistence(props, queue, mapper).saveQueueSnapshot(List.of()));

        assertEquals(Set.of("heard-id"), queue.getPlayedQueueIds());
        assertEquals(List.of(heard), queue.getQueueSnapshot());
        assertEquals("previous snapshot", Files.readString(file));
    }
    @Test void oldSnapshotsKeepConfiguredStartupValue() throws Exception {
        Path file=directory.resolve("old.json");Files.writeString(file,"{\"queue\":[],\"history\":[],\"settings\":{\"systemConfig\":{}}}");
        var props=new AppProperties();props.getQueue().setPersistenceFile(file.toString());
        persistence(props,new MusicQueueManager(props),new ObjectMapper()).loadData();assertFalse(props.getQueue().isAllowPlaylistClaim());
        props.getQueue().setAllowPlaylistClaim(true);persistence(props,new MusicQueueManager(props),new ObjectMapper()).loadData();assertTrue(props.getQueue().isAllowPlaylistClaim());
    }
    @Test void failedAtomicWritePreservesPreviousFileAndRemovesTemporary() throws Exception {
        Path file=directory.resolve("queue.json");Files.writeString(file,"previous valid snapshot");
        var props=new AppProperties();props.getQueue().setPersistenceFile(file.toString());
        var mapper=mock(ObjectMapper.class);doThrow(new IOException("fixture write failure")).when(mapper).writeValue(any(File.class),any());
        assertThrows(IOException.class,()->persistence(props,new MusicQueueManager(props),mapper).saveQueueSnapshot(List.of()));
        assertEquals("previous valid snapshot",Files.readString(file));
        try(var files=Files.list(directory)){assertEquals(List.of("queue.json"),files.map(p->p.getFileName().toString()).toList());}
    }
}
