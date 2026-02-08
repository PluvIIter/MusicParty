import { useToastStore } from '../stores/toast';

export function useToast() {
    const store = useToastStore();

    // 保持 API 兼容性
    // register 不再需要，因为 store 是全局的
    const register = (instance) => {
        console.warn('useToast: register() is deprecated. ToastNotification uses Pinia store now.');
    };

    const show = (options) => store.add(options);
    const success = (message) => store.success(message);
    const error = (message) => store.error(message);
    const info = (message) => store.info(message);

    return {
        register,
        show,
        success,
        error,
        info
    };
}