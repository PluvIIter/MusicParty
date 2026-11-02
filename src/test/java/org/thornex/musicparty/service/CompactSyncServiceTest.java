package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.controller.CompactSyncController;
import org.thornex.musicparty.dto.PlayerState;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.QueueUpdateEvent;
import org.thornex.musicparty.event.UserCountChangeEvent;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompactSyncServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MusicPlayerService player;
    private SimpMessagingTemplate messages;
    private CompactSyncService sync;

    private PlayerState state(long position) throws Exception {
        return mapper.readValue("""
                {"nowPlaying":{"music":{"id":"101","name":"Fixture","artists":["Artist"],"duration":180000,
                  "platform":"netease","url":"https://audio.example.invalid/fixture.wav","coverUrl":"","needsProxy":false},
                  "currentPosition":%d,"likeMarkers":[],"likedUserIds":[]},
                  "queue":[],"onlineUsers":[],"playMode":"SEQUENTIAL","isPaused":true,
                  "config":{"maxUserSongs":100,"neteaseEnabled":true}}
                """.formatted(position), PlayerState.class);
    }

    @BeforeEach void setup() throws Exception {
        player = mock(MusicPlayerService.class);
        messages = mock(SimpMessagingTemplate.class);
        when(player.getCurrentPlayerState()).thenReturn(state(10000));
        sync = new CompactSyncService(player, mapper, messages);
    }

    private ObjectNode lastPatch() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messages, atLeastOnce()).convertAndSend(eq("/topic/player/compact"), payload.capture());
        return (ObjectNode) payload.getValue();
    }

    @Test void heartbeatOnlyCarriesChangedPositionAndMonotonicSequence() throws Exception {
        sync.onApplicationEvent(new PlayerStateEvent(this, state(15000)));
        ObjectNode frame = lastPatch();
        assertEquals(0, frame.path("base").asLong());
        assertEquals(1, frame.path("seq").asLong());
        assertFalse(frame.path("full").asBoolean());
        assertEquals(1, frame.path("state").size());
        assertEquals(15000, frame.at("/state/nowPlaying/currentPosition").asLong());
        assertFalse(frame.path("state").has("queue"));
        assertFalse(frame.at("/state/nowPlaying").has("music"));
        assertTrue(mapper.writeValueAsBytes(frame).length < 200);
        sync.onApplicationEvent(new PlayerStateEvent(this, state(20000)));
        assertEquals(1, lastPatch().path("base").asLong());
        assertEquals(2, sync.snapshot().path("seq").asLong());
    }

    @Test void unchangedStateStillProducesTinyTransportHeartbeat() throws Exception {
        sync.onApplicationEvent(new PlayerStateEvent(this, state(10000)));
        assertTrue(lastPatch().path("state").isEmpty());
        assertEquals(1, lastPatch().path("seq").asInt());
    }

    @Test void nullExplicitlyClearsCurrentTrack() throws Exception {
        ObjectNode state = mapper.valueToTree(state(10000));
        state.putNull("nowPlaying");
        sync.onApplicationEvent(new PlayerStateEvent(this, mapper.treeToValue(state, PlayerState.class)));
        assertTrue(lastPatch().path("state").has("nowPlaying"));
        assertTrue(lastPatch().at("/state/nowPlaying").isNull());
        assertTrue(sync.snapshot().at("/state/nowPlaying").isNull());
    }

    @Test void queueOnlyEventKeepsPlayerMetadataAndDoesNotCallBackIntoPlayer() {
        clearInvocations(player);
        sync.onApplicationEvent(new QueueUpdateEvent(this, List.of()));
        verifyNoInteractions(player);
        assertEquals("101", sync.snapshot().at("/state/nowPlaying/music/id").asText());
        assertTrue(lastPatch().path("state").isEmpty());
    }

    @Test void snapshotsAreDetachedAndHaveStableServerEpoch() {
        ObjectNode first = sync.snapshot();
        first.withObject("/state").put("isPaused", false);
        ObjectNode second = sync.snapshot();
        assertTrue(second.at("/state/isPaused").asBoolean());
        assertEquals(first.path("epoch"), second.path("epoch"));
        assertTrue(second.path("full").asBoolean());
        assertTrue(second.at("/state/queue").isArray());
    }

    @Test void memberChangeReadsFreshStateAndPublishesOnlyDelta() throws Exception {
        when(player.getCurrentPlayerState()).thenReturn(state(25000));
        sync.onApplicationEvent(new UserCountChangeEvent(this, 2));
        assertEquals(25000, lastPatch().at("/state/nowPlaying/currentPosition").asLong());
        assertFalse(lastPatch().path("full").asBoolean());
    }

    @Test void resyncIsPrivateToRequestingSession() {
        clearInvocations(player);
        CompactSyncController controller = new CompactSyncController(sync, messages);
        controller.resync("requesting-session");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(messages).convertAndSendToUser(eq("requesting-session"), eq("/queue/player/compact"), payload.capture(), headers.capture());
        assertTrue(((ObjectNode) payload.getValue()).path("full").asBoolean());
        assertEquals("requesting-session", SimpMessageHeaderAccessor.getSessionId(headers.getValue()));
        verify(messages, never()).convertAndSend(eq("/topic/player/state"), any(Object.class));
        verifyNoInteractions(player);
    }
}
