import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';

const generateToken = () => {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

let storedToken = localStorage.getItem(STORAGE_KEYS.TOKEN);
if (!storedToken) {
    storedToken = generateToken();
    localStorage.setItem(STORAGE_KEYS.TOKEN, storedToken);
}
const userToken = ref(storedToken);

const storageName = localStorage.getItem(STORAGE_KEYS.USERNAME);
const currentUser = ref({
    name: storageName || 'Guest',
    sessionId: ''
});

export const useUserStore = defineStore('user', () => {
    const onlineUsers = ref([]);

    const isAuthPassed = ref(false);

    // 启动时：严格从 LocalStorage 读取，默认值只在这里设定一次
    const storageName = localStorage.getItem('mp_username');
    const currentUser = ref({
        name: storageName || 'Guest',
        sessionId: ''
    });

    const bindings = ref(JSON.parse(localStorage.getItem(STORAGE_KEYS.BINDINGS) || '{}'));
    // 全局状态：控制改名弹窗显示
    const showNameModal = ref(false);

    const onNameSetCallback = ref(null);

    const isGuest = ref(!storageName);

    // 核心方法：将 SessionID 翻译成名字
    const resolveName = (id, fallbackName) => {
        if (!id) return 'Unknown';
        if (id === 'ADMIN') return 'AUTO_DJ';

        // 如果 ID 是我自己 (比较 Token)
        if (id === userToken.value) return currentUser.value.name;

        // 否则去在线列表里找 (通过 u.token 匹配)
        const u = onlineUsers.value.find(u => u.token === id);

        return u ? u.name : (fallbackName || 'Unknown Agent');
    };

    /**
     * 2. 初始化用户身份 (来自 /app/user/me)
     * 逻辑：对比服务器认为的名字 (serverName) 和我本地存储的名字
     * 返回：true 表示需要强制同步（改名），false 表示一致
     */
    const initUser = (sessionId, serverName) => {
        currentUser.value.sessionId = sessionId;

        // 如果后端返回的名字和本地不同
        // 情况A: 我是 Guest，后端给我分配了 Guest_7 -> 我应该更新显示，但保持 Guest 身份
        // 情况B: 我是 ThorNex，后端给我分配了 ThorNex_1 (去重) -> 我应该更新显示，保持 非Guest 身份
        if (serverName && serverName !== currentUser.value.name) {
            console.log(`Syncing name from server: ${serverName}`);
            currentUser.value.name = serverName;
            if (!isGuest.value) {
                localStorage.setItem(STORAGE_KEYS.USERNAME, serverName);            }
        }
        return false; // 不需要再发 rename 了，后端已经处理好了
    };

    const setOnlineUsers = (users) => {
        onlineUsers.value = users;
    };

    const updateBinding = (platform, accountId) => {
        bindings.value[platform] = accountId;
        localStorage.setItem(STORAGE_KEYS.BINDINGS, JSON.stringify(bindings.value));
    };

    // 只有这个方法有权修改 LocalStorage
    const saveName = (newName) => {
        if(!newName) return;
        currentUser.value.name = newName;
        localStorage.setItem(STORAGE_KEYS.USERNAME, newName);
        isGuest.value = false;
        // 保存成功后，自动关闭弹窗
        showNameModal.value = false;

        if (onNameSetCallback.value) {
            onNameSetCallback.value();
            onNameSetCallback.value = null;
        }
    }

    const setPostNameAction = (fn) => {
        onNameSetCallback.value = fn;
    }

    const resetAuthentication = () => {
        isAuthPassed.value = false;
        localStorage.removeItem(STORAGE_KEYS.ROOM_PASSWORD);// 清除本地保存的旧密码
    };

    return {
        onlineUsers,
        currentUser,
        bindings,
        initUser,
        setOnlineUsers,
        updateBinding,
        saveName,
        isGuest,
        showNameModal,
        resolveName,
        userToken,
        setPostNameAction,
        isAuthPassed,
        resetAuthentication
    };
});
