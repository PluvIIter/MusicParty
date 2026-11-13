// Public identifiers are for display; recovery keys must never be exposed in lists.
export const publicUserId = (user) => String(user?.id || user?.token || '');

export function readIdentity(me) {
    return {
        id: publicUserId(me),
        recoveryKey: String(me?.recoveryKey || me?.token || ''),
        name: String(me?.name || '游客'),
        isGuest: me?.isGuest !== false
    };
}

export function playbackDuration(music) {
    const value = Number(music?.playableDurationMs);
    return Number.isFinite(value) && value > 0 ? value : Math.max(0, Number(music?.duration) || 0);
}
