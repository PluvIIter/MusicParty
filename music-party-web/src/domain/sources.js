export const resourceKey = (track) => JSON.stringify([track?.platform ?? '', String(track?.id ?? '')]);

export function safeExternalUrl(value) {
    if (!value) return '';
    try {
        const url = new URL(value);
        return ['http:', 'https:'].includes(url.protocol) ? url.href : '';
    } catch {
        return '';
    }
}

export function openExternal(value) {
    const url = safeExternalUrl(value);
    if (url) window.open(url, '_blank', 'noopener,noreferrer');
}

export function createRequestScope() {
    let generation = 0;
    let controller;
    return {
        cancel() {
            generation++;
            controller?.abort();
        },
        start() {
            controller?.abort();
            controller = new AbortController();
            const current = ++generation;
            return { signal: controller.signal, current: () => current === generation };
        },
    };
}

export const isCanceled = (error) => error?.code === 'ERR_CANCELED' || error?.name === 'AbortError';
