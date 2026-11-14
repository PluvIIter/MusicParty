import { ref } from 'vue';

export const audioCacheStatus = ref('starting');
// 支持分段缓存音源：CDN 需要支持 CORS + Range；不满足时 service worker 会自动回退直连。
const CACHEABLE_PLATFORMS = new Set(['netease', 'qqmusic']);
const PLATFORM_HOSTS = {
    netease: ['.music.126.net', '.music.163.com'],
    qqmusic: ['.qq.com'],
};
const AUDIO_FILE = /^[\w.-]{8,160}\.(flac|mp3|m4a|aac|wav|ogg)$/i;
let initialization;
let selection = null;
const ourWorker = () =>
    navigator.serviceWorker?.controller &&
    new URL(navigator.serviceWorker.controller.scriptURL).pathname === '/audio-cache-sw.js';

export function initializeAudioCache() {
    if (initialization) return initialization;
    if (!window.isSecureContext || !('serviceWorker' in navigator) || !('caches' in window)) {
        audioCacheStatus.value = 'unavailable';
        return Promise.resolve();
    }
    initialization = new Promise((resolve) => {
        const ready = () => {
            if (!ourWorker()) return;
            audioCacheStatus.value = 'ready';
            resolve();
        };
        navigator.serviceWorker.addEventListener('controllerchange', ready);
        navigator.serviceWorker
            .register('/audio-cache-sw.js', { scope: '/', updateViaCache: 'none' })
            .then(ready)
            .catch(() => {
                audioCacheStatus.value = 'unavailable';
                resolve();
            });
        setTimeout(() => {
            if (audioCacheStatus.value !== 'ready') audioCacheStatus.value = 'unavailable';
            resolve();
        }, 1500);
    });
    return initialization;
}

export function cachedAudioSource(music, source) {
    const platform = music?.platform;
    if (!source || !CACHEABLE_PLATFORMS.has(platform)) return source;
    // Becoming ready after audio has already started must not reload the current source.
    if (selection?.source === source && selection?.id === music.id) return selection.result;
    let result = source;
    if (audioCacheStatus.value === 'ready') {
        try {
            const url = new URL(source);
            const hosts = PLATFORM_HOSTS[platform] || [];
            if (
                /^[\w-]{1,100}$/.test(music.id) &&
                url.protocol === 'https:' &&
                !url.port &&
                !url.username &&
                !url.password &&
                hosts.some((host) => url.hostname.endsWith(host)) &&
                AUDIO_FILE.test(url.pathname.split('/').pop())
            ) {
                result = `/__mp_audio_cache/v1/${encodeURIComponent(music.id)}?platform=${encodeURIComponent(platform)}&source=${encodeURIComponent(source)}`;
            }
        } catch {
            /* Preserve native media URLs that cannot be cached. */
        }
    }
    selection = { id: music.id, source, result };
    return result;
}


