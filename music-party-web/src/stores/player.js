// src/stores/player.js

import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { useUserStore } from './user';
import { socketService } from '../services/socket';
import { createSocketSubscriptions, createSocketCallbacks } from '../services/socketHandler'; // 引入新文件
import { musicApi } from '../api/music';
import { WS_DEST } from '../constants/api';
import { STORAGE_KEYS } from '../constants/keys';

export const usePlayerStore = defineStore('player', () => {
    // === 1. State ===
    const nowPlaying = ref(null);
    const queue = ref([]);
    const isPaused = ref(false);
    const isShuffle = ref(false);
    const pauseTimeMillis = ref(0);
    const serverTimeOffset = ref(0);
    const lyricText = ref('');
    const connected = ref(false);
    const isLoading = ref(false);
    const lastControlTime = ref(0);

    const userStore = useUserStore();
    const LOCAL_COOLDOWN = 500; // 稍微调低一点冷却时间提升手感

    // === 2. Logic ===
    const getCurrentProgress = () => {
        if (!nowPlaying.value) return 0;
        const effectiveStartTime = nowPlaying.value.startTimeMillis;
        if (isPaused.value) {
            return pauseTimeMillis.value > 0
                ? Math.max(0, pauseTimeMillis.value - effectiveStartTime)
                : 0;
        } else {
            const currentServerTime = Date.now() + serverTimeOffset.value;
            return Math.max(0, currentServerTime - effectiveStartTime);
        }
    };

    const requireAuth = () => {
        if (userStore.isGuest) {
            userStore.showNameModal = true;
            return false;
        }
        return true;
    };

    const checkCooldown = () => {
        const now = Date.now();
        if (now - lastControlTime.value < LOCAL_COOLDOWN) {
            // 这里可以不再弹 Toast 报错，而是静默失败，避免刷屏
            return false;
        }
        lastControlTime.value = now;
        return true;
    };

    // === 3. Actions ===

    // [新增] 纯粹的状态同步 Action
    const syncState = (state) => {
        nowPlaying.value = state.nowPlaying;
        queue.value = state.queue;
        isPaused.value = state.isPaused;
        isShuffle.value = state.isShuffle;
        pauseTimeMillis.value = state.pauseTimeMillis || 0;
        isLoading.value = state.isLoading || false;

        if (state.serverTimestamp) {
            serverTimeOffset.value = state.serverTimestamp - Date.now();
        }
        if (state.onlineUsers) {
            userStore.setOnlineUsers(state.onlineUsers);
        }
    };

    const connect = () => {
        const authHeaders = {
            'user-name': localStorage.getItem(STORAGE_KEYS.USERNAME) || 'Guest',
            'user-token': userStore.userToken,
            'room-password': localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD) || ''
        };

        // 使用抽离出的订阅配置
        const subscriptions = createSocketSubscriptions();

        // 补充 UserMe 的特殊处理 (因为它需要用到 renameUser，如果放在 socketHandler 会导致循环依赖)
        subscriptions[WS_DEST.USER_ME] = (me) => {
            const needsSync = userStore.initUser(me.sessionId, me.name);
            if (needsSync) renameUser(userStore.currentUser.name);
        };

        const callbacks = createSocketCallbacks();

        socketService.connect(authHeaders, callbacks, subscriptions);
    };

    // --- 指令发送 ---
    const playNext = () => requireAuth() && checkCooldown() && socketService.send(WS_DEST.PLAYER_NEXT);
    const togglePause = () => requireAuth() && checkCooldown() && socketService.send(WS_DEST.PLAYER_PAUSE);
    const toggleShuffle = () => requireAuth() && checkCooldown() && socketService.send(WS_DEST.PLAYER_SHUFFLE);

    const enqueue = (platform, musicId) => requireAuth() && socketService.send(WS_DEST.ENQUEUE, { platform, musicId });
    const enqueuePlaylist = (platform, playlistId) => requireAuth() && socketService.send(WS_DEST.ENQUEUE_PLAYLIST, { platform, playlistId });
    const topSong = (queueId) => requireAuth() && socketService.send(WS_DEST.QUEUE_TOP, { queueId });
    const removeSong = (queueId) => requireAuth() && socketService.send(WS_DEST.QUEUE_REMOVE, { queueId });

    const bindAccount = (platform, accountId) => {
        socketService.send(WS_DEST.USER_BIND, { platform, accountId });
        userStore.updateBinding(platform, accountId);
    };

    const renameUser = (newName) => {
        socketService.send(WS_DEST.USER_RENAME, { newName });
        userStore.saveName(newName);
    };

    const sendLike = () => {
        if (requireAuth()) socketService.send(WS_DEST.PLAYER_LIKE);
    };

    const sendChatMessage = (content) => {
        if (requireAuth()) socketService.send(WS_DEST.CHAT_SEND, { content });
    };

    // 歌词监听
    watch(() => nowPlaying.value?.music?.id, async (newId) => {
        lyricText.value = '';
        if (!newId) return;
        try {
            const platform = nowPlaying.value.music.platform;
            const data = await musicApi.getLyric(platform, newId);
            lyricText.value = data || '';
        } catch (e) {
            console.error("Lyrics Error", e);
        }
    });

    return {
        nowPlaying, queue, isPaused, isShuffle, connected, isLoading, lyricText,
        connect, getCurrentProgress, syncState, // 导出 syncState
        playNext, togglePause, toggleShuffle,
        enqueue, enqueuePlaylist, topSong, removeSong,
        bindAccount, renameUser, sendChatMessage, sendLike
    };
});