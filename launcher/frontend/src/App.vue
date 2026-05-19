<script setup>
import { ref, onMounted, reactive } from 'vue';
import { LoadConfig, SaveConfig, StartServices, StopServices } from './wailsjs/go/main/App';
import { EventsOn } from './wailsjs/runtime';

const config = reactive({
  serverIp: '0.0.0.0',
  serverPort: '8848',
  adminPassword: 'admin',
  authorName: 'ThorNex',
  backWords: 'THORNEX',
  neteaseCookie: '',
  neteaseQuality: 'exhigh',
  biliSessData: '',
  queueMaxSize: 1000,
  queueHistorySize: 50,
  queueMaxUserSongs: 100,
  maxPlaylistImportSize: 100,
  chatMaxHistorySize: 1000,
  chatMinIntervalMs: 1000,
  chatMaxMessageLength: 200,
  cacheMaxSize: '1GB',
  authRateLimitEnabled: true,
  authMaxAttempts: 5,
  authWindowSeconds: 60,
  authBlockDuration: 300
});

const isRunning = ref(false);
const logs = ref([]);
const logContainer = ref(null);
const activeTab = ref('basic');

onMounted(async () => {
  const cfg = await LoadConfig();
  if (cfg) Object.assign(config, cfg);
  
  EventsOn("log", (msg) => {
    logs.value.push({
      id: Date.now() + Math.random(),
      time: new Date().toLocaleTimeString(),
      text: msg
    });
    if (logs.value.length > 1000) logs.value.shift();
    scrollToBottom();
  });
});

const scrollToBottom = () => {
  setTimeout(() => {
    if (logContainer.value) {
      logContainer.value.scrollTop = logContainer.value.scrollHeight;
    }
  }, 50);
};

const handleStart = async () => {
  await SaveConfig(JSON.parse(JSON.stringify(config)));
  isRunning.value = true;
  logs.value = []; // 清空之前的日志
  await StartServices();
};

const handleStop = async () => {
  await StopServices();
  isRunning.value = false;
};

const copyLogs = () => {
  const text = logs.value.map(l => `[${l.time}] ${l.text}`).join('\n');
  navigator.clipboard.writeText(text);
  alert('日志已复制到剪贴板');
};
</script>

