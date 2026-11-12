import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { musicApi } from '../api/music';

export const useSourcesStore = defineStore('sources', () => {
    const sources = ref([]);
    const loading = ref(false);
    const loadError = ref('');
    let request;
    let reloadRequested = false;
    const enabled = computed(() => sources.value.filter((source) => isEnabled(source.id)));
    const descriptor = (id) => sources.value.find((source) => source.id === id);
    const overrides = ref({});
    const isEnabled = (id) => !!descriptor(id) && (overrides.value[id]?.enabled ?? true);
    const supports = (id, capability) => isEnabled(id) && descriptor(id)?.capabilities?.[capability] === true;
    const load = (refresh = false) => {
        if (request) {
            if (refresh) reloadRequested = true;
            return request;
        }
        loading.value = true;
        loadError.value = '';
        request = musicApi
            .getSources()
            .then((catalog) => {
                sources.value = catalog.sources || [];
            })
            .catch((error) => {
                loadError.value = error.response?.data?.message || error.message || '音乐源读取失败';
            })
            .finally(() => {
                loading.value = false;
                request = null;
                if (reloadRequested) {
                    reloadRequested = false;
                    return load();
                }
            });
        return request;
    };
    return { sources, enabled, loading, loadError, overrides, descriptor, isEnabled, supports, load };
});
