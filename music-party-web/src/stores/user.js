import { defineStore } from 'pinia';
import { ref } from 'vue';
import axios from 'axios';

export const useUserStore = defineStore('user', () => {
    const onlineUsers = ref([]);
    //优先从 LocalStorage 读取上次改过的名字，如果没有则默认为 Guest
    const savedName = localStorage.getItem('mp_username');
    const currentUser = ref({ 
        name: savedName || 'Guest', 
        sessionId: '' 
    });
    
    // 从 LocalStorage 读取绑定信息
    const bindings = ref(JSON.parse(localStorage.getItem('mp_bindings') || '{}'));

    // 🟢 新增：被 PlayerStore 调用，用于确立“我”的身份
    const initUser = (sessionId, name) => {
        currentUser.value.sessionId = sessionId;
        if(name) {
            currentUser.value.name = name;
            saveName(name); // 确保本地存储也是同步的
        }
    };

    const setOnlineUsers = (users) => {
        onlineUsers.value = users;
    };

    const updateBinding = (platform, accountId) => {
        bindings.value[platform] = accountId;
        localStorage.setItem('mp_bindings', JSON.stringify(bindings.value));
    };
	
	// 🟢 新增：保存昵称的方法
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