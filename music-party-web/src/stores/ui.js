// src/stores/ui.js
import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import { THEMES } from '../constants/themes';
import { applyTheme, readThemePreference } from '../domain/theme';
import { STORAGE_KEYS } from '../constants/storage';
import client from '../api/client';

export const useUiStore = defineStore('ui', () => {
    const isLiteMode = ref(false);
    const volume = ref(parseFloat(localStorage.getItem(STORAGE_KEYS.VOLUME) || '0.5'));
    const autoLiteMode = ref(localStorage.getItem(STORAGE_KEYS.AUTO_LITE_MODE) !== 'false'); // 默认 true
    const authorName = ref('ThorNex');
    const backWords = ref('THORNEX');

    const themeId = ref(readThemePreference());
    const isDarkMode = computed(() => THEMES.find((theme) => theme.id === themeId.value)?.mode === 'dark');
    const setTheme = (id) => {
        if (THEMES.some((theme) => theme.id === id)) themeId.value = id;
    };

    const toggleLiteMode = () => {
        isLiteMode.value = !isLiteMode.value;
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

    watch(
        themeId,
        (id) => {
            applyTheme(id);
            try {
                localStorage.setItem(STORAGE_KEYS.THEME, id);
            } catch {
                /* Keep the applied theme even if the browser refuses persistence. */
            }
        },
        { immediate: true, flush: 'sync' }
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
        themeId,
        setTheme,
    };
});
