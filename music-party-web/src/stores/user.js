// File Path: music-party-web\src\stores\user.js

import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useUserStore = defineStore('user', () => {
    const onlineUsers = ref([]);

    const savedName = localStorage.getItem('mp_username');
    const currentUser = ref({
        name: savedName || 'Guest',
        sessionId: ''
    });

    const bindings = ref(JSON.parse(localStorage.getItem('mp_bindings') || '{}'));

    // 🟢 修改：initUser 现在返回一个 boolean，表示是否需要向后端发送更名请求
    const initUser = (sessionId, serverName) => {
        currentUser.value.sessionId = sessionId;

        const localName = localStorage.getItem('mp_username');
        let needsSync = false;

        if (localName) {
            // 本地有名字
            currentUser.value.name = localName;

            // 核心逻辑：如果本地名字和服务器返回的名字不一致（且服务器名字不是空的），标记需要同步
            if (serverName && localName !== serverName) {
                needsSync = true;
            }
        } else if (serverName) {
            // 本地没名字，接受服务器的名字
            currentUser.value.name = serverName;
        }

        return needsSync;
    };

    const setOnlineUsers = (users) => {
        onlineUsers.value = users;
    };

    const updateBinding = (platform, accountId) => {
        bindings.value[platform] = accountId;
        localStorage.setItem('mp_bindings', JSON.stringify(bindings.value));
    };

    const saveName = (newName) => {
        currentUser.value.name = newName;
        localStorage.setItem('mp_username', newName);
    }

    return {
        onlineUsers,
        currentUser,
        bindings,
        initUser,
        setOnlineUsers,
        updateBinding,
        saveName
    };
});