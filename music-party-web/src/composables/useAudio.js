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

    // === 核心：尝试播放并处理浏览器拦截 ===
    const safePlay = async () => {
        if (!audioRef.value || !playerStore.nowPlaying) return;

        try {
            await audioRef.value.play();
            isErrorState.value = false;
        } catch (e) {
            // NotAllowedError 是浏览器由于缺乏用户交互而拦截
            if (e.name === 'NotAllowedError') {
                error('自动播放被拦截，请点击播放按钮');
                // 这里我们不改变 store 的状态，因为后端确实在播放
                // 只是本地没声音，需要用户手动点一下 UI 上的播放键来“解锁”
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
            // 修复点：如果不暂停，就应该播放！
            // 延迟一点点，给浏览器喘息时间
            setTimeout(() => safePlay(), 50);
        }
    };

    // === 2. 监听后端状态变化 ===
    watch(() => playerStore.isPaused, (newPaused) => {
        if (!audioRef.value) return;
        if (newPaused) {
            audioRef.value.pause();
        } else {
            safePlay();
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

    // === 5. 进度条同步 ===
    onMounted(() => {
        syncTimer = setInterval(() => {
            if (!playerStore.nowPlaying) {
                localProgress.value = 0;
                return;
            }
            // 计算理论进度
            const backendTime = playerStore.getCurrentProgress();

            // 如果后端在播放，前端却没动（通常是还没加载完，或者被拦截了）
            // 我们依然优先显示 backendTime，让 UI 看起来是对齐的
            localProgress.value = backendTime;

            // 强行同步逻辑 (纠偏)
            if (audioRef.value && !playerStore.isPaused && !isBuffering.value && !isErrorState.value) {
                const domTime = audioRef.value.currentTime * 1000;
                // 如果偏差超过 2 秒，强制跳转
                if (Math.abs(domTime - backendTime) > 2000) {
                    // 只有在音频就绪时才跳转
                    if (audioRef.value.readyState >= 2) {
                        audioRef.value.currentTime = backendTime / 1000;
                    }
                }
            }
        }, 500);
    });

    onUnmounted(() => clearInterval(syncTimer));

    return {
        localProgress,
        isBuffering,
        isErrorState,
        retryCount,
        handleError,
        checkAutoPlay
    };
}