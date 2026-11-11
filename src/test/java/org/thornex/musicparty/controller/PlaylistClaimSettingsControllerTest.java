package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.*;
import java.io.IOException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class PlaylistClaimSettingsControllerTest {
    @Test void missingOrWrongAdminCannotReadOrWrite() throws Exception {
        var props=new AppProperties();props.setAdminPassword("fixture-admin");
        var persistence=mock(QueuePersistenceService.class);var player=mock(MusicPlayerService.class);
        var controller=new PlaylistClaimSettingsController(props,new MusicQueueManager(props),player,persistence);
        for(String password:new String[]{null,"wrong"}){
            assertEquals(403,controller.get(password).getStatusCode().value());
            assertEquals(403,controller.update(password,Map.of("allowPlaylistClaim",true)).getStatusCode().value());
        }
        assertFalse(props.getQueue().isAllowPlaylistClaim());verifyNoInteractions(player,persistence);
    }
    @Test void rejectsNonBooleanAndPersistsBeforeBroadcast() throws Exception {
        var props=new AppProperties();props.setAdminPassword("fixture-admin");
        var persistence=mock(QueuePersistenceService.class);var player=mock(MusicPlayerService.class);
        var controller=new PlaylistClaimSettingsController(props,new MusicQueueManager(props),player,persistence);
        assertEquals(400,controller.update("fixture-admin",Map.of()).getStatusCode().value());
        assertEquals(400,controller.update("fixture-admin",Map.of("allowPlaylistClaim","false")).getStatusCode().value());
        assertEquals(200,controller.update("fixture-admin",Map.of("allowPlaylistClaim",true)).getStatusCode().value());assertTrue(props.getQueue().isAllowPlaylistClaim());
        var order=inOrder(persistence,player);order.verify(persistence).saveQueueSnapshot(anyList());order.verify(player).broadcastFullPlayerState();
        assertEquals("no-store",controller.get("fixture-admin").getHeaders().getCacheControl());
    }
    @Test void failedSaveRestoresSettingAndDoesNotBroadcast() throws Exception {
        var props=new AppProperties();props.setAdminPassword("fixture-admin");
        var persistence=mock(QueuePersistenceService.class);var player=mock(MusicPlayerService.class);
        doThrow(new IOException("fixture disk failure")).when(persistence).saveQueueSnapshot(anyList());
        var controller=new PlaylistClaimSettingsController(props,new MusicQueueManager(props),player,persistence);
        assertThrows(IOException.class,()->controller.update("fixture-admin",Map.of("allowPlaylistClaim",true)));
        assertFalse(props.getQueue().isAllowPlaylistClaim());verifyNoInteractions(player);
    }
}
