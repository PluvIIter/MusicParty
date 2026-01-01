import { ref } from 'vue';
import { useToast } from './useToast';
import { musicApi } from '../api/music';
import { authApi } from '../api/auth';

export function useSearchLogic(emit) {
    const { success, error } = useToast();

    const platform = ref('netease');
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const listMode = ref('search'); // 'search' | 'playlist'
    const isAdminMode = ref(false);

    // 管理员指令状态
    const adminCommandType = ref('');
    const adminCommandArg = ref('');

    const handleAdminCommand = async (pwd) => {
        try {
            if (adminCommandType.value === 'RESET') {
                await authApi.adminReset(pwd);
                success('SYSTEM PURGED');
            } else if (adminCommandType.value === 'PASS') {
                await authApi.adminSetPassword(pwd, adminCommandArg.value);
                success('ROOM PASSWORD UPDATED');
            } else if (adminCommandType.value === 'OPEN') {
                await authApi.adminSetPassword(pwd, "");
                success('ROOM IS NOW PUBLIC');
            }
            emit('close');
        } catch (e) {
            error('ACCESS DENIED');
        } finally {
            isAdminMode.value = false;
            keyword.value = '';
            adminCommandType.value = '';
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

        // 2. 指令拦截
        if (val === '//RESET') {
            isAdminMode.value = true;
            adminCommandType.value = 'RESET';
            keyword.value = '';
            return;
        }
        if (val.startsWith('//PASS ')) {
            isAdminMode.value = true;
            adminCommandType.value = 'PASS';
            adminCommandArg.value = val.substring(7);
            keyword.value = '';
            return;
        }
        if (val === '//OPEN') {
            isAdminMode.value = true;
            adminCommandType.value = 'OPEN';
            keyword.value = '';
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
            error('Search Failed');
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