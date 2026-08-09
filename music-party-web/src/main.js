import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import { APP_VERSION } from './constants/version.js'

window.__APP_VERSION__ = APP_VERSION

const pinia = createPinia()
const app = createApp(App)

app.use(pinia)
app.mount('#app')