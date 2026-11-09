package org.thornex.musicparty.service;

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
        var item=new MusicQueueItem("stable-id",new Music("1","Fixture",List.of("Fixture"),180000,"netease",""),new UserSummary("current","session","Recovered",false),QueueItemStatus.READY);
        persistence(props,queue,new ObjectMapper()).saveQueueSnapshot(List.of(item));
        props.getQueue().setAllowPlaylistClaim(false);
        persistence(props,queue,new ObjectMapper()).loadData();
        assertTrue(props.getQueue().isAllowPlaylistClaim());assertEquals(List.of(item),queue.getQueueSnapshot());
        props.getQueue().setAllowPlaylistClaim(false);persistence(props,queue,new ObjectMapper()).saveData();props.getQueue().setAllowPlaylistClaim(true);
        persistence(props,queue,new ObjectMapper()).loadData();assertFalse(props.getQueue().isAllowPlaylistClaim());
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
