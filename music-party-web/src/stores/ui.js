// src/stores/ui.js
import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';

export const useUiStore = defineStore('ui', () => {
    const isLiteMode = ref(false);
    const volume = ref(parseFloat(localStorage.getItem(STORAGE_KEYS.VOLUME) || '0.5'));
    const autoLiteMode = ref(localStorage.getItem('mp_auto_lite_mode') !== 'false'); // 默认 true

    const toggleLiteMode = () => {
        isLiteMode.value = !isLiteMode.value;
    };

    const setVolume = (val) => {
        volume.value = Math.max(0, Math.min(1, val));
    };

    // 监听音量变化并持久化
    watch(volume, (newVal) => {
        localStorage.setItem(STORAGE_KEYS.VOLUME, newVal.toString());
    });

    watch(autoLiteMode, (newVal) => {
        localStorage.setItem('mp_auto_lite_mode', newVal.toString());
    });

    return {
        isLiteMode,
        toggleLiteMode,
        volume,
        setVolume,
        autoLiteMode
    };
});
