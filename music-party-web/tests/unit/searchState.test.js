import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { createPinia, setActivePinia } from 'pinia';
import { computed, createApp, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useSourcesStore } from '../../src/stores/sources';
import { useSearchLogic } from '../../src/composables/useSearchLogic';
import { usePlaylistLogic } from '../../src/composables/usePlaylistLogic';

const mocks = vi.hoisted(() => ({ search: vi.fn(), getSources: vi.fn(), error: vi.fn() }));
vi.mock('../../src/api/music', () => ({ musicApi: mocks }));
vi.mock('../../src/stores/user.js', () => ({ useUserStore: () => ({ bindings: {} }) }));
vi.mock('../../src/composables/useToast', () => ({ useToast: () => ({ error: mocks.error }) }));

const source = readFileSync('src/components/SearchModal.vue', 'utf8');
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import\s[\s\S]*?;\s*$/gm, '');
const setupModal = new Function(
    'dependencies',
    `
    const { ref, computed, watch, onMounted, onUnmounted, defineProps, defineEmits, useSourcesStore,
        useSearchLogic, usePlaylistLogic, useToast, useLikedSongs, usePlayerStore, useUserStore,
        resourceKey, openExternal } = dependencies;
    ${script}
    return { platform, keyword, songs, loading, loadingMore, nextCursor, selectPlatform, handleSearchAction,
        loadMore, mode };
`
);
const catalog = () => ['netease', 'qqmusic', 'bilibili'].map((id) => ({ id, capabilities: { search: true } }));
const page = (id, nextCursor = null) => ({ items: [{ id }], nextCursor });
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
let apps, requests, sources;
function mount() {
    const props = reactive({ isOpen: false });
    let state;
    const app = createApp({
        setup() {
            state = setupModal({
                ref,
                computed,
                watch,
                onMounted,
                onUnmounted,
                defineProps: () => props,
                defineEmits: () => vi.fn(),
                useSourcesStore,
                useSearchLogic,
                usePlaylistLogic,
                useToast: () => ({ success: vi.fn(), error: mocks.error }),
                useLikedSongs: () => ({ likedSongs: ref([]) }),
                usePlayerStore: () => ({ queue: [] }),
                useUserStore: () => ({}),
                resourceKey: (song) => `${song.platform}:${song.id}`,
                openExternal: vi.fn(),
            });
            return () => null;
        },
    });
    app.mount(document.createElement('div'));
    apps.push(app);
    return { props, state, app };
}
beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    apps = [];
    requests = [];
    sources = useSourcesStore();
    sources.sources = catalog();
    mocks.getSources.mockImplementation(async () => ({ sources: sources.sources.slice() }));
    mocks.search.mockImplementation((...args) => {
        const request = { ...deferred(), args };
        requests.push(request);
        return request.promise;
    });
});
afterEach(() => apps.forEach((app) => app.unmount()));

