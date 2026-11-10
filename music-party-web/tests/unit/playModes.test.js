import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePlayerStore } from '../../src/stores/player';
import { socketService } from '../../src/services/socket';
import { WS_DEST } from '../../src/constants/api';
import { PLAY_MODES } from '../../src/domain/playModes';
import { canReorderItem, isValidReorder } from '../../src/domain/queueRules';

const { user } = vi.hoisted(() => ({ user: { userToken: 'mine', isGuest: false, showNameModal: false } }));
vi.mock('../../src/stores/user', () => ({ useUserStore: () => user }));
vi.mock('../../src/services/socket', () => ({ socketService: { connected: true, send: vi.fn() } }));
vi.mock('../../src/services/socketHandler', () => ({
    createSocketSubscriptions: vi.fn(),
    createSocketCallbacks: vi.fn(),
}));
vi.mock('../../src/api/music', () => ({ musicApi: { getLyric: vi.fn() } }));

const item = (queueId, token = 'mine', priority = 'REGULAR', platform = 'netease') => ({
    queueId,
    enqueuedBy: { token },
    priority,
    music: { platform },
});
const queue = () => [
    item('a'),
    item('other', 'other'),
    item('pinned', 'mine', 'USER_TOP'),
    item('fm', 'mine', 'REGULAR', 'netease-fm'),
    item('b'),
    item('c'),
];

describe('explicit playback controls', () => {
    let player;
    beforeEach(() => {
        setActivePinia(createPinia());
        player = usePlayerStore();
        player.connected = true;
        player.queue = queue();
        user.isGuest = false;
        user.showNameModal = false;
        socketService.connected = true;
        socketService.send.mockClear();
    });
    it.each(PLAY_MODES)('sends $value directly without optimistically changing state', ({ value }) => {
        player.setPlayMode(value);
        expect(socketService.send).toHaveBeenCalledExactlyOnceWith(WS_DEST.PLAYER_MODE, { playMode: value });
        expect(player.playMode).toBe('SEQUENTIAL');
    });
    it('rejects unknown modes and a mode lock', () => {
        player.setPlayMode('INVALID');
        player.isPlayModeLocked = true;
        player.setPlayMode('ROUND_ROBIN');
        expect(socketService.send).not.toHaveBeenCalled();
    });
    it.each(['guest', 'disconnected store', 'disconnected socket'])('guards all new actions for %s', (guard) => {
        player.playMode = 'ROUND_ROBIN';
        if (guard === 'guest') user.isGuest = true;
        if (guard === 'disconnected store') player.connected = false;
        if (guard === 'disconnected socket') socketService.connected = false;
        player.setPlayMode('SHUFFLE');
        player.setMemberShuffle(false);
        player.reorderSong('c', 'a');
        expect(socketService.send).not.toHaveBeenCalled();
    });
    it('synchronizes the top-level preferences and sends exact boolean values', () => {
        player.syncState({
            nowPlaying: null,
            queue: queue(),
            playMode: 'ROUND_ROBIN',
            memberShufflePreferences: { mine: false, other: true },
        });
        expect(player.isRoundRobin).toBe(true);
        expect(player.memberShufflePreferences).toEqual({ mine: false, other: true });
        player.setMemberShuffle(true);
        player.setMemberShuffle(false);
        player.setMemberShuffle('false');
        expect(socketService.send.mock.calls).toEqual([
            [WS_DEST.MEMBER_SHUFFLE, { shuffle: true }],
            [WS_DEST.MEMBER_SHUFFLE, { shuffle: false }],
        ]);
        expect(player.memberShufflePreferences.mine).toBe(false);
        player.syncState({ nowPlaying: null, queue: [], playMode: 'SHUFFLE' });
        expect(player.memberShufflePreferences).toEqual({});
        player.setMemberShuffle(false);
        expect(socketService.send).toHaveBeenCalledTimes(2);
    });
    it('sends own reorder targets or null without changing any queue entry', () => {
        const snapshot = structuredClone(queue());
        player.reorderSong('c', 'a');
        player.reorderSong('a', null);
        expect(socketService.send.mock.calls).toEqual([
            [WS_DEST.QUEUE_REORDER, { queueId: 'c', beforeQueueId: 'a' }],
            [WS_DEST.QUEUE_REORDER, { queueId: 'a', beforeQueueId: null }],
        ]);
        expect(player.queue).toEqual(snapshot);
    });
    it.each([
        ['other', 'a'],
        ['pinned', 'a'],
        ['fm', 'a'],
        ['a', 'other'],
        ['a', 'pinned'],
        ['a', 'fm'],
        ['a', undefined],
        ['a', 'a'],
        ['a', 'b'],
        ['c', null],
    ])('rejects invalid/no-op reorder %s before %s', (from, before) => {
        player.reorderSong(from, before);
        expect(socketService.send).not.toHaveBeenCalled();
    });
    it('checks the exact REGULAR ownership invariant for both endpoints', () => {
        expect(canReorderItem(item('a'), 'mine')).toBe(true);
        expect(canReorderItem(item('a', 'mine', 'GLOBAL_TOP'), 'mine')).toBe(false);
        expect(isValidReorder(queue(), 'mine', 'c', 'a')).toBe(true);
        expect(isValidReorder(queue(), 'other', 'c', 'a')).toBe(false);
    });
});
