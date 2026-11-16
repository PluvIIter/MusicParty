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
    const loadingMore = ref(false);
    const nextCursor = ref(null);
    const listMode = ref('search');
    const requests = createRequestScope();
    const cancelSearch = () => {
        requests.cancel();
        loading.value = false;
        loadingMore.value = false;
    };
    watch(
        [platform, listMode],
        () => {
            cancelSearch();
            songs.value = [];
            nextCursor.value = null;
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
        loadingMore.value = false;
        songs.value = [];
        nextCursor.value = null;
        try {
            const data = await musicApi.search(platform.value, val, request.signal);
            if (request.current()) {
                songs.value = data.items;
                nextCursor.value = data.nextCursor || null;
            }
        } catch (e) {
            if (request.current() && !isCanceled(e)) error(e.response?.data?.message || 'Search Failed');
        } finally {
            if (request.current()) loading.value = false;
        }
    };

    /** 滚动到底时追加下一页；没有游标或正在加载时静默跳过。 */
    const loadMore = async () => {
        const val = keyword.value.trim();
        if (!val || !platform.value || !nextCursor.value || loading.value || loadingMore.value) return;
        if (listMode.value !== 'search') return;
        const cursor = nextCursor.value;
        const request = requests.start();
        loadingMore.value = true;
        try {
            const data = await musicApi.search(platform.value, val, request.signal, cursor);
            if (request.current()) {
                songs.value = [...songs.value, ...data.items];
                nextCursor.value = data.nextCursor || null;
            }
        } catch (e) {
            if (request.current() && !isCanceled(e)) error(e.response?.data?.message || 'Search Failed');
        } finally {
            if (request.current()) loadingMore.value = false;
        }
    };

    return {
        platform,
        keyword,
        songs,
        loading,
        loadingMore,
        nextCursor,
        listMode,
        doSearch,
        loadMore,
        cancelSearch,
    };
}
