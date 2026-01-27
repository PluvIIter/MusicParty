import { ref } from 'vue';
import { useToast } from './useToast';
import { musicApi } from '../api/music.js';
import { authApi } from '../api/auth.js';

export function useSearchLogic(emit) {
    const { success, error } = useToast();

    const platform = ref('netease');
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const listMode = ref('search'); // 'search' | 'playlist'
    const isAdminMode = ref(false);

    // 存储原始的管理员指令
    const adminCommand = ref('');

    const handleAdminCommand = async (pwd) => {
        try {
            await authApi.adminCommand(pwd, adminCommand.value);
            success('ADMIN COMMAND EXECUTED');
            emit('close');
        } catch (e) {
            error(e.response?.data?.message || 'ACCESS DENIED OR COMMAND FAILED');
        } finally {
            isAdminMode.value = false;
            keyword.value = '';
            adminCommand.value = '';
        }
    };

    const doSearch = async () => {
        const val = keyword.value.trim();
        if (!val) return;

        // 1. 管理员密码输入模式
        if (isAdminMode.value) {
            await handleAdminCommand(val);
            return;
        }

        if (val.startsWith('//')) {
            isAdminMode.value = true;
            adminCommand.value = val; // 保存完整指令
            keyword.value = ''; // 清空输入框，准备输入密码
            return;
        }


        // 3. 普通搜索
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
        isAdminMode,
        doSearch
    };
}