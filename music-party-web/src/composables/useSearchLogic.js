import { ref, watch, onScopeDispose } from 'vue';
import { storeToRefs } from 'pinia';
import { useSourcesStore } from '../stores/sources';
import { useToast } from './useToast';
import { musicApi } from '../api/music.js';
import { createRequestScope, isCanceled } from '../domain/sources';

export function useSearchLogic() {
    const { error } = useToast();
    const sources = useSourcesStore();
    const { searchSource: platform } = storeToRefs(sources);
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const loadingMore = ref(false);
    const nextCursor = ref(null);
    const listMode = ref('search');
    const requests = createRequestScope();
    let submittedKeyword = '';
    watch(
        () => sources.enabled.map((source) => source.id),
        (ids) => {
            if (!ids.includes(platform.value)) platform.value = ids[0] || '';
        },
        { immediate: true, flush: 'sync' }
    );
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
            submittedKeyword = '';
        },
        { flush: 'sync' }
    );
    onScopeDispose(cancelSearch);

    const doSearch = async () => {
        const val = keyword.value.trim();
        if (!val || !sources.supports(platform.value, 'search')) return;
        listMode.value = 'search';
        submittedKeyword = val;
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
        const val = submittedKeyword;
        if (
            !val ||
            !sources.supports(platform.value, 'search') ||
            !nextCursor.value ||
            loading.value ||
            loadingMore.value
        )
            return;
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
