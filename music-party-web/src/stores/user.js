import { defineStore } from 'pinia';
import { ref, computed } from 'vue';

const generateToken = () => {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

let storedToken = localStorage.getItem('mp_user_token');
if (!storedToken) {
    storedToken = generateToken();
    localStorage.setItem('mp_user_token', storedToken);
}
const userToken = ref(storedToken);

const storageName = localStorage.getItem('mp_username');
const currentUser = ref({
    name: storageName || 'Guest',
    sessionId: ''
});

export const useUserStore = defineStore('user', () => {
    const onlineUsers = ref([]);


    // 🟢 1. 启动时：严格从 LocalStorage 读取，默认值只在这里设定一次
    const storageName = localStorage.getItem('mp_username');
    const currentUser = ref({
        name: storageName || 'Guest',
        sessionId: ''
    });

    const bindings = ref(JSON.parse(localStorage.getItem('mp_bindings') || '{}'));

    // 🟢 [新增] 全局状态：控制改名弹窗显示
    const showNameModal = ref(false);

    const isGuest = ref(!storageName);

    // 🟢 [新增] 核心方法：将 SessionID 翻译成名字
    const resolveName = (sessionId, fallbackName) => {
        if (!sessionId) return 'Unknown';
        if (sessionId === 'ADMIN') return 'AUTO_DJ';
        // 如果 ID 是我自己，优先返回我当前输入框里的名字（即时响应）
        if (sessionId === currentUser.value.sessionId) return currentUser.value.name;

        // 否则去在线列表里找
        const u = onlineUsers.value.find(u => u.sessionId === sessionId);
        // 如果用户在线，显示最新名字；如果不在线，显示历史记录里的名字（fallback）
        return u ? u.name : (fallbackName || 'Unknown Agent');
    };

    /**
     * 🟢 2. 初始化用户身份 (来自 /app/user/me)
     * 逻辑：对比服务器认为的名字 (serverName) 和我本地存储的名字
     * 返回：true 表示需要强制同步（改名），false 表示一致
     */
    const initUser = (sessionId, serverName) => {
        currentUser.value.sessionId = sessionId;

        // 如果后端返回的名字和本地不同，说明后端帮我们恢复了老名字，或者名字被占用了被后端改了
        // 这里我们要以后端为准，因为后端做了去重
        if (serverName && serverName !== currentUser.value.name) {
            console.log(`Syncing name from server: ${serverName}`);
            currentUser.value.name = serverName;
            localStorage.setItem('mp_username', serverName);
            // 解锁 Guest
            if(isGuest.value) isGuest.value = false;
        }
        return false; // 不需要再发 rename 了，后端已经处理好了
    };

    const setOnlineUsers = (users) => {
        onlineUsers.value = users;
    };

    const updateBinding = (platform, accountId) => {
        bindings.value[platform] = accountId;
        localStorage.setItem('mp_bindings', JSON.stringify(bindings.value));
    };

    // 🟢 3. 只有这个方法有权修改 LocalStorage
    const saveName = (newName) => {
        if(!newName) return;
        currentUser.value.name = newName;
        localStorage.setItem('mp_username', newName);
        isGuest.value = false;
        // 🟢 [新增] 保存成功后，自动关闭弹窗
        showNameModal.value = false;
    }

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
        userToken
    };
});
