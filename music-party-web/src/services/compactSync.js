// Arrays replace only on changes; object patches preserve all omitted fields and explicit nulls.
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
export function mergeState(previous, patch) {
    const next = { ...previous };
    for (const key of Object.keys(patch)) {
        if (key === '__proto__' || key === 'constructor' || key === 'prototype') continue;
        next[key] = object(patch[key]) && object(previous?.[key])
            ? mergeState(previous[key], patch[key]) : patch[key];
    }
    return next;
}

/** A missing/reordered delta never becomes an authoritative partial player state. */
export function createCompactReceiver({ apply, requestSnapshot, activated, now = Date.now }) {
    let state = null, epoch = null, sequence = -1, active = false;
    let buffered = [], lastRequest = -Infinity;
    function reset() {
        state = null; epoch = null; sequence = -1; active = false;
        buffered = []; lastRequest = -Infinity;
    }
    function recover() {
        if (now() - lastRequest < 2000) return;
        lastRequest = now();
        requestSnapshot();
    }
    function valid(frame) {
        return frame?.v === 1 && typeof frame.epoch === 'string' && frame.epoch.length <= 80
            && Number.isSafeInteger(frame.seq) && frame.seq >= 0
            && Number.isSafeInteger(frame.base) && frame.base >= 0
            && typeof frame.full === 'boolean' && object(frame.state);
    }
    function receive(frame) {
        if (!valid(frame)) return false;
        if (frame.full) {
            if (!Array.isArray(frame.state.queue) || typeof frame.state.isPaused !== 'boolean'
                || !Object.hasOwn(frame.state, 'nowPlaying')) return false;
            if (frame.epoch === epoch && frame.seq < sequence) return true;
            epoch = frame.epoch; sequence = frame.seq; state = frame.state;
            if (!active) { active = true; activated?.(); }
            lastRequest = -Infinity;
        } else {
            if (frame.epoch === epoch && frame.seq <= sequence) return true;
            if (!state || frame.epoch !== epoch || frame.base !== sequence || frame.seq !== frame.base + 1) {
                buffered.push(frame);
                if (buffered.length > 64) buffered = buffered.slice(-64);
                recover();
                return true;
            }
            state = mergeState(state, frame.state); sequence = frame.seq;
        }
        // A private snapshot can race newer broadcast deltas on the outbound executor.
        buffered = buffered.filter(item => item.epoch === epoch && item.seq > sequence).sort((a, b) => a.seq - b.seq);
        while (buffered.length && buffered[0].base === sequence && buffered[0].seq === sequence + 1) {
            const item = buffered.shift();
            state = mergeState(state, item.state); sequence = item.seq;
            buffered = buffered.filter(item => item.seq > sequence);
        }
        apply(state);
        if (buffered.length) recover();
        return true;
    }
    return { receive, reset, isActive: () => active };
}
