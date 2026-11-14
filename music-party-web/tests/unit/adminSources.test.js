import { beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive } from 'vue';
import { useAdminSources } from '../../src/composables/useAdminSources';
const mocks = vi.hoisted(() => ({
    admin: null,
    player: null,
    get: vi.fn(),
    save: vi.fn(),
    success: vi.fn(),
    error: vi.fn(),
}));
vi.mock('../../src/stores/admin', () => ({ useAdminStore: () => mocks.admin }));
vi.mock('../../src/stores/player', () => ({ usePlayerStore: () => mocks.player }));
vi.mock('../../src/api/admin', () => ({ adminApi: { getSources: mocks.get, setCookie: mocks.save } }));
vi.mock('../../src/composables/useToast', () => ({ useToast: () => ({ success: mocks.success, error: mocks.error }) }));
const descriptor = (configured = false) => ({
    id: 'qqmusic',
    label: 'QQ音乐',
    enabled: false,
    credential: { type: 'qqmusic-credential', label: 'QQ音乐登录凭据（JSON）', configured },
});
const flush = async () => {
    await nextTick();
    await nextTick();
};
let scope;
beforeEach(() => {
    scope?.stop();
    vi.clearAllMocks();
    mocks.admin = reactive({ showDashboard: false, isVerified: false, adminPassword: '' });
    mocks.player = reactive({ config: { sources: { qqmusic: { enabled: false } } } });
    mocks.get.mockResolvedValue({ sources: [descriptor()] });
    mocks.save.mockResolvedValue({ message: 'do not reflect response' });
});
const setup = () => {
    scope = effectScope();
    return scope.run(() => useAdminSources());
};
const open = async () => {
    Object.assign(mocks.admin, { adminPassword: 'admin-secret', isVerified: true, showDashboard: true });
    await flush();
};
describe('authenticated source credential directory', () => {
    it('requires confirmation before sending an explicit empty credential', async () => {
        mocks.get.mockResolvedValue({ sources: [descriptor(true)] });
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
        const state = setup();
        await open();
        state.credentialInputs.value.qqmusic = 'draft-secret';
        await state.clearCredential('qqmusic');
        expect(mocks.save).not.toHaveBeenCalled();
        expect(confirm).toHaveBeenCalledWith(expect.stringContaining('确定清除QQ音乐登录凭据（JSON）'));
        confirm.mockReturnValue(true);
        mocks.get.mockResolvedValue({ sources: [descriptor(false)] });
        await state.clearCredential('qqmusic');
        expect(mocks.save).toHaveBeenCalledExactlyOnceWith('admin-secret', 'qqmusic', '');
        expect(state.credentialInputs.value.qqmusic).toBe('');
        expect(state.platforms.value[0].credential.configured).toBe(false);
        expect(mocks.success).toHaveBeenCalledWith('凭据已清除');
    });
    it('keeps configured state and uses a safe error when credential clearing fails', async () => {
        mocks.get.mockResolvedValue({ sources: [descriptor(true)] });
        vi.spyOn(window, 'confirm').mockReturnValue(true);
        const state = setup();
        await open();
        mocks.save.mockRejectedValue({ response: { data: { message: 'reflected-secret' } } });
        await state.clearCredential('qqmusic');
        expect(state.platforms.value[0].credential.configured).toBe(true);
        expect(mocks.error).toHaveBeenCalledWith('凭据更新失败，请检查格式、权限或服务状态后重试');
    });
    it('loads only for a verified visible admin and keeps disabled registered sources manageable', async () => {
        const state = setup();
        expect(mocks.get).not.toHaveBeenCalled();
        await open();
        expect(mocks.get).toHaveBeenCalledWith('admin-secret', expect.any(AbortSignal));
        expect(state.platforms.value[0]).toEqual(descriptor());
        mocks.player.config.sources.qqmusic.enabled = true;
        expect(state.platforms.value[0].enabled).toBe(true);
    });
    it('does not copy secret-like response fields or prefill inputs', async () => {
        mocks.get.mockResolvedValue({
            sources: [
                {
                    ...descriptor(true),
                    secret: 'should-not-copy',
                    credential: { ...descriptor(true).credential, value: 'should-not-copy' },
                },
            ],
        });
        const state = setup();
        await open();
        expect(JSON.stringify(state.platforms.value)).not.toContain('should-not-copy');
        expect(state.credentialInputs.value).toEqual({});
    });
    it('saves JSON verbatim, clears the input and reloads configured status', async () => {
        const state = setup();
        await open();
        const json = '{"musicid":"123","musickey":"secret"}';
        state.credentialInputs.value.qqmusic = json;
        mocks.get.mockResolvedValue({ sources: [descriptor(true)] });
        await state.saveCredential('qqmusic');
        expect(mocks.save).toHaveBeenCalledExactlyOnceWith('admin-secret', 'qqmusic', json);
        expect(state.credentialInputs.value.qqmusic).toBe('');
        expect(state.platforms.value[0].credential.configured).toBe(true);
        expect(mocks.success).toHaveBeenCalledWith('凭据已更新');
    });
    it('never reflects upstream failures containing submitted credentials', async () => {
        const state = setup();
        await open();
        state.credentialInputs.value.qqmusic = 'sensitive-json';
        mocks.save.mockRejectedValue({ response: { data: { message: 'sensitive-json' } } });
        await state.saveCredential('qqmusic');
        expect(mocks.error).toHaveBeenCalledWith('凭据更新失败，请检查格式、权限或服务状态后重试');
        expect(state.saving.value.size).toBe(0);
    });
    it('clears inputs on close and ignores late save results across admin sessions', async () => {
        let resolve;
        mocks.save.mockReturnValue(
            new Promise((yes) => {
                resolve = yes;
            })
        );
        const state = setup();
        await open();
        state.credentialInputs.value.qqmusic = 'sensitive-json';
        const pending = state.saveCredential('qqmusic');
        mocks.admin.showDashboard = false;
        expect(state.credentialInputs.value).toEqual({});
        expect(state.platforms.value).toEqual([]);
        resolve({});
        await pending;
        expect(mocks.success).not.toHaveBeenCalled();
    });
    it('aborts stale directory responses on logout and shows safe directory errors', async () => {
        let resolve;
        mocks.get.mockReturnValueOnce(
            new Promise((yes) => {
                resolve = yes;
            })
        );
        const state = setup();
        await open();
        const signal = mocks.get.mock.calls[0][1];
        mocks.admin.isVerified = false;
        expect(signal.aborted).toBe(true);
        resolve({ sources: [descriptor()] });
        await flush();
        expect(state.platforms.value).toEqual([]);
        mocks.get.mockRejectedValue(new Error('admin-secret'));
        mocks.admin.isVerified = true;
        await flush();
        expect(state.loadError.value).toBe('音乐源管理目录读取失败，请重试');
    });
});
