import { defineStore } from 'pinia';
import { ref, computed } from 'vue';

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
    const resolveName = (sessionId) => {
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

        const localName = localStorage.getItem('mp_username');

        // A. 本地有名字
        if (localName) {
            // 界面上强制显示本地名字，防止闪烁
            currentUser.value.name = localName;
            isGuest.value = false;

            // 如果服务端名字和本地不一致，告诉调用者需要同步
            // 注意：这里我们不保存 serverName 到本地，因为本地才是真理
            if (serverName && serverName !== localName) {
                return true; // 需要同步
            }
        }
        // B. 本地没名字（第一次来），接受服务端分配的默认名
        else if (serverName) {
            currentUser.value.name = serverName;
            isGuest.value = true;
            // 这种情况下不写入 LocalStorage，让用户保持 "未设置" 状态，直到他主动改名
            // 或者你可以选择写入：localStorage.setItem('mp_username', serverName);
        }

        return false;
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
        resolveName
    };
});