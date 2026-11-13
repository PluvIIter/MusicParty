<script setup>
import { ref, onMounted, onUnmounted, reactive, computed } from 'vue';
import { LoadConfig, SaveConfig, StartServices, StopServices, GetServiceStatuses, GetTunnelStatus, GetSourceDescriptors, OpenBrowser } from './wailsjs/go/main/App';
import { emptyTunnelStatus, normalizeTunnelStatus, tunnelUrl, copyTunnelUrl, createStatusPoll, tunnelPasswordWarning } from './tunnelStatus';
import { createServiceAttempt } from './serviceAttempt';
import { EventsOn } from './wailsjs/runtime';
import { sourceSettingsRows, deliveryModeLabel, connectionFieldsFor } from './sourceSettings';

const config = reactive({
  serverIp: '0.0.0.0',
  serverPort: '8080',
  baseUrl: 'http://localhost:8080',
  cloudflareTunnelEnabled: false,
  adminPassword: '',
  authorName: 'ThorNex',
  backWords: 'THORNEX',
  neteaseCookie: '',
  neteaseQuality: 'exhigh',
  biliCookie: '',
  qqmusicGatewayMode: 'bundled',
  qqmusicBaseUrl: 'http://127.0.0.1:8000',
  qqmusicCookie: '',
  qqmusicQuality: 'MP3_128',
  sources: {},
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

const descriptors = ref([]);
const sourceRows = computed(() => sourceSettingsRows(descriptors.value, config.sources));
const configReady = ref(false);
const errorMessage = ref('');
const isRunning = ref(false);
const isStarting = ref(false);
const isStopping = ref(false);
const serviceAttempt = createServiceAttempt();
const statusPoll = createStatusPoll();
const tunnelStatus = ref(emptyTunnelStatus());
const publicUrl = computed(() => tunnelUrl(tunnelStatus.value));
const passwordWarning = computed(() => tunnelPasswordWarning(config.cloudflareTunnelEnabled, config.adminPassword));
const tunnelActionMessage = ref('');
const configLocked = computed(() => isStarting.value || isRunning.value || isStopping.value);
const tunnelStateLabel = computed(() => ({
  off: '已关闭', starting: '连接中', ready: '已就绪', failed: '连接失败', stopped: '已停止',
})[tunnelStatus.value.state]);
let statusInterval;
let unsubscribeLogs;
let tunnelPollingActive = false;

const resetTunnel = (state = 'off') => {
  statusPoll.reset();
  tunnelStatus.value = emptyTunnelStatus(state);
  tunnelActionMessage.value = '';
};

const pollStatuses = async () => {
  if (isStopping.value) return;
  const poll = statusPoll.begin();
  const current = () => statusPoll.current(poll) && !isStopping.value;
  const updateTunnel = next => {
    if (!current() || !tunnelPollingActive) return;
    if (!isRunning.value && !isStarting.value && (next.state === 'ready' || next.state === 'starting')) return;
    // A run's terminal failure stays visible until an explicit stop or a new start.
    if (tunnelStatus.value.state === 'failed' && next.state !== 'failed') return;
    if (next.url !== tunnelStatus.value.url) tunnelActionMessage.value = '';
    tunnelStatus.value = next;
  };
  await Promise.allSettled([
    GetServiceStatuses().then(value => {
      if (!current()) return;
      const statuses = value || {};
      Object.assign(serviceStatuses, { NETEASE_API: false, JAVA_SERVER: false, QQMUSIC_API: false }, statuses);
      if (isRunning.value && !isStarting.value && !statuses.JAVA_SERVER) {
        isRunning.value = false;
        isJavaReady.value = false;
        isApiReady.value = false;
        if (tunnelStatus.value.state === 'ready' || tunnelStatus.value.state === 'starting') {
          tunnelStatus.value = { ...tunnelStatus.value, state: 'starting', url: '' };
          tunnelActionMessage.value = '';
        }
      }
    }),
    GetTunnelStatus()
      .then(status => updateTunnel(normalizeTunnelStatus(status)))
      .catch(error => updateTunnel(emptyTunnelStatus('failed', String(error)))),
  ]);
};
const logs = ref([]);
const logContainer = ref(null);
const activeTab = ref('basic');
const serviceStatuses = reactive({
  NETEASE_API: false,
  JAVA_SERVER: false,
  QQMUSIC_API: false
});

const isJavaReady = ref(false);
const isApiReady = ref(false);

const systemUrl = computed(() => {
  const host = config.serverIp === '0.0.0.0' ? '127.0.0.1' : config.serverIp;
  return `http://${host}:${config.serverPort}`;
});

onMounted(async () => {
  try {
    const [cfg, manifest] = await Promise.all([LoadConfig(), GetSourceDescriptors()]);
    if (cfg) Object.assign(config, cfg);
    descriptors.value = manifest || [];
    configReady.value = true;
  } catch (error) {
    errorMessage.value = String(error);
  }
  
  statusInterval = setInterval(pollStatuses, 2000);

  unsubscribeLogs = EventsOn("log", (msg) => {
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

onUnmounted(() => {
  clearInterval(statusInterval);
  unsubscribeLogs?.();
  statusPoll.reset();
});

const scrollToBottom = () => {
  setTimeout(() => {
    if (logContainer.value) {
      logContainer.value.scrollTop = logContainer.value.scrollHeight;
    }
  }, 50);
};

const toggleServices = async () => {
  if (isStopping.value) return;
  errorMessage.value = '';
  const attempt = serviceAttempt.begin();
  const stopping = isRunning.value || isStarting.value;
  try {
    if (stopping) {
      serviceAttempt.cancel();
      isStopping.value = true;
      tunnelPollingActive = false;
      resetTunnel('stopped');
      Object.assign(serviceStatuses, { NETEASE_API: false, JAVA_SERVER: false, QQMUSIC_API: false });
      await StopServices();
      isStarting.value = false;
      isRunning.value = false;
      isJavaReady.value = false;
      isApiReady.value = false;
    } else {
      isStarting.value = true;
      tunnelPollingActive = false;
      resetTunnel();
      await SaveConfig(JSON.parse(JSON.stringify(config)));
      if (!serviceAttempt.current(attempt)) return;
      isJavaReady.value = false;
      isApiReady.value = false;
      logs.value = [];
      statusPoll.reset();
      tunnelPollingActive = true;
      await StartServices();
      if (!serviceAttempt.current(attempt)) return;
      isRunning.value = true;
      isStarting.value = false;
      void pollStatuses();
    }
  } catch (error) {
    if (serviceAttempt.current(attempt) || stopping) {
      isStarting.value = false;
      isRunning.value = false;
      isJavaReady.value = false;
      isApiReady.value = false;
      if (!stopping) {
        const tunnelError = tunnelStatus.value.error || String(error);
        statusPoll.reset();
        tunnelStatus.value = config.cloudflareTunnelEnabled
          ? { enabled: true, state: 'failed', url: '', error: tunnelError }
          : emptyTunnelStatus();
        tunnelActionMessage.value = '';
      }
      errorMessage.value = String(error);
    }
  } finally {
    if (stopping) isStopping.value = false;
  }
};

const copyLogs = () => {
  const text = logs.value.map(l => `[${l.time}] ${l.text}`).join('\n');
  navigator.clipboard.writeText(text);
};

const openWeb = () => {
  OpenBrowser(systemUrl.value);
};

const copyPublicUrl = async () => {
  const url = publicUrl.value;
  tunnelActionMessage.value = '';
  try {
    await copyTunnelUrl(tunnelStatus.value, navigator.clipboard?.writeText.bind(navigator.clipboard));
    if (url === publicUrl.value) tunnelActionMessage.value = '已复制公网地址';
  } catch (error) {
    if (url === publicUrl.value) tunnelActionMessage.value = `复制失败：${String(error)}`;
  }
};

const openPublicUrl = async () => {
  const url = tunnelUrl(tunnelStatus.value);
  if (!url) return;
  tunnelActionMessage.value = '';
  try {
    await OpenBrowser(url);
  } catch (error) {
    if (url === publicUrl.value) tunnelActionMessage.value = `打开失败：${String(error)}`;
  }
};
</script>

<template>
  <div class="h-screen flex flex-col p-6 space-y-4 bg-medical-50 text-medical-900 overflow-hidden font-sans">
    <!-- 头部 -->
    <div class="flex justify-between items-end border-b-2 border-medical-900 pb-4">
      <div>
        <h1 class="text-3xl font-black tracking-tighter">MusicParty</h1>
        <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-widest">> 启动器</p>
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
                <input :disabled="configLocked" v-model="config.serverIp" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">服务端口</label>
                <input :disabled="configLocked" v-model="config.serverPort" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">公网访问地址 (Base URL)</label>
                <p class="text-[9px] text-medical-400">用于生成直播流链接，如 http://1.2.3.4:8080</p>
                <input :disabled="configLocked" v-model="config.baseUrl" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm font-mono outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">管理员控制台密码</label>
                <input :disabled="configLocked" v-model="config.adminPassword" type="text" placeholder="留空则默认为 admin123" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
            </div>
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <div class="flex items-center justify-between gap-2">
                <label for="cloudflareTunnel" class="text-[10px] font-black uppercase">Cloudflare 公网隧道</label>
                <input id="cloudflareTunnel" type="checkbox" v-model="config.cloudflareTunnelEnabled" :disabled="configLocked" class="w-4 h-4 accent-medical-900 disabled:opacity-40" />
              </div>
              <p class="text-[10px] text-medical-500">开启将把本机服务公开到互联网，建议设置强管理员密码与房间密码；Quick Tunnel临时域名每次重启变化，不保证在线率/大流量音频</p>
              <p v-if="passwordWarning" role="status" class="text-[10px] text-amber-700">{{ passwordWarning }}</p>
              <p class="text-[9px] text-medical-400">默认关闭，仅在下次启动时生效；运行或启动期间不可修改配置。</p>
              <div class="text-[10px] font-mono" role="status" aria-live="polite">
                <span class="text-medical-400">隧道状态：</span>
                <span :class="tunnelStatus.state === 'ready' ? 'text-green-600' : tunnelStatus.state === 'failed' ? 'text-red-600' : 'text-medical-500'">{{ tunnelStateLabel }}</span>
              </div>
              <div v-if="publicUrl" class="space-y-2">
                <input :value="publicUrl" readonly aria-label="公网隧道地址" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none" />
                <div class="flex gap-4">
                  <button type="button" @click="copyPublicUrl" class="text-[10px] font-bold text-medical-900 hover:underline">复制地址</button>
                  <button type="button" @click="openPublicUrl" class="text-[10px] font-bold text-medical-900 hover:underline">打开公网网页</button>
                </div>
              </div>
              <p v-if="tunnelStatus.error" role="alert" class="text-[10px] text-red-600 break-words">{{ tunnelStatus.error }}</p>
              <p v-if="tunnelActionMessage" role="status" class="text-[10px] text-medical-500 break-words">{{ tunnelActionMessage }}</p>
            </div>
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">作者名称</label>
                <input :disabled="configLocked" v-model="config.authorName" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
              <div class="space-y-1">
                <label class="text-[10px] font-bold text-medical-500 uppercase">装饰文字</label>
                <input :disabled="configLocked" v-model="config.backWords" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900" />
              </div>
            </div>
          </div>

          <!-- 接口设置 -->
          <div v-if="activeTab === 'api'" class="space-y-4">
            <p class="text-[10px] text-medical-500">请求会携带该音源已配置的 Cookie；是否采用登录身份、可访问内容及缓存行为按上游接口原生规则处理。网页绑定用户只是查询目标，不会登录对方账号，也不会自动改为查询配置账号的收藏。</p>
            <div v-for="source in sourceRows" :key="source.id" class="bg-white p-4 border border-medical-200 shadow-sm space-y-4">
              <div class="flex items-center justify-between border-b border-medical-100 pb-2 mb-2">
                <h3 class="text-[10px] font-black uppercase">{{ source.label }}</h3>
                <input :disabled="configLocked" type="checkbox" v-model="config.sources[source.id].enabled" :aria-label="`启用 ${source.label}`" class="w-4 h-4 accent-medical-900" />
              </div>
              <p v-if="source.description" class="text-[10px] text-medical-500">{{ source.description }}</p>
              <fieldset :disabled="!config.sources[source.id].enabled" class="space-y-4 disabled:opacity-40">
                <div class="space-y-1">
                  <label :for="`delivery-${source.id}`" class="text-[10px] font-bold text-medical-500 uppercase">音频交付方式</label>
                  <select :disabled="configLocked" :id="`delivery-${source.id}`" v-model="config.sources[source.id].deliveryMode" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900">
                    <option v-for="mode in source.supportedModes" :key="mode" :value="mode">{{ deliveryModeLabel(mode) }}</option>
                  </select>
                </div>
                <div v-for="field in connectionFieldsFor(source, config)" :key="field.key" class="space-y-1">
                  <label :for="`connection-${field.key}`" class="text-[10px] font-bold text-medical-500 uppercase">{{ field.label }}</label>
                  <select :disabled="configLocked" v-if="field.kind === 'select'" :id="`connection-${field.key}`" v-model="config[field.key]" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-sm outline-none focus:border-medical-900">
                    <option v-for="option in field.options" :key="option.value" :value="option.value">{{ option.label }}</option>
                  </select>
                  <input :disabled="configLocked" v-else :id="`connection-${field.key}`" v-model="config[field.key]" :type="field.kind" :placeholder="field.placeholder" autocomplete="off" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-[10px] font-mono outline-none focus:border-medical-900" />
                  <p v-if="field.placeholder" class="text-[9px] text-medical-400">{{ field.placeholder }}</p>
                </div>
              </fieldset>
            </div>
          </div>

          <!-- 高级设置 -->
          <div v-if="activeTab === 'advanced'" class="space-y-4">
            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">播放队列控制</h3>
              <div class="grid grid-cols-2 gap-3">
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">队列最大歌曲上限</label>
                  <input :disabled="configLocked" v-model.number="config.queueMaxSize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">历史记录歌曲上限</label>
                  <input :disabled="configLocked" v-model.number="config.queueHistorySize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">单人歌曲上限</label>
                  <input :disabled="configLocked" v-model.number="config.queueMaxUserSongs" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">歌单导入上限</label>
                  <input :disabled="configLocked" v-model.number="config.maxPlaylistImportSize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
              </div>
            </div>

            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">聊天室限制</h3>
              <div class="grid grid-cols-2 gap-3">
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">消息历史条数</label>
                  <input :disabled="configLocked" v-model.number="config.chatMaxHistorySize" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">发言间隔(毫秒)</label>
                  <input :disabled="configLocked" v-model.number="config.chatMinIntervalMs" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
                <div class="space-y-1">
                  <label class="text-[9px] font-bold text-medical-400 uppercase">消息最大长度</label>
                  <input :disabled="configLocked" v-model.number="config.chatMaxMessageLength" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
                </div>
              </div>
            </div>

            <div class="bg-white p-4 border border-medical-200 shadow-sm space-y-3">
              <h3 class="text-[10px] font-black border-b border-medical-100 pb-1 mb-2">存储与安全</h3>
              <div class="space-y-1">
                <label class="text-[9px] font-bold text-medical-400 uppercase">音乐缓存上限</label>
                <input :disabled="configLocked" v-model="config.cacheMaxSize" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none" />
              </div>
              <div class="flex items-center gap-2 pt-2">
                <input :disabled="configLocked" type="checkbox" v-model="config.authRateLimitEnabled" id="rateLimit" class="w-4 h-4 accent-medical-900" />
                <label for="rateLimit" class="text-[10px] font-bold text-medical-600">启用进入尝试频率限制</label>
              </div>
            </div>
          </div>
        </div>

        <p v-if="errorMessage" role="alert" class="text-xs text-red-600">{{ errorMessage }}</p>
        <!-- 操作按钮 -->
        <button
          :disabled="!configReady || isStopping"
          @click="toggleServices"
          :class="isRunning ? 'bg-red-600 hover:bg-red-700 shadow-red-100' : 'bg-medical-900 hover:bg-black shadow-medical-200'"
          class="w-full py-4 text-white font-black text-xl transition-all chamfer-br active:scale-95 shadow-lg"
        >
          {{ isStarting ? '取消启动' : isRunning ? '停止运行' : '启动系统' }}
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
          <span class="text-medical-400">QQ 原生 API:</span>
          <span :class="serviceStatuses.QQMUSIC_API ? 'text-green-600 font-bold' : 'text-medical-300'">{{ serviceStatuses.QQMUSIC_API ? '已验证就绪' : '未就绪' }}</span>
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
