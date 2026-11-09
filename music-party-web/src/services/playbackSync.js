// Playback uses a monotonic local clock. A delayed state packet is not a seek command.
export function createRoomClock(now = () => performance.now()) {
    let key = '', paused = false, duration = 0;
    let anchor = 0, anchorAt = 0, lastRaw = -1;
    let sampleAt = -Infinity, sampleId = 0, revision = 0, reason = 'track';
    let reconnect = false;

    const position = (at = now()) => key ? Math.max(0, anchor + (paused ? 0 : at - anchorAt)) : 0;
    const sample = (state) => {
        const at = now();
        const music = state.nowPlaying?.music;
        const nextKey = music ? `${music.platform}:${music.id}:${music.url}` : '';
        const raw = Number(state.nowPlaying?.currentPosition);
        if (!nextKey) {
            if (key) revision++;
            key = ''; lastRaw = -1; sampleAt = -Infinity;
            return;
        }
        if (!Number.isFinite(raw) || raw < 0) return;
        duration = Number(music.duration) || 0;
        if (duration > 0 && raw > duration + 2000) return;
        const nextPaused = !!state.isPaused;
        const changedTrack = nextKey !== key;
        const transport = !changedTrack && nextPaused !== paused;
        // Same URL/id can start another pass (repeat-one or a one-track retained playlist).
        const repeat = !changedTrack && !transport && !nextPaused && duration > 0
            && position(at) >= duration - 1000 && lastRaw >= duration - 10000
            && raw < Math.min(10000, duration * 0.2) && raw + 1000 < lastRaw;

        if (changedTrack || transport || repeat || reconnect) {
            anchor = raw; anchorAt = at; lastRaw = raw;
            sampleAt = at; sampleId++;
            if (changedTrack || transport || repeat) {
                revision++;
                reason = changedTrack ? 'track' : repeat ? 'repeat' : 'transport';
            }
            key = nextKey; paused = nextPaused; reconnect = false;
            return;
        }
        if (paused) {
            // A paused room still sends valid heartbeats, even though its position is unchanged.
            if (at - sampleAt >= 1000) { sampleAt = at; sampleId++; }
            return;
        }
        // A packet with an older position, or a burst of duplicate frames, cannot confirm drift.
        if (raw <= lastRaw || at - sampleAt < 1000) return;
        lastRaw = raw; sampleAt = at; sampleId++;
        // Faster delivery may improve our estimate; slower delivery never rewinds an ongoing clock.
        const predicted = position(at);
        anchor = Math.max(predicted, raw);
        anchorAt = at;
    };
    return {
        sample, position,
        expectReconnect: () => { reconnect = true; },
        status: () => ({ revision, reason, sampleId, sampleAt, fresh: !!key && now() - sampleAt < 15000 })
    };
}

// Small errors are tolerated or slewed. A hard seek needs independent, stable state samples.
export function createMediaSynchronizer() {
    let initial = true, appliedRevision = -1;
    let stableSince = null, lastHardSeek = -Infinity;
    let candidate = null, slewing = false;
    const resetCandidate = () => { candidate = null; slewing = false; };
    const resetMedia = () => { initial = true; stableSince = null; resetCandidate(); };
    const tick = (input) => {
        const { now, mediaTime, targetTime, revision, sampleId, sampleAt } = input;
        const delta = targetTime - mediaTime;
        const idle = () => ({ rate: 1 });
        if (!input.ready || input.seeking || input.blocked || !Number.isFinite(delta)) {
            stableSince = null; resetCandidate(); return idle();
        }
        if (input.connected && input.fresh && !input.buffering && (initial || revision !== appliedRevision)) {
            initial = false; appliedRevision = revision;
            stableSince = now; resetCandidate();
            if (Math.abs(delta) > 0.35) {
                lastHardSeek = now;
                return { rate: 1, seek: targetTime };
            }
            return idle();
        }
        if (input.paused || input.ended || input.buffering || !input.connected || !input.fresh) {
            stableSince = null; resetCandidate(); return idle();
        }
        if (stableSince === null) stableSince = now;
        // Let media settle after a stall/reconnect; canplay/seeked are not fresh room samples.
        if (now - stableSince < 5000) { resetCandidate(); return idle(); }
        const magnitude = Math.abs(delta);
        if (magnitude >= 8) {
            const sign = Math.sign(delta);
            if (!candidate || candidate.sign !== sign || Math.abs(delta - candidate.delta) > 2
                || sampleAt - candidate.lastAt > 12000) {
                candidate = { sign, delta, firstAt: sampleAt, lastAt: sampleAt, lastId: sampleId, count: 1 };
            } else if (sampleId !== candidate.lastId) {
                candidate.count++;
                candidate.lastId = sampleId;
                candidate.lastAt = sampleAt;
                candidate.delta = delta;
            }
            if (candidate.count >= 3 && candidate.lastAt - candidate.firstAt >= 8000
                && now - lastHardSeek >= 30000 && input.targetBuffered) {
                lastHardSeek = now; stableSince = now; resetCandidate();
                return { rate: 1, seek: targetTime };
            }
        } else candidate = null;
        if (magnitude <= 0.5 || (!slewing && magnitude <= 1.5)) {
            slewing = false;
            return idle();
        }
        slewing = true;
        // Do not consume a thin buffer faster while the network is struggling.
        if (delta > 0 && input.bufferAhead < 3) return idle();
        const rate = 1 + Math.max(-0.02, Math.min(0.02, delta / 120));
        return { rate: Math.round(rate * 1000) / 1000 };
    };
    return { tick, resetMedia };
}
