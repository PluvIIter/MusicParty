<script setup>
import { ref, onMounted, reactive, computed } from 'vue';
import { LoadConfig, SaveConfig, StartServices, StopServices, GetServiceStatuses, OpenBrowser } from './wailsjs/go/main/App';
import { EventsOn } from './wailsjs/runtime';

const config = reactive({
  serverIp: '0.0.0.0',
  serverPort: '8080',
  baseUrl: 'http://localhost:8080',
  adminPassword: '',
  authorName: 'ThorNex',
  backWords: 'THORNEX',
  neteaseCookie: '',
  neteaseQuality: 'exhigh',
  neteaseEnabled: true,
  biliSessData: '',
  bilibiliEnabled: true,
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

    if (msg.includes("Started MusicPartyApplication")) {
      isJavaReady.value = true;
    }
    if (msg.includes("server started") || msg.includes("NETEASE_API") && msg.includes("exited") === false) {
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

const toggleServices = async () => {
  if (isRunning.value) {
    await StopServices();
    isRunning.value = false;
    isJavaReady.value = false;
    isApiReady.value = false;
  } else {
    await SaveConfig(JSON.parse(JSON.stringify(config)));
    isRunning.value = true;
    isJavaReady.value = false;
    isApiReady.value = false;
    logs.value = [];
    await StartServices();
  }
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
    <!-- 头部 -->
    <div class="flex justify-between items-end border-b-2 border-medical-900 pb-4">
      <div>
        <h1 class="text-3xl font-black tracking-tighter">MusicParty</h1>
        <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-widest">> 启动器 v1.2</p>
      </div>
      <div class="flex items-center gap-6">
        <div v-if="isJavaReady" class="animate-in fade-in zoom-in duration-300">
           <button @click="openWeb" class="px-6 py-2 bg-accent text-white font-black text-xs chamfer-br shadow-lg hover:bg-black transition-all flex items-center gap-2">
             <span>打开网页</span>
           </button>
        </div>
        <div v-else class="flex flex-col items-end">
          <span :class="isRunning ? 'text-green-600' : 'text-red-600'" class="text-xs font-black font-mono">
            {{ isRunning ? '● 在线' : '○ 离线' }}
          </span>
        </div>
      </div>
    </div>

    <!-- 主体内容 -->
    <div class="flex-1 min-h-0 flex gap-6">
      <!-- 左侧：设置 -->
      <div class="w-96 flex flex-col gap-4">
        <!-- 标签页 -->
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
          <!-- 基础设置 -->
          <div v-if="activeTab === 'basic'" class="space-y-4">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">监听地址</label>
                <p class="text-[9px] text-medical-400">保持 0.0.0.0 以允许所有设备访问</p>
                <input v-model="config.serverIp" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">服务端口</label>
                <input v-model="config.serverPort" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">公网访问地址 (Base URL)</label>
                <p class="text-[9px] text-medical-400">用于生成直播流链接，如 http://1.2.3.4:8080</p>
                <input v-model="config.baseUrl" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">管理员控制台密码</label>
                <input v-model="config.adminPassword" type="text" placeholder="留空则默认为 admin123" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
            </div>
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">作者名称</label>
                <input v-model="config.authorName" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">装饰文字</label>
                <input v-model="config.backWords" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
            </div>
          </div>

          <!-- 接口设置 -->
          <div v-if="activeTab === 'api'" class="space-y-4">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="flex items-center justify-between border-b border-medical-100 pb-2 mb-2">
                <h3 class="text-[10px] font-black uppercase">网易云音乐 (Netease)</h3>
                <input type="checkbox" v-model="config.neteaseEnabled" class="w-4 h-4 accent-medical-900" />
              </div>
              <div class="space-y-4" :class="!config.neteaseEnabled ? 'opacity-40 grayscale pointer-events-none' : ''">
                <div class="space-y-1">
                  <label class="text-[10px] font-bold text-medical-500 uppercase">账号 Cookie</label>
                  <textarea v-model="config.neteaseCookie" placeholder="用于获取高清音质和私人歌单" rows="3" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none focus:border-medical-900 resize-none"></textarea>
                </div>
                <div class="space-y-1">
                  <label class="text-[10px] font-bold text-medical-500 uppercase">解析音质上限</label>
                  <select v-model="config.neteaseQuality" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900">
                    <option value="standard">标准</option>
                    <option value="higher">较高</option>
                    <option value="exhigh">极高</option>
                    <option value="lossless">无损</option>
                    <option value="hires">高解析度</option>
                  </select>
                </div>
              </div>
            </div>
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="flex items-center justify-between border-b border-medical-100 pb-2 mb-2">
                <h3 class="text-[10px] font-black uppercase">Bilibili</h3>
                <input type="checkbox" v-model="config.bilibiliEnabled" class="w-4 h-4 accent-medical-900" />
              </div>
              <div class="space-y-1" :class="!config.bilibiliEnabled ? 'opacity-40 grayscale pointer-events-none' : ''">
                <label class="text-[10px] font-bold text-medical-500 uppercase">SessData</label>
                <input v-model="config.biliSessData" placeholder="用于解析B站音频流" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none focus:border-medical-900" />
              </div>
            </div>
          </div>

          <!-- 高级设置 -->
          <div v-if="activeTab === 'advanced'" class="space-y-4">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">播放队列控制</h3>
              <div class="grid grid-cols-2 gap-3">
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">队列最大歌曲上限</label>
                  <input v-model.number="config.queueMaxSize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">历史记录歌曲上限</label>
                  <input v-model.number="config.queueHistorySize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">单人歌曲上限</label>
                  <input v-model.number="config.queueMaxUserSongs" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">歌单导入上限</label>
                  <input v-model.number="config.maxPlaylistImportSize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
              </div>
            </div>

            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">聊天室限制</h3>
              <div class="grid grid-cols-2 gap-3">
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">消息历史条数</label>
                  <input v-model.number="config.chatMaxHistorySize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">发言间隔(毫秒)</label>
                  <input v-model.number="config.chatMinIntervalMs" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">消息最大长度</label>
                  <input v-model.number="config.chatMaxMessageLength" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
              </div>
            </div>

            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">存储与安全</h3>
              <div class="space-y-1">
                <label class="text-[9px] font-bold text-medical-400 uppercase">音乐缓存上限</label>
                <input v-model="config.cacheMaxSize" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
              </div>
              <div class="flex items-center gap-2 pt-2">
                <input type="checkbox" v-model="config.authRateLimitEnabled" id="rateLimit" class="w-4 h-4 accent-medical-900" />
                <label for="rateLimit" class="text-[10px] font-bold text-medical-600">启用进入尝试频率限制</label>
              </div>
            </div>
          </div>
        </div>

        <!-- 操作按钮 -->
        <button 
          @click="toggleServices"
          :class="isRunning ? 'bg-red-600 hover:bg-red-700 shadow-red-100' : 'bg-medical-900 hover:bg-black shadow-medical-200'"
          class="w-full py-4 text-white font-black text-xl transition-all chamfer-br active:scale-95 shadow-lg"
        >
          {{ isRunning ? '停止运行' : '启动系统' }}
        </button>
      </div>

      <!-- 右侧：日志 -->
      <div class="flex-1 flex flex-col bg-medical-900 chamfer-br p-4 overflow-hidden border-2 border-medical-900 shadow-inner relative">
        <div class="flex justify-between items-center mb-2 border-b border-white/10 pb-2">
          <div class="flex items-center gap-4">
            <span class="text-[10px] font-mono text-white/40 uppercase tracking-widest">系统执行日志</span>
            <button @click="copyLogs" class="text-[10px] font-bold text-accent hover:underline decoration-accent-500 underline-offset-4">复制日志</button>
          </div>
          <div class="flex gap-4">
             <div class="flex items-center gap-1.5">
               <span class="w-1.5 h-1.5 rounded-full" :class="serviceStatuses.NETEASE_API ? 'bg-green-500 shadow-[0_0_8px_#22c55e]' : 'bg-white/20'"></span>
               <span class="text-[9px] font-mono text-white/60">网易云 API</span>
             </div>
             <div class="flex items-center gap-1.5">
               <span class="w-1.5 h-1.5 rounded-full" :class="serviceStatuses.JAVA_SERVER ? 'bg-green-500 shadow-[0_0_8px_#22c55e]' : 'bg-white/20'"></span>
               <span class="text-[9px] font-mono text-white/60">后端服务</span>
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
      </div>
    </div>

    <!-- 状态栏 -->
    <div class="flex justify-between items-center px-4 py-2 bg-white border border-medical-200 text-[10px] font-mono shadow-sm">
      <div class="flex gap-6">
        <div class="flex items-center gap-2">
          <span class="text-medical-400">网易云服务:</span>
          <span :class="serviceStatuses.NETEASE_API ? 'text-green-600 font-bold' : 'text-medical-300'">
            {{ serviceStatuses.NETEASE_API ? (isApiReady ? '已运行' : '启动中') : '已停止' }}
          </span>
        </div>
        <div class="flex items-center gap-2">
          <span class="text-medical-400">后端主服务:</span>
          <span :class="serviceStatuses.JAVA_SERVER ? 'text-green-600 font-bold' : 'text-medical-300'">
            {{ serviceStatuses.JAVA_SERVER ? (isJavaReady ? '就绪' : '启动中') : '已停止' }}
          </span>
        </div>
      </div>
      <div class="flex items-center gap-4 text-medical-400">
        <span>架构: X64</span>
        <span>平台: Windows</span>
        <span class="text-medical-900 font-bold">MusicParty启动器</span>
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
