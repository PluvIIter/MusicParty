// src/composables/useAudio.js

import { ref, onMounted, onUnmounted, watch } from 'vue';
import { useToast } from './useToast';

export function useAudio(audioRef, playerStore) {
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const retryCount = ref(0);
    const isErrorState = ref(false);
    const { info, error, success } = useToast();
    let syncTimer = null;
    let wakeLock = null;

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
        // 这样点击锁屏的下一首/暂停，会通过 WebSocket 发送给服务器
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
            // NotAllowedError 是浏览器由于缺乏用户交互而拦截
            if (e.name === 'NotAllowedError') {
                console.warn("Autoplay blocked. User interaction required.");
            } else if (e.name !== 'AbortError') {
                console.warn("Play failed:", e);
            }
        }
    };

    // === 1. 监听资源加载 (canplay) ===
    // 这是修复你问题的关键：音频加载就绪后，主动判断是否需要播放
    const checkAutoPlay = () => {
        if (!playerStore.nowPlaying) return;
        isBuffering.value = false;

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
        // 切歌会导致 src 变化，自动触发 load -> canplay -> checkAutoPlay
        // 所以这里不需要手动 call play
        updateMediaSession();
    });

    // === 4. 错误重试机制 ===
    const handleError = () => {
        if (!playerStore.nowPlaying?.music?.url) return;

        // 忽略由切换 src 导致的中断错误
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
                // load 完会触发 canplay，进而触发 checkAutoPlay
            }
        }, 1500);
    };

    // 页面可见性变化监听
    // 当重新回到前台时，如果发现 WebSocket 断了，应该自动重连
    // 这里主要处理 Wake Lock 的重新获取
    const handleVisibilityChange = async () => {
        if (document.visibilityState === 'visible' && !playerStore.isPaused) {
            await requestWakeLock();
        }
    };

    // === 5. 进度条同步 ===
    onMounted(() => {
        document.addEventListener('visibilitychange', handleVisibilityChange);

        syncTimer = setInterval(() => {
            if (!playerStore.nowPlaying) {
                localProgress.value = 0;
                return;
            }

            // 1. 获取理论上的正确进度
            const targetTime = playerStore.getCurrentProgress();

            // 2. 更新 UI 绑定值 (localProgress)
            // 如果音频正在播放，直接用 audio.currentTime 作为 UI 显示源，这样最平滑
            // 如果没在播（缓冲中/暂停），用 targetTime
            if (audioRef.value && !audioRef.value.paused) {
                localProgress.value = audioRef.value.currentTime * 1000;
            } else {
                localProgress.value = targetTime;
            }

            // 3. 强行同步逻辑 (纠偏)
            if (audioRef.value && !isBuffering.value && !isErrorState.value) {
                // 如果是暂停状态，强制对齐
                if (playerStore.isPaused) {
                    // 避免重复赋值导致杂音
                    if (Math.abs(audioRef.value.currentTime * 1000 - targetTime) > 200) {
                        audioRef.value.currentTime = targetTime / 1000;
                    }
                }
                // 如果是播放状态，只有偏差过大才对齐
                else {
                    const domTime = audioRef.value.currentTime * 1000;
                    // 允许 2 秒的误差，因为网络延迟和 JS 执行时间
                    if (Math.abs(domTime - targetTime) > 2000) {
                        if (audioRef.value.readyState >= 2) {
                            console.log(`[Sync] Correcting time: ${domTime} -> ${targetTime}`);
                            audioRef.value.currentTime = targetTime / 1000;
                        }
                    }
                }
            }
        }, 500);
    });

    onUnmounted(() => {
        document.removeEventListener('visibilitychange', handleVisibilityChange);
        clearInterval(syncTimer);
        releaseWakeLock();
    });

    return {
        localProgress,
        isBuffering,
        isErrorState,
        retryCount,
        handleError,
        checkAutoPlay
    };
}