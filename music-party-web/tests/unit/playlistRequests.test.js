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
    const active = ref(true);
    const state = scope.run(() => usePlaylistLogic(platform, songs, mode, loading, active));
    return { platform, songs, mode, loading, active, state, requests };
}
async function setupPagination() {
    const context = setup();
    context.requests[0].resolve(page('list'));
    await flush();
    context.state.loadPlaylist({ id: 'container', collectionType: 'playlist' });
    context.requests.at(-1).resolve(page('first', 'next'));
    await flush();
    return context;
}
const scrollToEnd = (state) => state.handleScroll({ target: { scrollHeight: 100, scrollTop: 100, clientHeight: 100 } });
describe('track pagination failure ownership', () => {
    it.each([
        ['AbortError', { name: 'AbortError' }],
        ['ERR_CANCELED', { code: 'ERR_CANCELED' }],
        ['late transport failure', {}],
    ])('resumes the canceled cursor after closing and reopening: %s', async (_, properties) => {
        const { active, state, songs, requests } = await setupPagination();
        state.loadMoreTracks();
        const canceled = requests.at(-1);
        active.value = false;
        expect(canceled.args.at(-1).aborted).toBe(true);
        canceled.reject(Object.assign(new Error('canceled page'), properties));
        await flush();
        expect(mocks.error).not.toHaveBeenCalled();
        expect(state.isLoadingMore.value).toBe(false);
        const count = requests.length;
        active.value = true;
        expect(requests).toHaveLength(count + 1);
        expect(requests.at(-1).args.slice(0, 4)).toEqual(['unknown', 'playlist', 'container', 'next']);
        expect(state.isLoadingMore.value).toBe(true);
        requests.at(-1).resolve(page('second'));
        await flush();
        expect(songs.value).toEqual([{ id: 'first' }, { id: 'second' }]);
        expect(state.isLoadingMore.value).toBe(false);
    });
    it('ignores an old failure while the resumed page is still loading', async () => {
        const { active, state, songs, requests } = await setupPagination();
        state.loadMoreTracks();
        const canceled = requests.at(-1);
        active.value = false;
        active.value = true;
        const resumed = requests.at(-1);
        canceled.reject(new Error('late failure'));
        await flush();
        expect(state.isLoadingMore.value).toBe(true);
        expect(mocks.error).not.toHaveBeenCalled();
        resumed.resolve(page('second', 'third'));
        await flush();
        scrollToEnd(state);
        expect(requests.at(-1).args.slice(0, 4)).toEqual(['unknown', 'playlist', 'container', 'third']);
        requests.at(-1).resolve(page('third'));
        await flush();
        expect(songs.value).toEqual([{ id: 'first' }, { id: 'second' }, { id: 'third' }]);
    });
    it.each([{ name: 'AbortError' }, { code: 'ERR_CANCELED' }])(
        'does not pause scrolling for a current canceled response: %o',
        async (properties) => {
            const { state, songs, requests } = await setupPagination();
            state.loadMoreTracks();
            requests.at(-1).reject(Object.assign(new Error('canceled page'), properties));
            await flush();
            const count = requests.length;
            scrollToEnd(state);
            expect(requests).toHaveLength(count + 1);
            expect(requests.at(-1).args[3]).toBe('next');
            expect(mocks.error).not.toHaveBeenCalled();
            requests.at(-1).resolve(page('second'));
            await flush();
            expect(songs.value).toEqual([{ id: 'first' }, { id: 'second' }]);
        }
    );
    it.each(['source', 'binding', 'category', 'container', 'search'])(
        'does not let an old failure pause a new %s context',
        async (context) => {
            const { platform, state, songs, mode, loading, requests } = await setupPagination();
            state.loadMoreTracks();
            const old = requests.at(-1);
            if (context === 'source') platform.value = 'another';
            if (context === 'binding') mocks.user.bindings.unknown = 'replacement';
            if (context === 'category') state.categoryId.value = 'tracks';
            if (context === 'search') mode.value = 'search';
            state.loadPlaylist({ id: 'new', collectionType: 'album' });
            expect(old.args.at(-1).aborted).toBe(true);
            requests.at(-1).resolve(page('new-first', 'new-next'));
            await flush();
            old.reject(new Error('old context failure'));
            await flush();
            expect(songs.value).toEqual([{ id: 'new-first' }]);
            expect(loading.value).toBe(false);
            expect(state.isLoadingMore.value).toBe(false);
            expect(mocks.error).not.toHaveBeenCalled();
            const count = requests.length;
            scrollToEnd(state);
            expect(requests).toHaveLength(count + 1);
            expect(requests.at(-1).args.slice(0, 4)).toEqual([platform.value, 'album', 'new', 'new-next']);
            requests.at(-1).resolve(page('new-second'));
            await flush();
            expect(songs.value).toEqual([{ id: 'new-first' }, { id: 'new-second' }]);
        }
    );
    it('pauses automatic loading after a real failure until explicit retry succeeds', async () => {
        const { active, state, songs, requests } = await setupPagination();
        scrollToEnd(state);
        requests.at(-1).reject(new Error('page unavailable'));
        await flush();
        expect(mocks.error).toHaveBeenCalledExactlyOnceWith('page unavailable');
        expect(state.hasMore.value).toBe(true);
        expect(state.isLoadingMore.value).toBe(false);
        expect(songs.value).toEqual([{ id: 'first' }]);
        const count = requests.length;
        scrollToEnd(state);
        scrollToEnd(state);
        active.value = false;
        active.value = true;
        scrollToEnd(state);
        expect(requests).toHaveLength(count);
        state.loadMoreTracks();
        expect(requests).toHaveLength(count + 1);
        expect(requests.at(-1).args[3]).toBe('next');
        requests.at(-1).resolve(page('second', 'third'));
        await flush();
        scrollToEnd(state);
        expect(requests).toHaveLength(count + 2);
        expect(requests.at(-1).args[3]).toBe('third');
        requests.at(-1).resolve(page('third'));
        await flush();
        expect(songs.value).toEqual([{ id: 'first' }, { id: 'second' }, { id: 'third' }]);
        expect(mocks.error).toHaveBeenCalledTimes(1);
    });
});
describe('public collection request ownership', () => {
    it('exposes explicit next-page loading after an empty first page', async () => {
        const { state, songs, requests } = setup();
        state.loadPlaylist({ id: 'empty', collectionType: 'playlist' });
        requests.at(-1).resolve({ items: [], nextCursor: 'next' });
        await flush();
        expect(state.hasMore.value).toBe(true);
        state.loadMoreTracks();
        expect(requests.at(-1).args.slice(0, 4)).toEqual(['unknown', 'playlist', 'empty', 'next']);
        requests.at(-1).resolve(page('visible'));
        await flush();
        expect(songs.value).toEqual([{ id: 'visible' }]);
    });
    it('resumes canceled collections and discards responses from before suspension', async () => {
        const { active, state, requests } = setup();
        active.value = false;
        requests[0].resolve(page('canceled'));
        await flush();
        expect(state.playlists.value).toEqual([]);
        active.value = true;
        expect(requests.at(-1).args.slice(0, 3)).toEqual(['unknown', 'lists', 'U']);
        requests.at(-1).resolve(page('resumed'));
        await flush();
        expect(state.playlists.value).toEqual([{ id: 'resumed' }]);
    });
    it('resumes exact canceled track cursor without duplicating committed pages', async () => {
        const { active, state, songs, requests } = setup();
        requests[0].resolve(page('list'));
        await flush();
        state.loadPlaylist({ id: 'container', collectionType: 'playlist' });
        requests.at(-1).resolve(page('first', 'next'));
        await flush();
        state.loadMoreTracks();
        const canceled = requests.at(-1);
        active.value = false;
        canceled.resolve(page('stale'));
        await flush();
        active.value = true;
        expect(requests.at(-1).args.slice(0, 4)).toEqual(['unknown', 'playlist', 'container', 'next']);
        requests.at(-1).resolve(page('second'));
        await flush();
        expect(songs.value).toEqual([{ id: 'first' }, { id: 'second' }]);
    });
    it('does not issue inactive target requests and resumes only the latest binding', async () => {
        const { active, state, requests } = setup();
        active.value = false;
        mocks.user.bindings.unknown = 'replacement';
        expect(requests).toHaveLength(1);
        active.value = true;
        expect(requests.at(-1).args[2]).toBe('replacement');
        requests[0].resolve(page('stale'));
        requests.at(-1).resolve(page('replacement'));
        await flush();
        expect(state.playlists.value).toEqual([{ id: 'replacement' }]);
    });
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
