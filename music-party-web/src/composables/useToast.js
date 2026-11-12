import { useToastStore } from '../stores/toast';

export function useToast() {
    const store = useToastStore();

    const show = (options) => store.add(options);
    const success = (message) => store.success(message);
    const error = (message) => store.error(message);
    const info = (message) => store.info(message);
    const warning = (message) => store.warning(message);

    return {
        show,
        success,
        error,
        info,
        warning,
    };
}
