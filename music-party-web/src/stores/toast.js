import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useToastStore = defineStore('toast', () => {
    const toasts = ref([]);
    let idCounter = 0;

    const add = (options) => {
        const id = idCounter++;
        const toast = {
            id,
            title: options.title || 'SYSTEM NOTICE',
            message: options.message,
            type: options.type || 'info', // success, error, info, warning
            duration: options.duration || 3000
        };

        toasts.value.push(toast);

        if (toast.duration > 0) {
            setTimeout(() => {
                remove(id);
            }, toast.duration);
        }
    };

    const remove = (id) => {
        const index = toasts.value.findIndex(t => t.id === id);
        if (index !== -1) toasts.value.splice(index, 1);
    };

    const success = (message, title = 'SUCCESS') => add({ title, message, type: 'success' });
    const error = (message, title = 'ERROR') => add({ title, message, type: 'error' });
    const info = (message, title = 'INFO') => add({ title, message, type: 'info' });
    const warning = (message, title = 'WARNING') => add({ title, message, type: 'warning' });

    return {
        toasts,
        add,
        remove,
        success,
        error,
        info,
        warning
    };
});
