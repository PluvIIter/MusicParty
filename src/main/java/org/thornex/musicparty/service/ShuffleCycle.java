package org.thornex.musicparty.service;

import org.thornex.musicparty.dto.MusicQueueItem;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Runtime-only shuffle rounds. Access is guarded by MusicQueueManager's monitor. */
final class ShuffleCycle {
    private final Map<String, Round> rounds = new HashMap<>();
    private final Set<String> heard = new HashSet<>();

    private static final class Round {
        final Set<String> played = new HashSet<>();
        String last = "";
    }

    MusicQueueItem choose(List<MusicQueueItem> eligible, boolean perMember) {
        List<MusicQueueItem> fresh = eligible.stream()
                .filter(item -> !MusicQueueManager.FM_MARKER_ID.equals(item.music().platform())
                        && !heard.contains(item.queueId())).toList();
        if (!fresh.isEmpty()) eligible = fresh;
        String scope = perMember ? memberScope(eligible.getFirst()) : "all";
        Round round = rounds.computeIfAbsent(scope, ignored -> new Round());
        List<MusicQueueItem> unplayed = eligible.stream()
                .filter(item -> !round.played.contains(key(item))).toList();
        if (unplayed.isEmpty()) {
            // Only available songs start a new round; unavailable songs keep their history.
            eligible.forEach(item -> round.played.remove(key(item)));
            unplayed = eligible;
        }
        List<MusicQueueItem> withoutLast = unplayed.stream()
                .filter(item -> !key(item).equals(round.last)).toList();
        if (!withoutLast.isEmpty()) unplayed = withoutLast;
        return unplayed.get(ThreadLocalRandom.current().nextInt(unplayed.size()));
    }

    void selected(MusicQueueItem item) {
        for (String scope : List.of("all", memberScope(item))) {
            Round round = rounds.computeIfAbsent(scope, ignored -> new Round());
            round.played.add(key(item));
            round.last = key(item);
        }
    }

    void prune(List<MusicQueueItem> liveQueue) {
        Set<String> ids = new HashSet<>();
        Set<String> scopes = new HashSet<>(Set.of("all"));
        for (MusicQueueItem item : liveQueue) {
            ids.add(key(item));
            scopes.add(memberScope(item));
        }
        heard.retainAll(ids);
        rounds.keySet().retainAll(scopes);
        rounds.values().forEach(round -> round.played.retainAll(ids));
    }

    void heard(MusicQueueItem item) {
        if (!MusicQueueManager.FM_MARKER_ID.equals(item.music().platform())) heard.add(item.queueId());
    }

    Set<String> heardSnapshot() { return Set.copyOf(heard); }

    void restoreHeard(Collection<String> ids) {
        heard.clear();
        if (ids != null) heard.addAll(ids);
    }

    void clear() {
        rounds.clear();
        heard.clear();
    }

    private static String memberScope(MusicQueueItem item) {
        return "member:" + item.enqueuedBy().token();
    }

    private static String key(MusicQueueItem item) {
        // FM is regenerated after consumption; its stable key must still finish the round.
        return MusicQueueManager.FM_MARKER_ID.equals(item.music().platform()) ? "fm-marker" : item.queueId();
    }
}
