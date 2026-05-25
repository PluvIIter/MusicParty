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
    const isFairShuffle = ref(true);
    const allowOfflineShuffle = ref(false);
    const isPauseLocked = ref(false);
    const isSkipLocked = ref(false);
    const isShuffleLocked = ref(false);
    const lyricText = ref('');
    const connected = ref(false);
    const isLoading = ref(false);
    const streamListenerCount = ref(0);
    const streamActive = ref(false);
    const lastControlTime = ref(0);
    const remotePosition = ref(0);
    const lastSyncTime = ref(0);
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const isErrorState = ref(false);

    // 投票切歌相关
    const isVoteSkipEnabled = ref(false);
    const voteSkipThreshold = ref(0.5);
    const voteSkipWaitTime = ref(15);
    const currentVotes = ref(0);
    const eligibleUsers = ref(0);

    const config = ref({
        maxQueueSize: 1000,
        maxHistorySize: 50,
        maxUserSongs: 100,
        maxPlaylistImportSize: 100,
        maxChatHistorySize: 1000,
        minChatIntervalMs: 1000,
        maxChatMessageLength: 200,
        neteaseEnabled: true,
        bilibiliEnabled: true,
        voteSkipEnabled: false,
        voteSkipThreshold: 0.5,
        voteSkipWaitTime: 15
    });

    const userStore = useUserStore();
    const LOCAL_COOLDOWN = 500; // 稍微调低一点冷却时间提升手感

    // === 2. Logic ===
    const getCurrentProgress = () => {
        if (!nowPlaying.value) return 0;
        if (isPaused.value) {
            return remotePosition.value;
        } else {
            // 本地简单的推算：服务器给的进度 + (当前时间 -收到包的时间)
            return remotePosition.value + (Date.now() - lastSyncTime.value);
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

    const syncState = (state) => {
        nowPlaying.value = state.nowPlaying;
        queue.value = state.queue;
        isPaused.value = state.isPaused;
        isShuffle.value = state.isShuffle;
        isFairShuffle.value = state.isFairShuffle !== undefined ? state.isFairShuffle : true;
        allowOfflineShuffle.value = state.allowOfflineShuffle || false;
        isPauseLocked.value = state.isPauseLocked || false;
        isSkipLocked.value = state.isSkipLocked || false;
        isShuffleLocked.value = state.isShuffleLocked || false;
        isLoading.value = state.isLoading || false;
        streamListenerCount.value = state.streamListenerCount || 0;
        streamActive.value = state.isStreamEnabled || false;

        // 投票状态同步
        isVoteSkipEnabled.value = state.isVoteSkipEnabled || false;
        voteSkipThreshold.value = state.voteSkipThreshold || 0.5;
        voteSkipWaitTime.value = state.voteSkipWaitTime || 15;
        currentVotes.value = state.currentVotes || 0;
        eligibleUsers.value = state.eligibleUsers || 0;

        // 记录服务器发来的进度和收到包的时间
        if (state.nowPlaying) {
            remotePosition.value = state.nowPlaying.currentPosition;
            lastSyncTime.value = Date.now();
        } else {
            remotePosition.value = 0;
        }

        if (state.onlineUsers) {
            userStore.setOnlineUsers(state.onlineUsers);
        }

        if (state.config) {
            config.value = { ...state.config };
        }
    };

    const connect = () => {
        const authHeaders = {
            'user-name': localStorage.getItem(STORAGE_KEYS.USERNAME) || '游客',
            'user-token': userStore.userToken,
            'room-password': localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD) || ''
        };

        // 使用抽离出的订阅配置
        const subscriptions = createSocketSubscriptions();

        // 补充 UserMe 的特殊处理 (因为它需要用到 renameUser，如果放在 socketHandler 会导致循环依赖)
        subscriptions[WS_DEST.USER_ME] = (me) => {
            // me: { token, sessionId, name, isGuest }
            userStore.initUser(me.sessionId, me.name, me.isGuest);
        };

        subscriptions[WS_DEST.USER_ME_UPDATE] = (me) => {
            userStore.initUser(me.sessionId, me.name, me.isGuest);
        };

        const callbacks = createSocketCallbacks();

        socketService.connect(authHeaders, callbacks, subscriptions);
    };

    const tryReconnect = () => {
        if (!connected.value) {
            socketService.forceReconnect();
        }
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
        // userStore.saveName(newName) removed; relying on backend sync
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
        nowPlaying, queue, isPaused, isShuffle, isFairShuffle, allowOfflineShuffle, config,
        isPauseLocked, isSkipLocked, isShuffleLocked, connected, isLoading, lyricText,
        localProgress, isBuffering, isErrorState, streamListenerCount, streamActive,
        isVoteSkipEnabled, voteSkipThreshold, voteSkipWaitTime, currentVotes, eligibleUsers,
        connect, tryReconnect, getCurrentProgress, syncState, // 导出 syncState
        playNext, togglePause, toggleShuffle,
        enqueue, enqueuePlaylist, topSong, removeSong,
        bindAccount, renameUser, sendChatMessage, sendLike
    };
});