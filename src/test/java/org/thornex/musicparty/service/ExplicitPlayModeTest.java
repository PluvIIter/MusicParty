package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExplicitPlayModeTest {
    final UserService users = mock(UserService.class);
    final MusicQueueManager queue = new MusicQueueManager(new AppProperties());
    final MusicPlayerService player = PlayerTestFactory.create(List.of(), users,
            mock(LiveStreamService.class), queue, mock(ApplicationEventPublisher.class), new AppProperties(), mock(PrivateDjService.class));

    @Test void systemSelectsEveryExplicitMode() {
        for (String mode : List.of("SHUFFLE", "ROUND_ROBIN", "REPEAT_ONE", "SEQUENTIAL")) {
            assertTrue(player.setPlayMode("SYSTEM", mode));
            var state = player.getCurrentPlayerState();
            assertEquals(mode, state.playMode());
        }
    }
    @Test void guestUnknownInvalidAndLockedRequestsDoNotChangeMode() {
        when(users.getUser("member")).thenReturn(Optional.of(new User("A", "member", "Alice")));
        when(users.getUser("guest")).thenReturn(Optional.of(new User("g", "guest", "游客")));
        assertFalse(player.setPlayMode("gone", "SHUFFLE"));
        assertFalse(player.setPlayMode("guest", "SHUFFLE"));
        assertFalse(player.setPlayMode("member", "INVALID"));
        assertFalse(player.setPlayMode("SYSTEM", null));
        player.setLock("SHUFFLE", true);
        assertFalse(player.setPlayMode("member", "ROUND_ROBIN"));
        assertEquals("SEQUENTIAL", player.getCurrentPlayerState().playMode());
        assertTrue(player.setPlayMode("SYSTEM", "ROUND_ROBIN"));
    }
    @Test void memberPreferenceAppearsInStateWithoutChangingRoomMode() {
        queue.setMemberShuffle("A", false);
        assertEquals(Map.of("A", false), player.getCurrentPlayerState().memberShufflePreferences());
        assertEquals("SEQUENTIAL", player.getCurrentPlayerState().playMode());
    }
}
