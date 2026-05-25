import { ref } from 'vue';
import { useToast } from './useToast';
import { musicApi } from '../api/music.js';

export function useSearchLogic(emit) {
    const { error } = useToast();

    const platform = ref('netease');
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const listMode = ref('search'); // 'search' | 'playlist'

    const doSearch = async () => {
        const val = keyword.value.trim();
        if (!val) return;

        // 普通搜索
        listMode.value = 'search';
        loading.value = true;
        songs.value = [];
        try {
            const data = await musicApi.search(platform.value, val);
            songs.value = data;
        } catch (e) {
            console.error(e);
            const msg = e.response?.data?.message || 'Search Failed';
            error(msg);
        } finally {
            loading.value = false;
        }
    };

    return {
        platform,
        keyword,
        songs,
        loading,
        listMode,
        doSearch
    };
}
