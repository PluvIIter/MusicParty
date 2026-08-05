// src/composables/useAudio.js

import { ref, onMounted, onUnmounted, watch } from 'vue';
import { socketService } from '../services/socket';
import { WS_DEST } from '../constants/api';

// 阈值常量
const ENDED_RESYNC_DELAY = 2500;       // 当前曲播完但服务器未推新曲时，等待多久后主动重同步
const STALL_TIMEOUT = 8000;            // 持续缓冲超时（ms），超时后重载 + 重同步
const POSITION_STATE_INTERVAL = 1000;  // setPositionState 节流间隔（ms）

export function useAudio(audioRef, playerStore) {
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const retryCount = ref(0);
    const isErrorState = ref(false);
    let syncTimer = null;
    let wakeLock = null;
    let endedTimer = null;
    let endedTrackId = null;
    let stallTimer = null;
    let lastPositionStateAt = 0;

    // 请求唤醒锁 (防止 WebSocket 断连)
    const requestWakeLock = async () => {
        if ('wakeLock' in navigator) {
            try {
                wakeLock = await navigator.wakeLock.request('screen');
                console.log('Wake Lock active');
            } catch (err) {
                console.warn('Wake Lock request failed:', err);
            }
        }
    };

    // 释放唤醒锁
    const releaseWakeLock = async () => {
        if (wakeLock !== null) {
            await wakeLock.release();
            wakeLock = null;
        }
    };

    // 更新系统媒体中心 (锁屏控制)
    const updateMediaSession = () => {
        if (!('mediaSession' in navigator) || !playerStore.nowPlaying) return;

        const music = playerStore.nowPlaying.music;

        // 1. 设置元数据
        navigator.mediaSession.metadata = new MediaMetadata({
            title: music.name,
            artist: music.artists.join(' / '),
            artwork: [
                { src: music.coverUrl, sizes: '512x512', type: 'image/png' }
            ]
        });

        // 2. 注册控制事件 (关键：告诉系统我们支持后台控制)
        try {
            navigator.mediaSession.setActionHandler('play', () => playerStore.togglePause());
            navigator.mediaSession.setActionHandler('pause', () => playerStore.togglePause());
            navigator.mediaSession.setActionHandler('previoustrack', null); // 暂不支持上一首
            navigator.mediaSession.setActionHandler('nexttrack', () => playerStore.playNext());
        } catch (e) {
            console.warn('Media Session actions warning:', e);
        }
    };

    // 尝试播放并处理浏览器拦截
    const safePlay = async () => {
        if (!audioRef.value || !playerStore.nowPlaying) return;

        try {
            await audioRef.value.play();
            isErrorState.value = false;
            updateMediaSession();
            requestWakeLock();
        } catch (e) {
            if (e.name === 'NotAllowedError') {
                console.warn("Autoplay blocked. User interaction required.");
            } else if (e.name !== 'AbortError') {
                console.warn("Play failed:", e);
            }
        }
    };

    // === 1. 监听资源加载 (canplay) ===
    const checkAutoPlay = () => {
        if (!playerStore.nowPlaying) return;
        isBuffering.value = false;
        clearTimeout(endedTimer);   // 新曲已到位，取消 ended 重同步等待
        clearTimeout(stallTimer);

        if (playerStore.isPaused) {
            audioRef.value.pause();
        } else {
            safePlay();
        }
    };

    // === 2. 监听后端状态变化 ===
    watch(() => playerStore.isPaused, (newPaused) => {
        if (!audioRef.value) return;
        if (newPaused) {
            audioRef.value.pause();
            navigator.mediaSession.playbackState = 'paused';
            releaseWakeLock();
        } else {
            safePlay();
            navigator.mediaSession.playbackState = 'playing';
        }
    });

    // === 3. 监听切歌 ===
    watch(() => playerStore.nowPlaying?.music?.id, () => {
        // 服务器已推新曲（或清空），取消 ended 重同步等待
        clearTimeout(endedTimer);
        endedTrackId = null;

        // 更新媒体中心信息 (锁屏显示)
        if ('mediaSession' in navigator && playerStore.nowPlaying) {
            const music = playerStore.nowPlaying.music;
            navigator.mediaSession.metadata = new MediaMetadata({
                title: music.name,
                artist: music.artists.join(' / '),
                artwork: [{ src: music.coverUrl, sizes: '512x512', type: 'image/png' }]
            });
        }

        retryCount.value = 0;
        isErrorState.value = false;
        updateMediaSession();
    });

    // === 4. 错误重试机制 ===
    const handleError = () => {
        if (!playerStore.nowPlaying?.music?.url) return;

        if (audioRef.value && audioRef.value.error && audioRef.value.error.code === 20) return;

        isBuffering.value = false;
        if (retryCount.value >= 3) {
            isErrorState.value = true;
            return;
        }

        retryCount.value++;
        console.log(`Retry audio (${retryCount.value})...`);
        setTimeout(() => {
            if (audioRef.value) {
                audioRef.value.load();
            }
        }, 1500);
    };

    // === 5. 音频自然结束兜底：服务器没推新曲就主动重同步 ===
    const handleEnded = () => {
        if (playerStore.isPaused) return; // 服务器暂停态：保持暂停，不动作

        endedTrackId = playerStore.nowPlaying?.music?.id ?? null;
        clearTimeout(endedTimer);
        endedTimer = setTimeout(() => {
            const currentId = playerStore.nowPlaying?.music?.id ?? null;
            // 超时仍停留在同一曲（或无曲目）→ 服务器广播丢失/连接异常
            if (currentId === endedTrackId) {
                console.warn('[Ended] No next track pushed, forcing resync...');
                playerStore.tryReconnect();
                socketService.send(WS_DEST.RESYNC);
            }
        }, ENDED_RESYNC_DELAY);
    };

    // === 6. 持续缓冲看门狗：不再无限干等 ===
    const onWaiting = () => {
        isBuffering.value = true;
        clearTimeout(stallTimer);
        stallTimer = setTimeout(() => {
            if (audioRef.value && !playerStore.isPaused) {
                console.warn('[Stall] Buffer timeout, reloading audio...');
                audioRef.value.load();
                playerStore.tryReconnect();
                socketService.send(WS_DEST.RESYNC);
            }
        }, STALL_TIMEOUT);
    };

    const onPlaying = () => {
        isBuffering.value = false;
        clearTimeout(stallTimer);
    };

    // 页面可见性变化监听
    const handleVisibilityChange = async () => {
        if (document.visibilityState === 'visible') {
            if (!playerStore.isPaused) {
                await requestWakeLock();
            }
            playerStore.tryReconnect();
            // 回到前台无条件重同步，立即对齐服务器进度
            socketService.send(WS_DEST.RESYNC);
        }
    };

    // 网络状态监听
    const handleNetworkChange = () => {
        if (navigator.onLine) {
            console.log('[Network] Back online, checking socket...');
            playerStore.tryReconnect();
            socketService.send(WS_DEST.RESYNC);
        }
    };

    // === 7. 进度条同步 ===
    onMounted(() => {
        document.addEventListener('visibilitychange', handleVisibilityChange);
        window.addEventListener('online', handleNetworkChange);

        syncTimer = setInterval(() => {
            if (!playerStore.nowPlaying) {
                localProgress.value = 0;
                return;
            }

            // 1. 获取理论上的正确进度
            const targetTime = playerStore.getCurrentProgress();

            // 2. 更新 UI 绑定值 (localProgress)
            if (audioRef.value && !audioRef.value.paused) {
                localProgress.value = audioRef.value.currentTime * 1000;
            } else {
                localProgress.value = targetTime;
            }

            // 3. 强行同步逻辑 (纠偏)
            if (audioRef.value && !isBuffering.value && !isErrorState.value) {
                if (playerStore.isPaused) {
                    if (Math.abs(audioRef.value.currentTime * 1000 - targetTime) > 200) {
                        audioRef.value.currentTime = targetTime / 1000;
                    }
                } else {
                    const domTime = audioRef.value.currentTime * 1000;
                    const threshold = document.hidden ? 10000 : 2000;
                    if (Math.abs(domTime - targetTime) > threshold) {
                        if (audioRef.value.readyState >= 2) {
                            console.log(`[Sync] Correcting time (${document.hidden ? 'bg' : 'fg'}): ${domTime} -> ${targetTime}`);
                            audioRef.value.currentTime = targetTime / 1000;
                        }
                    }
                }
            }

            // 4. 锁屏/通知栏真实进度（节流，避免每 200ms 都调用）
            const now = Date.now();
            if (now - lastPositionStateAt >= POSITION_STATE_INTERVAL
                && 'mediaSession' in navigator && navigator.mediaSession.setPositionState) {
                lastPositionStateAt = now;
                try {
                    navigator.mediaSession.setPositionState({
                        duration: playerStore.nowPlaying.music.duration / 1000,
                        playbackRate: playerStore.isPaused ? 0 : 1,
                        position: playerStore.localProgress / 1000,
                    });
                } catch (e) {
                    // 某些状态（如无有效时长）会抛异常，忽略
                }
            }
        }, 200);
    });

    onUnmounted(() => {
        document.removeEventListener('visibilitychange', handleVisibilityChange);
        window.removeEventListener('online', handleNetworkChange);
        clearInterval(syncTimer);
        clearTimeout(endedTimer);
        clearTimeout(stallTimer);
        releaseWakeLock();
    });

    return {
        localProgress,
        isBuffering,
        isErrorState,
        retryCount,
        handleError,
        checkAutoPlay,
        handleEnded,
        onWaiting,
        onPlaying
    };
}
