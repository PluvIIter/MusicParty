import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { computed, createApp, nextTick, onUnmounted, reactive, ref, watch } from 'vue';
import { createRequestScope, isCanceled, resourceKey } from '../../src/domain/sources';

const source = readFileSync('src/components/AlbumModal.vue', 'utf8');
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import\s[\s\S]*?;\s*$/gm, '');
const setupAlbum = new Function(
    'dependencies',
    `
    const { ref, computed, watch, onUnmounted, defineProps, defineEmits, usePlayerStore, useUserStore, useSourcesStore, musicApi, createRequestScope, isCanceled, resourceKey } = dependencies;
    ${script}
    return { album, loading, loadError };
`
);
const deferred = () => {
    let resolve;
    const promise = new Promise((yes) => {
        resolve = yes;
    });
    return { promise, resolve };
};
describe('album request ownership', () => {
    let app, props, state, metadata, tracks;
    beforeEach(() => {
        metadata = [];
        tracks = [];
        const musicApi = {
            getSongAlbum: vi.fn((...args) => {
                const request = deferred();
                metadata.push({ args, ...request });
                return request.promise;
            }),
            getContainerTracks: vi.fn((...args) => {
                const request = deferred();
                tracks.push({ args, ...request });
                return request.promise;
            }),
        };
        props = reactive({ sourceId: 'first', songId: 'same', albumId: '' });
        app = createApp({
            setup() {
                state = setupAlbum({
                    ref,
                    computed,
                    watch,
                    onUnmounted,
                    defineProps: () => props,
                    defineEmits: () => vi.fn(),
                    usePlayerStore: () => ({ queue: [] }),
                    useUserStore: () => ({}),
                    useSourcesStore: () => ({}),
                    musicApi,
                    createRequestScope,
                    isCanceled,
                    resourceKey,
                });
                return () => null;
            },
        });
        app.mount(document.createElement('div'));
    });
    afterEach(() => app.unmount());
    it('ignores stale metadata before starting track reads under a different source', async () => {
        props.sourceId = 'second';
        await nextTick();
        metadata[0].resolve({ id: 'old', collectionType: 'album' });
        await nextTick();
        expect(tracks).toEqual([]);
        expect(state.loading.value).toBe(true);
        metadata[1].resolve({ id: 'new', name: 'Current', collectionType: 'album' });
        await nextTick();
        expect(tracks[0].args.slice(0, 3)).toEqual(['second', 'album', 'new']);
        tracks[0].resolve({ items: [{ id: 'current-song' }], nextCursor: null });
        await nextTick();
        expect(state.album.value.name).toBe('Current');
    });
    it('does not append old pagination after a new album or unmount', async () => {
        metadata[0].resolve({ id: 'old', collectionType: 'album' });
        await nextTick();
        tracks[0].resolve({ items: [{ id: 'first-page' }], nextCursor: 'later' });
        await nextTick();
        expect(tracks[1].args[3]).toBe('later');
        props.songId = 'new-song';
        await nextTick();
        tracks[1].resolve({ items: [{ id: 'stale-page' }], nextCursor: null });
        await nextTick();
        expect(state.album.value).toBe(null);
        metadata[1].resolve({ id: 'new', collectionType: 'album' });
        await nextTick();
        app.unmount();
        tracks[2].resolve({ items: [{ id: 'unmounted' }], nextCursor: null });
        await nextTick();
        expect(state.album.value).toBe(null);
    });
});
