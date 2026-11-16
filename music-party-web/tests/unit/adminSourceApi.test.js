import { expect, it, vi } from 'vitest';
import { adminApi } from '../../src/api/admin';
const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../../src/api/client', () => ({ default: mocks }));
it('uses admin-only source metadata without sending credentials to the public catalog', () => {
    const signal = new AbortController().signal;
    adminApi.getSources('administrator', signal);
    expect(mocks.get).toHaveBeenCalledWith('/api/admin/sources', {
        signal,
        headers: { 'X-Admin-Password': 'administrator' },
    });
    adminApi.setCookie('administrator', 'qqmusic', '{"musicid":"1"}');
    expect(mocks.post).toHaveBeenCalledWith(
        '/api/admin/config/cookie',
        { platform: 'qqmusic', value: '{"musicid":"1"}' },
        { headers: { 'X-Admin-Password': 'administrator' } }
    );
});
