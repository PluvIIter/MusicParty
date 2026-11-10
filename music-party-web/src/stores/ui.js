// src/stores/ui.js
import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { STORAGE_KEYS } from '../constants/storage';
import client from '../api/client';

export const useUiStore = defineStore('ui', () => {
    const isLiteMode = ref(false);
    const volume = ref(parseFloat(localStorage.getItem(STORAGE_KEYS.VOLUME) || '0.5'));
    const autoLiteMode = ref(localStorage.getItem(STORAGE_KEYS.AUTO_LITE_MODE) !== 'false'); // 默认 true
    const authorName = ref('ThorNex');
    const backWords = ref('THORNEX');

    // 外观：用户本地偏好，存 localStorage；首次访问跟随系统 prefers-color-scheme。
    const storedTheme = localStorage.getItem(STORAGE_KEYS.THEME);
    const prefersDark = typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches;
    const isDarkMode = ref(storedTheme ? storedTheme === 'dark' : prefersDark);

    const toggleLiteMode = () => {
        isLiteMode.value = !isLiteMode.value;
    };

    const toggleDarkMode = () => {
        isDarkMode.value = !isDarkMode.value;
    };

    const setVolume = (val) => {
        volume.value = Math.max(0, Math.min(1, val));
    };

    const fetchConfig = async () => {
        try {
            const config = await client.get('/api/config');
            authorName.value = config.authorName;
            backWords.value = config.backWords;
        } catch (e) {
            console.error('Failed to fetch config', e);
        }
    };

    // 监听音量变化并持久化
    watch(volume, (newVal) => {
        localStorage.setItem(STORAGE_KEYS.VOLUME, newVal.toString());
    });

    watch(autoLiteMode, (newVal) => {
        localStorage.setItem(STORAGE_KEYS.AUTO_LITE_MODE, newVal.toString());
    });

    // 外观切换：同步 <html class="dark">，并立刻持久化
    watch(
        isDarkMode,
        (dark) => {
            if (typeof document !== 'undefined') {
                document.documentElement.classList.toggle('dark', dark);
            }
            localStorage.setItem(STORAGE_KEYS.THEME, dark ? 'dark' : 'light');
        },
        { immediate: true }
    );

    return {
        isLiteMode,
        toggleLiteMode,
        volume,
        setVolume,
        autoLiteMode,
        authorName,
        backWords,
        fetchConfig,
        isDarkMode,
        toggleDarkMode,
    };
});
