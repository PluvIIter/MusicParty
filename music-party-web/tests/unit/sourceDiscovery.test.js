import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { effectScope, nextTick } from 'vue';
import { resourceKey, safeExternalUrl, createRequestScope } from '../../src/domain/sources';
import { useSourcesStore } from '../../src/stores/sources';
import { useSearchLogic } from '../../src/composables/useSearchLogic';
const mocks = vi.hoisted(() => ({ catalog: vi.fn(), search: vi.fn(), error: vi.fn() }));
vi.mock('../../src/api/music', () => ({ musicApi: { getSources: mocks.catalog, search: mocks.search } }));
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
    setActivePinia(createPinia());
    vi.clearAllMocks();
});
describe('source discovery and identity', () => {
    it('discovers an unknown source and uses declared capabilities, not IDs', async () => {
        mocks.catalog.mockResolvedValue({
            schemaVersion: 2,
            sources: [
                { id: 'future-source', capabilities: { search: true, album: false }, delivery: { mode: 'DIRECT_URL' } },
            ],
        });
        const store = useSourcesStore();
        await store.load();
        expect(store.enabled.map((s) => s.id)).toEqual(['future-source']);
        expect(store.supports('future-source', 'search')).toBe(true);
        expect(store.supports('future-source', 'album')).toBe(false);
        store.overrides = { 'future-source': { enabled: false } };
        expect(store.enabled).toEqual([]);
    });
    it('namespaces equal IDs without delimiter collisions', () => {
        expect(resourceKey({ platform: 'a', id: 'same' })).not.toBe(resourceKey({ platform: 'b', id: 'same' }));
        expect(resourceKey({ platform: 'a:b', id: 'c' })).not.toBe(resourceKey({ platform: 'a', id: 'b:c' }));
    });
    it('accepts only absolute HTTP(S) external links', () => {
        for (const value of ['javascript:alert(1)', 'data:text/html,test', '/relative', '//example.com', ''])
            expect(safeExternalUrl(value)).toBe('');
        expect(safeExternalUrl('https://example.com/track/1')).toBe('https://example.com/track/1');
    });
    it('invalidates ignored aborts and canceled scopes', () => {
        const scope = createRequestScope(),
            first = scope.start(),
            second = scope.start();
        expect(first.signal.aborted).toBe(true);
        expect(first.current()).toBe(false);
        scope.cancel();
        expect(second.current()).toBe(false);
    });
    it('isolates deferred searches across source, mode and unmount transitions', async () => {
        const requests = [];
        mocks.search.mockImplementation(() => {
            const request = deferred();
            requests.push(request);
            return request.promise;
        });
        const scope = effectScope();
        const state = scope.run(() => useSearchLogic());
        state.platform.value = 'first';
        state.keyword.value = 'query';
        state.doSearch();
        state.platform.value = 'future';
        state.doSearch();
        requests[0].reject(new Error('stale'));
        await nextTick();
        expect(mocks.error).not.toHaveBeenCalled();
        expect(state.loading.value).toBe(true);
        requests[1].resolve({ items: [{ id: 'new' }] });
        await nextTick();
        expect(state.songs.value).toEqual([{ id: 'new' }]);
        state.doSearch();
        state.listMode.value = 'playlist';
        state.songs.value = [{ id: 'container' }];
        requests[2].resolve({ items: [{ id: 'late' }] });
        await nextTick();
        expect(state.songs.value).toEqual([{ id: 'container' }]);
        state.doSearch();
        scope.stop();
        requests[3].resolve({ items: [{ id: 'late-unmount' }] });
        await nextTick();
        expect(state.songs.value).toEqual([]);
    });
});
