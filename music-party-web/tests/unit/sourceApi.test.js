import { beforeEach, describe, expect, it, vi } from 'vitest';
import { musicApi } from '../../src/api/music';
const mocks = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../../src/api/client', () => ({ default: { get: mocks.get } }));
beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: () => 'room-secret' });
    mocks.get.mockClear();
});
describe('v2 source resource requests', () => {
    it('encodes opaque IDs as segments and always sends the selected public target', () => {
        const signal = new AbortController().signal;
        musicApi.getCollections('new/source', 'public/list', 'chosen-user', 'opaque:cursor', signal);
        expect(mocks.get).toHaveBeenCalledWith(
            '/api/v2/sources/new%2Fsource/collections/public%2Flist',
            expect.objectContaining({
                signal,
                params: { userId: 'chosen-user', cursor: 'opaque:cursor', limit: 50 },
                headers: { 'X-Room-Password': 'room-secret' },
            })
        );
        musicApi.getContainerTracks('new/source', 'mix/type', 'container/id', null, signal);
        expect(mocks.get).toHaveBeenLastCalledWith(
            '/api/v2/sources/new%2Fsource/containers/mix%2Ftype/container%2Fid/tracks',
            expect.objectContaining({ signal, params: { cursor: null, limit: 50 } })
        );
    });
    it('keeps search query data out of route segments', () => {
        musicApi.search('future', 'a/b?x=#y');
        expect(mocks.get).toHaveBeenCalledWith(
            '/api/v2/sources/future/tracks/search',
            expect.objectContaining({ params: { q: 'a/b?x=#y' } })
        );
    });
});
