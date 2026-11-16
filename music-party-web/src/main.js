import { createApp } from 'vue';
import { createPinia } from 'pinia';
import './fonts.css';
import './style.css';
import App from './App.vue';
import { APP_VERSION } from './constants/version.js';
import { applyTheme, readThemePreference } from './domain/theme';

window.__APP_VERSION__ = APP_VERSION;

// 旧的“本机音频分段缓存”已下线：注销残留的 Service Worker，并清掉它留下的分片缓存。
// 音频统一使用音源直链（或服务端票据），不再改写 <audio src>。
const LEGACY_WORKER = '/audio-cache-sw.js';
const LEGACY_CACHES = ['mp-audio-chunks-v1', 'mp-audio-metadata-v1'];

function removeLegacyAudioCache() {
    try {
        const registrations = navigator.serviceWorker?.getRegistrations?.();
        registrations
            ?.then((items) =>
                items.forEach((registration) => {
                    const scripts = [registration.active, registration.installing, registration.waiting]
                        .map((worker) => worker?.scriptURL || '');
                    if (scripts.some((script) => script.endsWith(LEGACY_WORKER))) {
                        registration.unregister().catch(() => {});
                    }
                })
            )
            .catch(() => {});
    } catch {
        /* 浏览器不支持 Service Worker 时无残留可清。 */
    }
    try {
        if (typeof caches === 'undefined') return;
        for (const name of LEGACY_CACHES) caches.delete(name).catch(() => {});
    } catch {
        /* CacheStorage 不可用时忽略。 */
    }
}

// 尽快应用外观偏好，避免首帧闪白（ui store 挂载后会再同步一次）
try {
    applyTheme(readThemePreference());
} catch {
    /* localStorage / matchMedia 不可用时保持默认浅色 */
}

const pinia = createPinia();
const app = createApp(App);

app.use(pinia);
removeLegacyAudioCache();
app.mount('#app');
