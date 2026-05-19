<script setup>
import { ref, onMounted, reactive, computed } from 'vue';
import { LoadConfig, SaveConfig, StartServices, StopServices, GetServiceStatuses, OpenBrowser } from './wailsjs/go/main/App';
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
const serviceStatuses = reactive({
  NETEASE_API: false,
  JAVA_SERVER: false
});

const isJavaReady = ref(false);
const isApiReady = ref(false);

const systemUrl = computed(() => {
  const host = config.serverIp === '0.0.0.0' ? '127.0.0.1' : config.serverIp;
  return `http://${host}:${config.serverPort}`;
});

onMounted(async () => {
  const cfg = await LoadConfig();
  if (cfg) Object.assign(config, cfg);
  
  // 轮询服务状态
  setInterval(async () => {
    if (isRunning.value) {
      const statuses = await GetServiceStatuses();
      Object.assign(serviceStatuses, statuses);
    } else {
      serviceStatuses.NETEASE_API = false;
      serviceStatuses.JAVA_SERVER = false;
    }
  }, 2000);

  EventsOn("log", (msg) => {
    logs.value.push({
      id: Date.now() + Math.random(),
      time: new Date().toLocaleTimeString(),
      text: msg
    });

    // 检测就绪状态
    if (msg.includes("Started MusicPartyApplication")) {
      isJavaReady.value = true;
    }
    if (msg.includes("server started") || msg.includes("NETEASE_API") && msg.includes("exited") === false) {
      // API 启动较快，简单处理
      isApiReady.value = true;
    }

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
  isJavaReady.value = false;
  isApiReady.value = false;
  logs.value = [];
  await StartServices();
};

const handleStop = async () => {
  await StopServices();
  isRunning.value = false;
  isJavaReady.value = false;
  isApiReady.value = false;
};

const copyLogs = () => {
  const text = logs.value.map(l => `[${l.time}] ${l.text}`).join('\n');
  navigator.clipboard.writeText(text);
};

const openWeb = () => {
  OpenBrowser(systemUrl.value);
};
</script>

<template>
  <div class="h-screen flex flex-col p-6 space-y-4 bg-medical-50 text-medical-900 overflow-hidden font-sans">
    <!-- Header -->
    <div class="flex justify-between items-end border-b-2 border-medical-900 pb-4">
      <div>
        <h1 class="text-3xl font-black tracking-tighter">MUSIC PARTY</h1>
        <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-widest">> DEPLOYMENT TERMINAL v1.2</p>
      </div>
      <div class="flex items-center gap-6">
        <div class="flex flex-col items-end">
          <span class="text-[9px] font-bold text-medical-400 uppercase">系统总览 / SYSTEM OVERVIEW</span>
          <span :class="isRunning ? 'text-green-600' : 'text-red-600'" class="text-xs font-black font-mono">
            {{ isRunning ? '● 在线 / ONLINE' : '○ 离线 / OFFLINE' }}
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
            {{ tab === 'basic' ? '基础' : tab === 'api' ? '接口' : '高级' }}
          </button>
        </div>

        <div class="flex-1 overflow-y-auto pr-2 space-y-4 custom-scroll">
          <!-- Basic Settings -->
          <div v-if="activeTab === 'basic'" class="space-y-4">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">绑定地址 / HOST</label>
                <input v-model="config.serverIp" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">服务端口 / PORT</label>
                <input v-model="config.serverPort" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">管理员密码 / ADMIN PASS</label>
                <input v-model="config.adminPassword" type="text" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
            </div>
          </div>

          <!-- API Settings -->
          <div v-if="activeTab === 'api'" class="space-y-4">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">网易云 COOKIE</label>
                <textarea v-model="config.neteaseCookie" rows="4" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none focus:border-medical-900 resize-none"></textarea>
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">解析音质 / QUALITY</label>
                <select v-model="config.neteaseQuality" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900">
                  <option value="standard">标准</option>
                  <option value="exhigh">极高</option>
                  <option value="lossless">无损</option>
                </select>
              </div>
            </div>
            <div class="bg-white p-4 border border-medical-200 shadow-sm">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">B站 SESSDATA</label>
                <input v-model="config.biliSessData" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none focus:border-medical-900" />
              </div>
            </div>
          </div>

          <!-- Advanced Settings -->
          <div v-if="activeTab === 'advanced'" class="space-y-4">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <div class="grid grid-cols-2 gap-3">
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">队列容量</label>
                  <input v-model.number="config.queueMaxSize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">缓存(GB)</label>
                  <input v-model="config.cacheMaxSize" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Actions -->
        <div class="space-y-2">
          <div v-if="isJavaReady" class="animate-bounce">
             <button @click="openWeb" class="w-full py-3 bg-accent text-white font-black text-sm chamfer-br shadow-lg flex items-center justify-center gap-2">
               <span>➔ 访问服务界面 / OPEN WEB UI</span>
             </button>
          </div>
          <button 
            v-if="!isRunning"
            @click="handleStart"
            class="w-full py-4 bg-medical-900 text-white font-black text-xl hover:bg-black transition-all chamfer-br active:scale-95"
          >
            启动 / START
          </button>
          <button 
            v-else
            @click="handleStop"
            class="w-full py-4 bg-red-600 text-white font-black text-xl hover:bg-red-700 transition-all chamfer-br active:scale-95"
          >
            停止 / STOP
          </button>
        </div>
      </div>

      <!-- Right: Terminal -->
      <div class="flex-1 flex flex-col bg-medical-900 chamfer-br p-4 overflow-hidden border-2 border-medical-900 shadow-inner relative">
        <div class="flex justify-between items-center mb-2 border-b border-white/10 pb-2">
          <div class="flex items-center gap-4">
            <span class="text-[10px] font-mono text-white/40 uppercase tracking-widest">日志 / LOGS</span>
            <button @click="copyLogs" class="text-[10px] font-bold text-accent hover:underline decoration-accent-500 underline-offset-4">复制 / COPY</button>
          </div>
          <div class="flex gap-4">
             <div class="flex items-center gap-1.5">
               <span class="w-1.5 h-1.5 rounded-full" :class="serviceStatuses.NETEASE_API ? 'bg-green-500 shadow-[0_0_8px_#22c55e]' : 'bg-white/20'"></span>
               <span class="text-[9px] font-mono text-white/60">API</span>
             </div>
             <div class="flex items-center gap-1.5">
               <span class="w-1.5 h-1.5 rounded-full" :class="serviceStatuses.JAVA_SERVER ? 'bg-green-500 shadow-[0_0_8px_#22c55e]' : 'bg-white/20'"></span>
               <span class="text-[9px] font-mono text-white/60">SERVER</span>
             </div>
          </div>
        </div>
        <div ref="logContainer" class="flex-1 overflow-y-auto space-y-1 terminal-scroll">
          <div v-for="log in logs" :key="log.id" class="text-[11px] font-mono leading-relaxed">
            <span class="text-white/20 mr-2">[{{ log.time }}]</span>
            <span :class="{
              'text-white/80': !log.text.includes('ERROR') && !log.text.includes('SYSTEM'),
              'text-red-400 font-bold': log.text.includes('ERROR'),
              'text-accent': log.text.includes('SYSTEM'),
              'text-blue-300': log.text.includes('DEBUG')
            }">{{ log.text }}</span>
          </div>
        </div>
        
        <!-- Web UI Hint Overlay -->
        <div v-if="isJavaReady" class="absolute bottom-6 right-6 p-4 bg-accent/90 backdrop-blur-sm border border-white/20 text-white chamfer-br shadow-2xl animate-in slide-in-from-bottom-4">
          <p class="text-[10px] font-black uppercase mb-1">服务就绪 / SERVICE READY</p>
          <p class="text-xs font-mono mb-2">{{ systemUrl }}</p>
          <button @click="openWeb" class="text-[10px] font-bold bg-white text-accent px-3 py-1 hover:bg-white/90 transition-colors uppercase">立即打开 / OPEN NOW</button>
        </div>
      </div>
    </div>

    <!-- Status Bar -->
    <div class="flex justify-between items-center px-4 py-2 bg-white border border-medical-200 text-[10px] font-mono shadow-sm">
      <div class="flex gap-6">
        <div class="flex items-center gap-2">
          <span class="text-medical-400">NETEASE_API:</span>
          <span :class="serviceStatuses.NETEASE_API ? 'text-green-600 font-bold' : 'text-medical-300'">
            {{ serviceStatuses.NETEASE_API ? (isApiReady ? 'RUNNING' : 'STARTING') : 'STOPPED' }}
          </span>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-medical-400">JAVA_SERVER:</span>
          <span :class="serviceStatuses.JAVA_SERVER ? 'text-green-600 font-bold' : 'text-medical-300'">
            {{ serviceStatuses.JAVA_SERVER ? (isJavaReady ? 'READY' : 'STARTING') : 'STOPPED' }}
          </span>
        </div>
      </div>
      <div class="flex items-center gap-4 text-medical-400">
        <span>ARCH: X64</span>
        <span>OS: WIN32</span>
        <span class="text-medical-900 font-bold">2024.MUSIC_PARTY_TERMINAL</span>
      </div>
    </div>
  </div>
</template>

<style>
.terminal-scroll::-webkit-scrollbar, .custom-scroll::-webkit-scrollbar {
  width: 4px;
}
.terminal-scroll::-webkit-scrollbar-track, .custom-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.terminal-scroll::-webkit-scrollbar-thumb, .custom-scroll::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.1);
}
.terminal-scroll::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.1);
}

.chamfer-br {
  clip-path: polygon(0 0, 100% 0, 100% calc(100% - 15px), calc(100% - 15px) 100%, 0 100%);
}

@keyframes slide-in-from-bottom {
  from { transform: translateY(100%); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}

.animate-in {
  animation: slide-in-from-bottom 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}
</style>
