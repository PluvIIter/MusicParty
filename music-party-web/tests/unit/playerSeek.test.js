import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePlayerStore } from '../../src/stores/player';
import { socketService } from '../../src/services/socket';
import { WS_DEST } from '../../src/constants/api';

vi.mock('../../src/services/socket', () => ({ socketService: { connected: true, send: vi.fn() } }));
vi.mock('../../src/services/socketHandler', () => ({
    createSocketSubscriptions: vi.fn(),
    createSocketCallbacks: vi.fn(),
}));
vi.mock('../../src/stores/user', () => ({ useUserStore: () => ({ isGuest: false, setOnlineUsers: vi.fn() }) }));
vi.mock('../../src/api/music', () => ({ musicApi: { getLyric: vi.fn().mockResolvedValue('') } }));

const state = (playbackId = 'first', extras = {}) => ({
    playbackId,
    nowPlaying: { music: { id: 'a', platform: 'netease', url: 'a.mp3', duration: 180000 }, currentPosition: 0 },
    queue: [],
    isPaused: false,
    config: { allowSeek: true },
    ...extras,
});

describe('instance-bound seek store', () => {
    let player;
    beforeEach(() => {
        setActivePinia(createPinia());
        player = usePlayerStore();
        player.connected = true;
        socketService.connected = true;
        socketService.send.mockClear();
        player.syncState(state());
    });

    it('sends the playback instance with the target position', () => {
        player.seekTo(12345.6, 'first');
        expect(socketService.send).toHaveBeenCalledWith(WS_DEST.PLAYER_SEEK, {
            positionMs: 12346,
            playbackId: 'first',
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

    it('does not send while either connection indicator is disconnected', () => {
        player.connected = false;
        player.seekTo(20000);
        player.connected = true;
        socketService.connected = false;
        player.seekTo(20000);
        expect(socketService.send).not.toHaveBeenCalled();
    });
});
