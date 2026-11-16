import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { computed, reactive } from 'vue';

const source = readFileSync('src/components/AdminDashboard.vue', 'utf8');
const logic = source.slice(source.indexOf('const privateDj ='), source.indexOf('const togglePrivateDjSwitch ='));
const setup = new Function(
    'dependencies',
    `
    const { computed, playerStore, adminStore, adminApi, success, error } = dependencies;
    ${logic}
    return { privateDj, supportsPrivateDjMode, privateDjModeSupported, privateDjSwitches, setPrivateDjMode };
`
);
let playerStore, api, error, success, state;
beforeEach(() => {
    playerStore = reactive({
        config: { privateDj: { source: 'selected', mode: 'OFF' } },
    });
    api = { updatePrivateDj: vi.fn().mockResolvedValue({ message: 'Saved' }) };
    error = vi.fn();
    success = vi.fn();
    state = setup({ computed, playerStore, adminStore: { adminPassword: 'admin' }, adminApi: api, success, error });
});
describe('selected deployment recommendation capabilities', () => {
    it('permits FM but rejects DJ for an FM-only selected provider', async () => {
        Object.assign(playerStore.config.privateDj, { fmSupported: true, djSupported: false });
        expect(state.supportsPrivateDjMode('FM')).toBe(true);
        expect(state.supportsPrivateDjMode('DJ')).toBe(false);
        expect(state.privateDjSwitches.value.every((item) => !item.hint.includes('DJ'))).toBe(true);
        await state.setPrivateDjMode('DJ');
        expect(api.updatePrivateDj).not.toHaveBeenCalled();
        await state.setPrivateDjMode('FM');
        expect(api.updatePrivateDj).toHaveBeenCalledExactlyOnceWith('admin', { mode: 'FM' });
    });
    it('accepts both explicit selected-provider flags', async () => {
        Object.assign(playerStore.config.privateDj, { fmSupported: true, djSupported: true });
        await state.setPrivateDjMode('DJ');
        await state.setPrivateDjMode('FM');
        expect(api.updatePrivateDj.mock.calls.map(([, body]) => body.mode)).toEqual(['DJ', 'FM']);
    });
    it.each([{}, { fmSupported: false, djSupported: false }, { fmSupported: 'true', djSupported: 1 }])(
        'fails closed for absent, unavailable or non-boolean support %j',
        async (flags) => {
            Object.assign(playerStore.config.privateDj, flags);
            await state.setPrivateDjMode('FM');
            await state.setPrivateDjMode('DJ');
            expect(api.updatePrivateDj).not.toHaveBeenCalled();
            await state.setPrivateDjMode('OFF');
            expect(api.updatePrivateDj).toHaveBeenCalledExactlyOnceWith('admin', { mode: 'OFF' });
        }
    );
    it('reacts to deployment state replacement without retaining old support', () => {
        playerStore.config.privateDj = { source: 'one', mode: 'DJ', fmSupported: true, djSupported: true };
        expect(state.privateDjModeSupported.value).toBe(true);
        playerStore.config.privateDj = { source: 'two', mode: 'DJ', fmSupported: true, djSupported: false };
        expect(state.privateDjModeSupported.value).toBe(false);
        expect(state.supportsPrivateDjMode('FM')).toBe(true);
    });
    it('keeps server state and surfaces authoritative POST rejection', async () => {
        Object.assign(playerStore.config.privateDj, { fmSupported: true, djSupported: true });
        api.updatePrivateDj.mockRejectedValue({ response: { data: { message: 'Selected provider disabled' } } });
        await state.setPrivateDjMode('DJ');
        expect(error).toHaveBeenCalledWith('Selected provider disabled');
        expect(success).not.toHaveBeenCalled();
        expect(playerStore.config.privateDj.mode).toBe('OFF');
    });
});
