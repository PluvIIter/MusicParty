package org.thornex.musicparty.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.dto.SeekRequest;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.UserService;

import java.util.Optional;

import static org.mockito.Mockito.*;

class MusicSocketControllerSeekTest {
    @Test
    void forwardsClientInstanceWithoutSubstitutingCurrentPlayback() throws Exception {
        MusicPlayerService player = mock(MusicPlayerService.class);
        UserService users = mock(UserService.class);
        User member = mock(User.class);
        when(users.getUser("session")).thenReturn(Optional.of(member));
        MusicSocketController controller = new MusicSocketController(player, users,
                mock(SimpMessagingTemplate.class), mock(ChatService.class));
        ObjectMapper mapper = new ObjectMapper();

        controller.seek(mapper.readValue("{\"positionMs\":12000,\"playbackId\":\"old-pass\"}", SeekRequest.class), "session");
        controller.seek(mapper.readValue("{\"positionMs\":12000}", SeekRequest.class), "session");

        verify(player).seekTo("session", 12000, "old-pass");
        verify(player).seekTo("session", 12000, null);
        verifyNoMoreInteractions(player);
    }

    @Test
    void guestsAndNullRequestsCannotSeek() {
        MusicPlayerService player = mock(MusicPlayerService.class);
        UserService users = mock(UserService.class);
        MusicSocketController controller = new MusicSocketController(player, users,
                mock(SimpMessagingTemplate.class), mock(ChatService.class));
        controller.seek(new SeekRequest(12000, "pass"), "guest");
        controller.seek(null, "guest");
        verifyNoInteractions(player);
    }
}
