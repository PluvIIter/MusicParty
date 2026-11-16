import { computed, ref, watch, onScopeDispose } from 'vue';
import { adminApi } from '../api/admin';
import { createRequestScope, isCanceled } from '../domain/sources';
import { useAdminStore } from '../stores/admin';
import { usePlayerStore } from '../stores/player';
import { useToast } from './useToast';

export function useAdminSources() {
    const admin = useAdminStore();
    const player = usePlayerStore();
    const { success, error } = useToast();
    const directory = ref([]);
    const credentialInputs = ref({});
    const saving = ref(new Set());
    const loading = ref(false);
    const loadError = ref('');
    const requests = createRequestScope();
    let generation = 0;
    const active = computed(() => admin.showDashboard && admin.isVerified && !!admin.adminPassword);
    const platforms = computed(() =>
        directory.value.map((source) => ({
            ...source,
            enabled: player.config.sources?.[source.id]?.enabled ?? source.enabled,
        }))
    );
    const load = async () => {
        if (!active.value) return;
        const request = requests.start();
        loading.value = true;
        loadError.value = '';
        try {
            const response = await adminApi.getSources(admin.adminPassword, request.signal);
            if (!request.current()) return;
            directory.value = response.sources.map((source) => ({
                id: source.id,
                label: source.label,
                enabled: source.enabled,
                credential: source.credential
                    ? {
                          type: source.credential.type,
                          label: source.credential.label,
                          configured: source.credential.configured === true,
                      }
                    : null,
            }));
        } catch (e) {
            if (request.current() && !isCanceled(e)) loadError.value = '音乐源管理目录读取失败，请重试';
        } finally {
            if (request.current()) loading.value = false;
        }
    };
    const submitCredential = async (id, value) => {
        const source = directory.value.find((item) => item.id === id);
        if (!active.value || !source?.credential || saving.value.has(id)) return;
        const version = generation;
        saving.value.add(id);
        try {
            await adminApi.setCookie(admin.adminPassword, id, value);
            if (version !== generation || !active.value) return;
            credentialInputs.value[id] = '';
            source.credential.configured = value !== '';
            success(value === '' ? '凭据已清除' : '凭据已更新');
            await load();
        } catch {
            if (version === generation && active.value) error('凭据更新失败，请检查格式、权限或服务状态后重试');
        } finally {
            if (version === generation) saving.value.delete(id);
        }
    };
    const saveCredential = (id) => {
        const value = credentialInputs.value[id]?.trim();
        if (value) return submitCredential(id, value);
    };
    const clearCredential = (id) => {
        const source = directory.value.find((item) => item.id === id);
        if (!active.value || !source?.credential?.configured || saving.value.has(id)) return;
        if (window.confirm(`确定清除${source.credential.label}？清除后，依赖该凭据的播放或推荐可能不可用。`))
            return submitCredential(id, '');
    };
    watch(
        () => [active.value, admin.adminPassword],
        () => {
            generation++;
            requests.cancel();
            directory.value = [];
            credentialInputs.value = {};
            saving.value = new Set();
            loading.value = false;
            loadError.value = '';
            if (active.value) load();
        },
        { immediate: true, flush: 'sync' }
    );
    onScopeDispose(() => {
        generation++;
        requests.cancel();
        credentialInputs.value = {};
    });
    return { platforms, credentialInputs, saving, loading, loadError, load, saveCredential, clearCredential };
}
