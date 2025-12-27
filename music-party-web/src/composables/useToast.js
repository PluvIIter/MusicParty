import { ref } from 'vue';

// 全局单例 ref，用于持有 Toast 组件的实例
const toastRef = ref(null);

export function useToast() {
    // 1. 注册组件实例 (在 App.vue 中调用)
    const register = (instance) => {
        toastRef.value = instance;
    };

    // 2. 核心方法：显示提示
    const show = ({ title, message, type = 'success', duration = 1500 }) => {
        if (toastRef.value) {
            toastRef.value.add({ title, message, type, duration });
        } else {
            console.warn('Toast component not registered!');
        }
    };

    const success = (message) => show({ title: 'SUCCESS', message, type: 'success' });
    const error = (message) => show({ title: 'ERROR', message, type: 'error' });
    const info = (message) => show({ title: 'INFO', message, type: 'info' });

    return {
        register,
        show,
        success,
        error,
        info
    };
}