import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';
import { usePlayerStore } from '../../src/stores/player';
import { useSourcesStore } from '../../src/stores/sources';
import { useLikedSongs } from '../../src/composables/useLikedSongs';
import { socketService } from '../../src/services/socket';
import { STORAGE_KEYS } from '../../src/constants/storage';
const mocks = vi.hoisted(() => ({ lyric: vi.fn(), updateBinding: vi.fn() }));
vi.mock('../../src/services/socket', () => ({ socketService: { connected: true, send: vi.fn() } }));
vi.mock('../../src/services/socketHandler', () => ({
    createSocketSubscriptions: vi.fn(),
    createSocketCallbacks: vi.fn(),
}));
vi.mock('../../src/stores/user', () => ({
    useUserStore: () => ({ isGuest: false, updateBinding: mocks.updateBinding }),
}));
vi.mock('../../src/api/music', () => ({
    musicApi: { getLyric: mocks.lyric, getSources: vi.fn().mockResolvedValue({ sources: [] }) },
}));
const deferred = () => {
    let resolve;
    const promise = new Promise((yes) => {
        resolve = yes;
    });
    return { promise, resolve };
};
beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    const storage = new Map();
    vi.stubGlobal('localStorage', {
        getItem: (key) => storage.get(key) ?? null,
        setItem: (key, value) => storage.set(key, String(value)),
        removeItem: (key) => storage.delete(key),
    });
});
describe('source-local frontend state', () => {
    it('keeps equal track IDs separate and ignores stale lyrics', async () => {
        const requests = [];
        mocks.lyric.mockImplementation(() => {
            const request = deferred();
            requests.push(request);
            return request.promise;
        });
        const player = usePlayerStore();
        player.nowPlaying = { music: { id: 'same', platform: 'one' } };
        await nextTick();
        player.nowPlaying = { music: { id: 'same', platform: 'two' } };
        await nextTick();
        expect(mocks.lyric.mock.calls.map((args) => args.slice(0, 2))).toEqual([
            ['one', 'same'],
            ['two', 'same'],
        ]);
        expect(mocks.lyric.mock.calls[0][2].aborted).toBe(true);
        requests[1].resolve('current lyric');
        await nextTick();
        requests[0].resolve('stale lyric');
        await nextTick();
        expect(player.lyricText).toBe('current lyric');
        player.nowPlaying = null;
        await nextTick();
        expect(player.lyricText).toBe('');
    });
    it('preserves LIKESONG snapshots when a source becomes disabled and validates enqueue', () => {
        const sources = useSourcesStore();
        sources.sources = [{ id: 'future-source', capabilities: {} }];
        const music = {
            id: 'same',
            platform: 'future-source',
            name: 'Saved title',
            artists: ['Artist'],
            duration: 1234,
            externalUrl: 'https://example.com/song',
            coverUrl: 'cover',
            url: 'https://signed.example.com/expired',
        };
        const likes = useLikedSongs();
        likes.addLikedSong(music);
        const stored = localStorage.getItem(STORAGE_KEYS.LIKED_SONGS);
        sources.overrides = { 'future-source': { enabled: false } };
        const player = usePlayerStore();
        player.enqueue('future-source', 'same');
        expect(socketService.send).not.toHaveBeenCalled();
        expect(likes.likedSongs.value).toContainEqual(
            expect.objectContaining({
                id: 'same',
                platform: 'future-source',
                name: 'Saved title',
                duration: 1234,
                externalUrl: music.externalUrl,
            })
        );
        expect(likes.likedSongs.value[0].url).toBeUndefined();
        expect(localStorage.getItem(STORAGE_KEYS.LIKED_SONGS)).toBe(stored);
    });
});