describe('explicit search and source selection', () => {
    it('keeps the selected source and keyword when reopening after catalog refresh', async () => {
        const { props, state } = mount();
        await flush();
        props.isOpen = true;
        state.selectPlatform('qqmusic');
        state.keyword.value = 'keep this query';
        await flush();
        props.isOpen = false;
        await flush();
        props.isOpen = true;
        await flush();
        expect(state.platform.value).toBe('qqmusic');
        expect(state.keyword.value).toBe('keep this query');
        expect(mocks.search).not.toHaveBeenCalled();
    });
    it('keeps the selected source across modal remounts', async () => {
        const first = mount();
        await flush();
        first.state.selectPlatform('qqmusic');
        first.app.unmount();
        apps = [];
        const second = mount();
        await flush();
        expect(second.state.platform.value).toBe('qqmusic');
        expect(mocks.search).not.toHaveBeenCalled();
    });
    it('switches source with existing text without searching, until explicit search', async () => {
        const { props, state } = mount();
        props.isOpen = true;
        await flush();
        state.keyword.value = '  a song  ';
        state.selectPlatform('qqmusic');
        await flush();
        expect(mocks.search).not.toHaveBeenCalled();
        expect(state.keyword.value).toBe('  a song  ');
        const pending = state.handleSearchAction();
        expect(requests[0].args.slice(0, 2)).toEqual(['qqmusic', 'a song']);
        requests[0].resolve(page('qq-song'));
        await pending;
        expect(state.songs.value).toEqual([{ id: 'qq-song' }]);
    });
    it('does not search when returning from local likes to an enabled source', async () => {
        const { state } = mount();
        await flush();
        state.keyword.value = 'saved song';
        state.mode.value = 'likesong';
        state.selectPlatform('qqmusic');
        await flush();
        expect(state.mode.value).toBe('search');
        expect(state.platform.value).toBe('qqmusic');
        expect(state.keyword.value).toBe('saved song');
        expect(mocks.search).not.toHaveBeenCalled();
    });
    it.each(['resolve', 'reject'])('ignores a late %s from the previous source', async (completion) => {
        const { props, state } = mount();
        props.isOpen = true;
        await flush();
        state.keyword.value = 'query';
        const old = state.handleSearchAction();
        state.selectPlatform('qqmusic');
        expect(requests[0].args[2].aborted).toBe(true);
        expect(state.loading.value).toBe(false);
        expect(state.songs.value).toEqual([]);
        const current = state.handleSearchAction();
        if (completion === 'resolve') requests[0].resolve(page('old', 'old-next'));
        else requests[0].reject(new Error('old source failed'));
        await old;
        expect(state.loading.value).toBe(true);
        expect(state.songs.value).toEqual([]);
        expect(state.nextCursor.value).toBeNull();
        expect(mocks.error).not.toHaveBeenCalled();
        requests[1].resolve(page('current', 'current-next'));
        await current;
        expect(state.songs.value).toEqual([{ id: 'current' }]);
        expect(state.nextCursor.value).toBe('current-next');
    });
    it('ignores a pending search after closing without resubmitting it on reopen', async () => {
        const { props, state } = mount();
        props.isOpen = true;
        await flush();
        state.selectPlatform('qqmusic');
        state.keyword.value = 'query';
        const pending = state.handleSearchAction();
        props.isOpen = false;
        expect(requests[0].args[2].aborted).toBe(true);
        requests[0].resolve(page('late', 'next'));
        await pending;
        props.isOpen = true;
        await flush();
        expect(state.songs.value).toEqual([]);
        expect(state.nextCursor.value).toBeNull();
        expect(state.platform.value).toBe('qqmusic');
        expect(state.keyword.value).toBe('query');
        expect(mocks.search).toHaveBeenCalledTimes(1);
    });
    it('falls back immediately when a source is disabled without searching or clearing text', async () => {
        const { props, state } = mount();
        props.isOpen = true;
        await flush();
        state.selectPlatform('qqmusic');
        state.keyword.value = 'keep me';
        const pending = state.handleSearchAction();
        sources.overrides = { qqmusic: { enabled: false } };
        expect(state.platform.value).toBe('netease');
        expect(requests[0].args[2].aborted).toBe(true);
        requests[0].resolve(page('disabled-result'));
        await pending;
        state.selectPlatform('qqmusic');
        expect(state.platform.value).toBe('netease');
        expect(state.keyword.value).toBe('keep me');
        expect(state.songs.value).toEqual([]);
        expect(mocks.search).toHaveBeenCalledTimes(1);
    });
    it('falls back when a refreshed catalog removes the selection while closed', async () => {
        const { state, props } = mount();
        await flush();
        state.selectPlatform('qqmusic');
        state.keyword.value = 'keep me';
        props.isOpen = true;
        await flush();
        props.isOpen = false;
        sources.sources = [catalog()[2]];
        props.isOpen = true;
        await flush();
        expect(state.platform.value).toBe('bilibili');
        expect(state.keyword.value).toBe('keep me');
        expect(mocks.search).not.toHaveBeenCalled();
    });
    it('handles an empty catalog and later restoration without an implicit search', async () => {
        const { state } = mount();
        await flush();
        state.keyword.value = 'keep me';
        sources.sources = [];
        expect(state.platform.value).toBe('');
        await state.handleSearchAction();
        sources.sources = [catalog()[1]];
        expect(state.platform.value).toBe('qqmusic');
        expect(state.keyword.value).toBe('keep me');
        expect(mocks.search).not.toHaveBeenCalled();
    });
    it('uses the submitted query for pagination without clearing an edited keyword', async () => {
        const { state } = mount();
        await flush();
        state.keyword.value = 'original';
        const first = state.handleSearchAction();
        requests[0].resolve(page('first', 'next'));
        await first;
        state.keyword.value = 'unfinished edit';
        const more = state.loadMore();
        expect(requests[1].args.slice(0, 2)).toEqual(['netease', 'original']);
        expect(requests[1].args[3]).toBe('next');
        requests[1].resolve(page('second'));
        await more;
        expect(state.songs.value).toEqual([{ id: 'first' }, { id: 'second' }]);
        expect(state.keyword.value).toBe('unfinished edit');
    });
    it('discards pending pagination on a source switch and requires a fresh explicit search', async () => {
        const { state } = mount();
        await flush();
        state.keyword.value = 'query';
        const first = state.handleSearchAction();
        requests[0].resolve(page('first', 'next'));
        await first;
        const more = state.loadMore();
        state.selectPlatform('qqmusic');
        expect(requests[1].args[2].aborted).toBe(true);
        requests[1].resolve(page('late-page', 'another'));
        await more;
        await state.loadMore();
        expect(state.loadingMore.value).toBe(false);
        expect(state.songs.value).toEqual([]);
        expect(state.nextCursor.value).toBeNull();
        expect(state.keyword.value).toBe('query');
        expect(mocks.search).toHaveBeenCalledTimes(2);
    });
});
