package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.dto.AlbumImportResult;
import org.thornex.musicparty.dto.EnqueueAlbumRequest;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.UserService;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicSocketControllerAlbumTest {
    @Test
    void sendsImportResultOnlyToRequestingSession() {
        MusicPlayerService player = mock(MusicPlayerService.class);
        SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
        EnqueueAlbumRequest request = new EnqueueAlbumRequest("netease", "900", "request");
        AlbumImportResult result = new AlbumImportResult("request", "900", "Album", 3, 3, 0, 0, "Added", false);
        when(player.enqueueAlbum(request, "session")).thenReturn(Mono.just(result));
        MusicSocketController controller = new MusicSocketController(player, mock(UserService.class), messages, mock(ChatService.class));

        controller.enqueueAlbum(request, "session");

        verify(messages).convertAndSendToUser(eq("session"), eq("/queue/album/import"), same(result),
                argThat((java.util.Map<String, Object> headers) -> "session".equals(headers.get(SimpMessageHeaderAccessor.SESSION_ID_HEADER))));
        verifyNoMoreInteractions(messages);
    }
}
