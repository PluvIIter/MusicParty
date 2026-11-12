package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.QueueUpdateEvent;
import org.thornex.musicparty.event.UserCountChangeEvent;

import java.util.UUID;

/** Versioned, loss-detecting JSON object patches. */
@Service
public class CompactSyncService implements ApplicationListener<ApplicationEvent> {
    private final MusicPlayerService player;
    private final ObjectMapper mapper;
    private final SimpMessagingTemplate messaging;
    private final String epoch = UUID.randomUUID().toString();
    private ObjectNode current;
    private long sequence;
    private long positionSampleNanos;

    public CompactSyncService(MusicPlayerService player, ObjectMapper mapper, SimpMessagingTemplate messaging) {
        this.player = player;
        this.mapper = mapper;
        this.messaging = messaging;
        current = mapper.valueToTree(player.getCurrentPlayerState());
        positionSampleNanos = System.nanoTime();
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        // Capture outside this service's monitor: player/queue code also publishes while locked.
        if (event instanceof PlayerStateEvent changed) {
            publish(mapper.valueToTree(changed.getState()), true);
        } else if (event instanceof QueueUpdateEvent changed) {
            ObjectNode update = mapper.createObjectNode();
            update.set("queue", mapper.valueToTree(changed.getQueue()));
            publish(update, false);
        } else if (event instanceof UserCountChangeEvent) {
            publish(mapper.valueToTree(player.getCurrentPlayerState()), true);
        }
    }

    private synchronized void publish(ObjectNode state, boolean complete) {
        ObjectNode next = complete ? state : current.deepCopy();
        if (!complete) state.fields().forEachRemaining(e -> next.set(e.getKey(), e.getValue()));
        ObjectNode patch = difference(current, next);
        if (complete) positionSampleNanos = System.nanoTime();
        long base = sequence++;
        current = next;
        // Even an empty patch is a cheap heartbeat for transport health and sequence checking.
        messaging.convertAndSend("/topic/player/compact", envelope(false, base, patch));
    }

    /** Snapshot and its sequence are captured atomically, without taking any player locks. */
    public synchronized ObjectNode snapshot() {
        ObjectNode snapshot = current.deepCopy();
        JsonNode playing = snapshot.get("nowPlaying");
        if (playing instanceof ObjectNode info && !snapshot.path("isPaused").asBoolean()
                && !snapshot.path("isLoading").asBoolean()) {
            long position = info.path("currentPosition").asLong()
                    + Math.max(0, (System.nanoTime() - positionSampleNanos) / 1_000_000);
            long duration = info.path("music").path("duration").asLong();
            info.put("currentPosition", duration > 0 ? Math.min(duration, position) : position);
        }
        return envelope(true, sequence, snapshot);
    }

    private ObjectNode envelope(boolean full, long base, ObjectNode state) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("v", 1);
        frame.put("epoch", epoch);
        frame.put("base", base);
        frame.put("seq", sequence);
        frame.put("full", full);
        frame.set("state", state);
        return frame;
    }

    private ObjectNode difference(ObjectNode previous, ObjectNode next) {
        ObjectNode patch = mapper.createObjectNode();
        next.fields().forEachRemaining(e -> {
            JsonNode old = previous.get(e.getKey()), value = e.getValue();
            if (value.equals(old)) return;
            if (old instanceof ObjectNode oldObject && value instanceof ObjectNode newObject) {
                patch.set(e.getKey(), difference(oldObject, newObject));
            } else {
                // Arrays are sent only when changed; null explicitly clears a field.
                patch.set(e.getKey(), value);
            }
        });
        previous.fieldNames().forEachRemaining(key -> { if (!next.has(key)) patch.putNull(key); });
        return patch;
    }
}