<template>
  <div class="h-screen flex flex-col p-6 space-y-4 bg-medical-50 text-medical-900">
    <!-- Header -->
    <div class="flex justify-between items-end border-b-2 border-medical-900 pb-4">
      <div>
        <h1 class="text-3xl font-black tracking-tighter">MUSIC PARTY</h1>
        <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-widest">> DEPLOYMENT TERMINAL v1.1</p>
      </div>
      <div class="flex items-center gap-6">
        <div class="flex flex-col items-end">
          <span class="text-[9px] font-bold text-medical-400 uppercase">系统状态 / SYSTEM STATUS</span>
          <span :class="isRunning ? 'text-green-600' : 'text-red-600'" class="text-xs font-black font-mono">
            {{ isRunning ? '● 运行中 / RUNNING' : '○ 待机 / STANDBY' }}
          </span>
        </div>
      </div>
    </div>

    <!-- Main Content -->
    <div class="flex-1 min-h-0 flex gap-6">
      <!-- Left: Settings -->
      <div class="w-96 flex flex-col gap-4">
        <!-- Tabs -->
        <div class="flex border-b border-medical-200">
          <button 
            v-for="tab in ['basic', 'api', 'advanced']" 
            :key="tab"
            @click="activeTab = tab"
            :class="activeTab === tab ? 'border-b-2 border-medical-900 font-black' : 'text-medical-400 font-bold'"
            class="px-4 py-2 text-xs uppercase transition-all"
          >
            {{ tab === 'basic' ? '基础配置' : tab === 'api' ? '接口配置' : '高级设置' }}
          </button>
        </div>

        <div class="flex-1 overflow-y-auto pr-2 space-y-4">
          <!-- Basic Settings -->
          <div v-if="activeTab === 'basic'" class="space-y-4 animate-in fade-in slide-in-from-left-2">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">绑定 IP / BIND IP</label>
                <input v-model="config.serverIp" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">端口 / PORT</label>
                <input v-model="config.serverPort" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">管理员密码 / ADMIN PASSWORD</label>
                <input v-model="config.adminPassword" type="text" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
            </div>
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">作者名称 / AUTHOR NAME</label>
                <input v-model="config.authorName" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">背景文字 / BACK WORDS</label>
                <input v-model="config.backWords" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
            </div>
          </div>

          <!-- API Settings -->
          <div v-if="activeTab === 'api'" class="space-y-4 animate-in fade-in slide-in-from-left-2">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">网易云 COOKIE / NETEASE COOKIE</label>
                <textarea v-model="config.neteaseCookie" rows="4" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none focus:border-medical-900 resize-none"></textarea>
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">音质选择 / QUALITY</label>
                <select v-model="config.neteaseQuality" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900">
                  <option value="standard">标准 / Standard</option>
                  <option value="higher">较高 / Higher</option>
                  <option value="exhigh">极高 / Exhigh</option>
                  <option value="lossless">无损 / Lossless</option>
                  <option value="hires">Hi-Res</option>
                </select>
              </div>
            </div>
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">B站 SESSDATA / BILI SESSDATA</label>
                <input v-model="config.biliSessData" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none focus:border-medical-900" />
              </div>
            </div>
          </div>

          <!-- Advanced Settings -->
          <div v-if="activeTab === 'advanced'" class="space-y-4 animate-in fade-in slide-in-from-left-2">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">队列与播放 / QUEUE & PLAYER</h3>
              <div class="grid grid-cols-2 gap-3">
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">最大队列</label>
                  <input v-model.number="config.queueMaxSize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">历史容量</label>
                  <input v-model.number="config.queueHistorySize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">单人限额</label>
                  <input v-model.number="config.queueMaxUserSongs" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">歌单上限</label>
                  <input v-model.number="config.maxPlaylistImportSize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
              </div>
            </div>

            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">聊天设置 / CHAT</h3>
              <div class="grid grid-cols-2 gap-3">
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">聊天历史</label>
                  <input v-model.number="config.chatMaxHistorySize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">发言间隔(ms)</label>
                  <input v-model.number="config.chatMinIntervalMs" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
              </div>
            </div>

            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">缓存与安全 / CACHE & SECURITY</h3>
              <div class="space-y-1">
                <label class="text-[9px] font-bold text-medical-400 uppercase">缓存上限 (e.g. 1GB)</label>
                <input v-model="config.cacheMaxSize" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
              </div>
              <div class="flex items-center gap-2 pt-2">
                <input type="checkbox" v-model="config.authRateLimitEnabled" id="rateLimit" class="w-4 h-4 accent-medical-900" />
                <label for="rateLimit" class="text-[10px] font-bold text-medical-600 uppercase">开启登录限流 / RATE LIMIT</label>
              </div>
            </div>
          </div>
        </div>

        <!-- Actions -->
        <div class="flex gap-2">
          <button 
            v-if="!isRunning"
            @click="handleStart"
            class="flex-1 py-4 bg-medical-900 text-white font-black text-xl hover:bg-black transition-all chamfer-br active:scale-95 shadow-lg shadow-medical-200"
          >
            启动服务器 / START
          </button>
          <button 
            v-else
            @click="handleStop"
            class="flex-1 py-4 bg-red-600 text-white font-black text-xl hover:bg-red-700 transition-all chamfer-br active:scale-95 shadow-lg shadow-red-100"
          >
            停止运行 / STOP
          </button>
        </div>
      </div>

      <!-- Right: Terminal -->
      <div class="flex-1 flex flex-col bg-medical-900 chamfer-br p-4 overflow-hidden border-2 border-medical-900 shadow-inner">
        <div class="flex justify-between items-center mb-2 border-b border-white/10 pb-2">
          <div class="flex items-center gap-4">
            <span class="text-[10px] font-mono text-white/40 uppercase tracking-widest">执行日志 / EXECUTION LOG</span>
            <button @click="copyLogs" class="text-[10px] font-bold text-accent hover:underline decoration-accent-500 underline-offset-4">复制日志 / COPY</button>
          </div>
          <span class="text-[10px] font-mono text-accent animate-pulse">SYSTEM_READY_</span>
        </div>
        <div ref="logContainer" class="flex-1 overflow-y-auto space-y-1 terminal-scroll">
          <div v-for="log in logs" :key="log.id" class="text-[11px] font-mono leading-relaxed group">
            <span class="text-white/20 mr-2">[{{ log.time }}]</span>
            <span :class="{
              'text-white/80': !log.text.includes('ERROR') && !log.text.includes('SYSTEM'),
              'text-red-400 font-bold': log.text.includes('ERROR'),
              'text-accent': log.text.includes('SYSTEM'),
              'text-blue-300': log.text.includes('DEBUG')
            }">{{ log.text }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="flex justify-between text-[9px] font-mono text-medical-400 uppercase tracking-widest">
      <span>Core Architecture: SpringBoot + Node.js + Go + Wails</span>
      <span>Build: 2024.MUSIC_PARTY_TERMINAL</span>
    </div>
  </div>
</template>

<style>
.terminal-scroll::-webkit-scrollbar {
  width: 4px;
}
.terminal-scroll::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.05);
}
.terminal-scroll::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.1);
}
.terminal-scroll::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.2);
}

.chamfer-br {
  clip-path: polygon(0 0, 100% 0, 100% calc(100% - 20px), calc(100% - 20px) 100%, 0 100%);
}

@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.animate-in {
  animation: fade-in 0.3s ease-out;
}
</style>
