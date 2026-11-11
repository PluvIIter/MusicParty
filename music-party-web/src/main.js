import { createApp } from 'vue';
import { createPinia } from 'pinia';
import './style.css';
import App from './App.vue';
import { initializeAudioCache } from './services/audioCache';
import { APP_VERSION } from './constants/version.js';
import { applyTheme, readThemePreference } from './domain/theme';

window.__APP_VERSION__ = APP_VERSION;

// 尽早应用外观偏好，避免首帧闪白（ui store 挂载后会再同步一次）
try {
    applyTheme(readThemePreference());
} catch {
    /* localStorage / matchMedia 不可用时保持默认浅色 */
}

const pinia = createPinia();
const app = createApp(App);

app.use(pinia);
initializeAudioCache().then(() => app.mount('#app'));
