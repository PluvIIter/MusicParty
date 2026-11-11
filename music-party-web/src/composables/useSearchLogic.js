import { ref, watch, onScopeDispose } from 'vue';
import { useToast } from './useToast';
import { musicApi } from '../api/music.js';
import { createRequestScope, isCanceled } from '../domain/sources';

export function useSearchLogic() {
    const { error } = useToast();
    const platform = ref('');
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const listMode = ref('search');
    const requests = createRequestScope();
    const cancelSearch = () => {
        requests.cancel();
        loading.value = false;
    };
    watch(
        [platform, listMode],
        () => {
            cancelSearch();
            songs.value = [];
        },
        { flush: 'sync' }
    );
    onScopeDispose(cancelSearch);
    const doSearch = async () => {
        const val = keyword.value.trim();
        if (!val || !platform.value) return;
        listMode.value = 'search';
        const request = requests.start();
        loading.value = true;
        songs.value = [];
        try {
            const data = await musicApi.search(platform.value, val, request.signal);
            if (request.current()) songs.value = data.items;
        } catch (e) {
            if (request.current() && !isCanceled(e)) error(e.response?.data?.message || 'Search Failed');
        } finally {
            if (request.current()) loading.value = false;
        }
    };
    return { platform, keyword, songs, loading, listMode, doSearch, cancelSearch };
}
