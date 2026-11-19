import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { createPinia, setActivePinia } from 'pinia';
import { usePlayerStore } from '../../src/stores/player';
import { socketService } from '../../src/services/socket';
import { WS_DEST } from '../../src/constants/api';

vi.mock('../../src/services/socket', () => ({ socketService: { connected: true, send: vi.fn(), connect: vi.fn() } }));
vi.mock('../../src/services/socketHandler', () => ({
    createSocketSubscriptions: () => ({}),
    createSocketCallbacks: () => ({}),
}));
vi.mock('../../src/stores/user', () => ({ useUserStore: () => ({ isGuest: false, setOnlineUsers: vi.fn() }) }));
vi.mock('../../src/api/music', () => ({ musicApi: { getLyric: vi.fn().mockResolvedValue('') } }));

const state = (playbackId = 'first', extras = {}) => ({
    playbackId,
    seekNonce: 0,
    nowPlaying: { music: { id: 'a', platform: 'netease', url: 'a.mp3', duration: 180000 }, currentPosition: 0 },
    queue: [],
    isPaused: false,
    config: { allowSeek: true },
    ...extras,
});

describe('instance-bound seek store', () => {
    let player, subscriptions;
    const currentRequest = () =>
        socketService.send.mock.calls.filter(([dest]) => dest === WS_DEST.PLAYER_SEEK).at(-1)[1];
    const acknowledge = (request, seekNonce, accepted = true) =>
        subscriptions[WS_DEST.USER_SEEK]({ ...request, seekNonce, accepted });
    const at = (position, seekNonce = 0, extras = {}) =>
        state('first', {
            seekNonce,
            nowPlaying: { ...state().nowPlaying, currentPosition: position },
            ...extras,
        });
    beforeEach(() => {
        vi.useFakeTimers();
        vi.stubGlobal('localStorage', { getItem: vi.fn() });
        setActivePinia(createPinia());
        player = usePlayerStore();
        player.connected = true;
        socketService.connected = true;
        socketService.send.mockClear();
        player.syncState(state());
        player.connect();
        subscriptions = socketService.connect.mock.calls.at(-1)[2];
    });
    afterEach(() => {
        player.$dispose();
        vi.useRealTimers();
        vi.unstubAllGlobals();
    });

    it('sends the playback instance with the target position', () => {
        player.seekTo(12345.6, 'first');
        expect(socketService.send).toHaveBeenCalledWith(WS_DEST.PLAYER_SEEK, {
            positionMs: 12346,
            playbackId: 'first',
            requestId: expect.any(String),
        });
    });

    it('rejects a drag from an earlier pass of the same song before consuming cooldown', () => {
        player.syncState(state('second'));
        player.seekTo(80000, 'first');
        expect(socketService.send).not.toHaveBeenCalled();
        player.seekTo(20000, 'second');
        expect(socketService.send).toHaveBeenCalledWith(WS_DEST.PLAYER_SEEK, {
            positionMs: 20000,
            playbackId: 'second',
            requestId: expect.any(String),
        });
    });

    it.each([
        ['missing playback identity', state(null)],
        ['cleared player', state(null, { nowPlaying: null })],
        ['loading', state('first', { isLoading: true })],
        ['permission disabled', state('first', { config: { allowSeek: false } })],
    ])('does not send for %s', (_name, next) => {
        player.syncState(next);
        player.seekTo(20000);
        expect(socketService.send).not.toHaveBeenCalled();
    });

    it('holds optimistic progress through delayed states without moving the room clock or media', () => {
        player.localProgress = 1000;
        player.seekTo(80000);
        expect(player.displayProgress).toBe(80000);
        expect(player.localProgress).toBe(1000);
        expect(player.getCurrentProgress()).toBeLessThan(1000);
        player.syncState(at(3000));
        player.localProgress = 3000;
        expect(player.displayProgress).toBe(80000);
    });

    it.each(['ack first', 'state first'])('confirms only the matching response plus real state: %s', async (order) => {
        player.seekTo(180000);
        const request = currentRequest();
        expect(player.displayProgress).toBe(179500);
        if (order === 'ack first') acknowledge(request, 1);
        else player.syncState(at(179500, 1));
        expect(player.seekPreview.confirmed).toBe(false);
        if (order === 'ack first') player.syncState(at(179500, 1));
        else acknowledge(request, 1);
        expect(player.seekPreview.confirmed).toBe(true);
        expect(player.displayProgress).toBeGreaterThanOrEqual(179500);
        player.localProgress = 179500;
        await nextTick();
        expect(player.seekPreview).toBeNull();
    });

    it('does not mistake another member seek for confirmation and accepts newer room intent after acknowledgment', () => {
        player.seekTo(80000);
        const request = currentRequest();
        player.syncState(at(20000, 1));
        expect(player.displayProgress).toBe(80000);
        expect(player.seekPreview.confirmed).toBe(false);
        acknowledge(request, 2);
        expect(player.seekPreview.confirmed).toBe(false);
        player.syncState(at(30000, 3));
        expect(player.seekPreview.confirmed).toBe(true);
        expect(player.displayProgress).toBeGreaterThanOrEqual(30000);
        expect(player.displayProgress).toBeLessThan(31000);
        player.syncState(at(80000, 2));
        expect(player.displayProgress).toBeLessThan(31000);
    });

    it('keeps the newest pending intent when old responses and states arrive', () => {
        player.seekTo(80000);
        const older = currentRequest();
        vi.advanceTimersByTime(600);
        player.seekTo(40000);
        const newer = currentRequest();
        acknowledge(older, 1);
        player.syncState(at(80000, 1));
        acknowledge(older, 1, false);
        expect(player.displayProgress).toBe(40000);
        expect(player.seekPreview.requestId).toBe(newer.requestId);
        acknowledge(newer, 2);
        expect(player.seekPreview.confirmed).toBe(false);
        player.syncState(at(40000, 2));
        expect(player.seekPreview.confirmed).toBe(true);
    });

    it('rolls back rejected and timed out requests and ignores their late replies', () => {
        player.localProgress = 2000;
        player.seekTo(80000);
        const rejected = currentRequest();
        acknowledge(rejected, 0, false);
        expect(player.displayProgress).toBe(2000);
        expect(player.seekError).toContain('未生效');
        vi.advanceTimersByTime(600);
        player.seekTo(40000);
        const expired = currentRequest();
        vi.advanceTimersByTime(8000);
        expect(player.seekPreview).toBeNull();
        expect(player.displayProgress).toBe(2000);
        expect(player.seekError).toContain('超时');
        expect(socketService.send).toHaveBeenCalledWith(WS_DEST.COMPACT_RESYNC);
        acknowledge(expired, 1);
        expect(player.seekPreview).toBeNull();
    });

    it.each(['track', 'permission', 'loading', 'disconnect'])('clears pending feedback on %s changes', (change) => {
        player.seekTo(80000);
        const request = currentRequest();
        if (change === 'track') player.syncState(state('second'));
        if (change === 'permission') player.syncState(at(1000, 0, { config: { allowSeek: false } }));
        if (change === 'loading') player.syncState(at(1000, 0, { isLoading: true }));
        if (change === 'disconnect') player.connected = false;
        expect(player.seekPreview).toBeNull();
        acknowledge(request, 1);
        expect(player.seekPreview).toBeNull();
    });

    it('retains feedback on cooldown rejection and clears feedback on send failure', () => {
        player.seekTo(80000);
        player.seekTo(40000);
        expect(player.displayProgress).toBe(80000);
        vi.advanceTimersByTime(600);
        socketService.send.mockImplementationOnce(() => {
            throw new Error('offline');
        });
        player.seekTo(40000);
        expect(player.seekPreview).toBeNull();
        expect(player.seekError).toContain('发送失败');
    });

    it('does not send while either connection indicator is disconnected', () => {
        player.connected = false;
        player.seekTo(20000);
        player.connected = true;
        socketService.connected = false;
        player.seekTo(20000);
        expect(socketService.send).not.toHaveBeenCalled();
    });
});
