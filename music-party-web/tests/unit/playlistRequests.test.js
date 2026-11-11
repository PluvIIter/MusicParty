import { beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive, ref } from 'vue';
import { usePlaylistLogic } from '../../src/composables/usePlaylistLogic';

const mocks = vi.hoisted(() => ({ user: null, collections: vi.fn(), tracks: vi.fn(), users: vi.fn(), error: vi.fn() }));
vi.mock('../../src/stores/user.js', () => ({ useUserStore: () => mocks.user }));
vi.mock('../../src/stores/sources.js', () => ({
    useSourcesStore: () => ({
        descriptor: () => ({
            collectionCategories: [
                { id: 'lists', label: 'Lists', entryKind: 'CONTAINER', collectionType: 'playlist' },
                { id: 'tracks', label: 'Tracks', entryKind: 'TRACK', collectionType: 'track' },
            ],
        }),
        supports: () => true,
    }),
}));
vi.mock('../../src/api/music.js', () => ({
    musicApi: { getCollections: mocks.collections, getContainerTracks: mocks.tracks, searchUser: mocks.users },
}));
vi.mock('../../src/composables/useToast', () => ({ useToast: () => ({ error: mocks.error }) }));
const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => {
        resolve = yes;
        reject = no;
    });
    return { promise, resolve, reject };
};
const flush = async () => {
    await nextTick();
    await nextTick();
};
const page = (id, nextCursor = null) => ({ items: [{ id }], nextCursor });
let scope;
beforeEach(() => {
    scope?.stop();
    mocks.user = reactive({ bindings: { unknown: 'U', another: 'A' } });
    mocks.user.updateBinding = (source, uid) => {
        mocks.user.bindings[source] = uid;
    };
    Object.values(mocks).forEach((value) => value?.mockReset?.());
});
function setup() {
    const requests = [];
    for (const [kind, fn] of Object.entries({
        collections: mocks.collections,
        tracks: mocks.tracks,
        users: mocks.users,
    })) {
        fn.mockImplementation((...args) => {
            const request = deferred();
            requests.push({ kind, args, ...request });
            return request.promise;
        });
    }
    const platform = ref('unknown'),
        songs = ref([]),
        mode = ref('search'),
        loading = ref(false);
    scope = effectScope();
    const state = scope.run(() => usePlaylistLogic(platform, songs, mode, loading));
    return { platform, songs, mode, loading, state, requests };
}
describe('public collection request ownership', () => {
    it('passes the explicit public target and ignores late source responses and errors', async () => {
        const { platform, state, requests } = setup();
        expect(requests[0].args.slice(0, 3)).toEqual(['unknown', 'lists', 'U']);
        platform.value = 'another';
        await flush();
        const current = requests.at(-1);
        requests[0].reject(new Error('old failure'));
        await flush();
        expect(state.isPlaylistsLoading.value).toBe(true);
        expect(mocks.error).not.toHaveBeenCalled();
        current.resolve(page('current'));
        await flush();
        expect(state.playlists.value).toEqual([{ id: 'current' }]);
        expect(state.isPlaylistsLoading.value).toBe(false);
    });
    it('invalidates rebinding and unlinking even if transport ignores AbortSignal', async () => {
        const { state, requests } = setup();
        mocks.user.bindings.unknown = 'new-user';
        const latest = requests.at(-1);
        expect(latest.args[2]).toBe('new-user');
        requests[0].resolve(page('old'));
        await flush();
        expect(state.playlists.value).toEqual([]);
        latest.resolve(page('new'));
        await flush();
        expect(state.playlists.value).toEqual([{ id: 'new' }]);
        mocks.user.bindings.unknown = '';
        expect(state.playlists.value).toEqual([]);
    });
    it('keeps newer refresh and category results when requests complete in reverse', async () => {
        const { state, songs, requests } = setup();
        const refreshed = state.fetchPlaylists();
        requests.at(-1).resolve(page('fresh'));
        await refreshed;
        requests[0].resolve(page('stale'));
        await flush();
        expect(state.playlists.value).toEqual([{ id: 'fresh' }]);
        state.categoryId.value = 'tracks';
        requests.at(-1).resolve(page('song'));
        await flush();
        expect(songs.value).toEqual([{ id: 'song' }]);
    });
    it('isolates container pagination, search transitions and unmount', async () => {
        const { state, songs, mode, loading, requests } = setup();
        state.loadPlaylist({ id: 'old', collectionType: 'playlist' });
        const old = requests.at(-1);
        state.loadPlaylist({ id: 'new', collectionType: 'album' });
        const current = requests.at(-1);
        old.resolve(page('old-song'));
        await flush();
        expect(loading.value).toBe(true);
        current.resolve(page('new-song', 'cursor'));
        await flush();
        expect(songs.value).toEqual([{ id: 'new-song' }]);
        state.handleScroll({ target: { scrollHeight: 100, scrollTop: 100, clientHeight: 100 } });
        const more = requests.at(-1);
        expect(more.args.slice(0, 4)).toEqual(['unknown', 'album', 'new', 'cursor']);
        mode.value = 'search';
        songs.value = [{ id: 'search-result' }];
        more.resolve(page('late'));
        await flush();
        expect(songs.value).toEqual([{ id: 'search-result' }]);
        state.searchUserKeyword.value = 'person';
        state.searchUser();
        const users = requests.at(-1);
        scope.stop();
        users.resolve(page('late-user'));
        await flush();
        expect(state.userSearchResults.value).toEqual([]);
    });
});
