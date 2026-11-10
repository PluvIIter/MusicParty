import { beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive, ref } from 'vue';
import { usePlaylistLogic } from '../../src/composables/usePlaylistLogic';

const mocks = vi.hoisted(() => ({ user: null, fetch: vi.fn(), error: vi.fn() }));
vi.mock('../../src/stores/user.js', () => ({ useUserStore: () => mocks.user }));
vi.mock('../../src/stores/player.js', () => ({ usePlayerStore: () => ({}) }));
vi.mock('../../src/api/music.js', () => ({ musicApi: { getUserPlaylists: mocks.fetch } }));
vi.mock('../../src/composables/useToast', () => ({ useToast: () => ({ error: mocks.error }) }));

const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => {
        resolve = yes;
        reject = no;
    });
    return { promise, resolve, reject };
};

beforeEach(() => {
    mocks.user = reactive({ bindings: { netease: 'N', bilibili: 'B' } });
    mocks.fetch.mockReset();
    mocks.error.mockReset();
});

describe('playlist request ownership', () => {
    const setup = () => {
        const requests = [];
        mocks.fetch.mockImplementation((platform, uid) => {
            const request = deferred();
            requests.push({ platform, uid, ...request });
            return request.promise;
        });
        const platform = ref('netease');
        const scope = effectScope();
        const state = scope.run(() => usePlaylistLogic(platform, ref([]), ref('search'), ref(false)));
        return { platform, requests, state, scope };
    };

    it('stores late responses under their original platform and reuses the correct cache', async () => {
        const { platform, requests, state, scope } = setup();
        try {
            platform.value = 'bilibili';
            await nextTick();
            requests[1].resolve([{ id: 'B-list' }]);
            await nextTick();
            requests[0].resolve([{ id: 'N-list' }]);
            await nextTick();
            expect(state.playlists.value).toEqual([{ id: 'B-list' }]);
            platform.value = 'netease';
            await nextTick();
            expect(state.playlists.value).toEqual([{ id: 'N-list' }]);
            platform.value = 'bilibili';
            await nextTick();
            expect(state.playlists.value).toEqual([{ id: 'B-list' }]);
            expect(mocks.fetch).toHaveBeenCalledTimes(2);
        } finally {
            scope.stop();
        }
    });

    it('watches binding mutations and ignores the previous account response', async () => {
        const { requests, state, scope } = setup();
        try {
            mocks.user.bindings.netease = 'replacement';
            await nextTick();
            expect(requests[1].uid).toBe('replacement');
            requests[0].resolve([{ id: 'old-account' }]);
            await nextTick();
            expect(state.playlists.value).toEqual([]);
            expect(state.isPlaylistsLoading.value).toBe(true);
            requests[1].resolve([{ id: 'new-account' }]);
            await nextTick();
            expect(state.playlists.value).toEqual([{ id: 'new-account' }]);
            expect(state.isPlaylistsLoading.value).toBe(false);
            mocks.user.bindings.netease = '';
            await nextTick();
            expect(state.playlists.value).toEqual([]);
        } finally {
            scope.stop();
        }
    });

    it('keeps a newer refresh when requests for the same key finish out of order', async () => {
        const { requests, state, scope } = setup();
        try {
            const refreshed = state.fetchPlaylists();
            requests[1].resolve([{ id: 'fresh' }]);
            await refreshed;
            requests[0].resolve([{ id: 'stale' }]);
            await nextTick();
            expect(state.playlists.value).toEqual([{ id: 'fresh' }]);
        } finally {
            scope.stop();
        }
    });

    it('does not clear another platform or its loading flag on stale failures', async () => {
        const { platform, requests, state, scope } = setup();
        try {
            platform.value = 'bilibili';
            await nextTick();
            requests[0].reject({ response: { data: { message: 'old request failed' } } });
            await nextTick();
            expect(state.isPlaylistsLoading.value).toBe(true);
            expect(mocks.error).not.toHaveBeenCalled();
            requests[1].resolve([{ id: 'B-list' }]);
            await nextTick();
            expect(state.playlists.value).toEqual([{ id: 'B-list' }]);
        } finally {
            scope.stop();
        }
    });
});
