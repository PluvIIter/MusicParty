<template>
  <Transition
      enter-active-class="transition duration-500 ease-out"
      enter-from-class="opacity-0 translate-y-4"
      enter-to-class="opacity-100 translate-y-0"
      leave-active-class="transition duration-300 ease-in"
      leave-from-class="opacity-100 translate-y-0"
      leave-to-class="opacity-0 translate-y-4"
  >
    <div v-if="adminStore.showDashboard" class="fixed inset-0 z-[120] flex items-center justify-center p-4 bg-medical-900/40 backdrop-blur-md">
      <div class="w-full max-w-5xl h-[90vh] bg-medical-50 shadow-2xl border border-medical-200 flex flex-col chamfer-br overflow-hidden">
        
        <!-- Header -->
        <div class="p-4 md:p-6 bg-white border-b border-medical-200 flex justify-between items-center flex-shrink-0">
          <div>
            <h2 class="text-2xl font-black font-mono text-medical-900 flex items-center gap-3">
              <Settings class="w-6 h-6 text-accent animate-spin-slow" /> 管理终端
            </h2>
            <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-[0.2em]">> 系统控制接口 / SYSTEM_CONTROL_INTERFACE</p>
          </div>
          <div class="flex items-center gap-4">
            <button @click="adminStore.showDashboard = false" class="p-2 bg-medical-100 hover:bg-medical-200 text-medical-900 transition-colors">
              <X class="w-6 h-6" />
            </button>
          </div>
        </div>

        <!-- Content Area -->
        <div class="flex-1 overflow-y-auto p-4 md:p-6 space-y-6 custom-scroll bg-medical-50/50">
          
          <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">
            
            <!-- Left Column: Playback & Parameters (7 cols) -->
            <div class="lg:col-span-7 space-y-6">
              
              <!-- Section: Playback Mastery -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-medical-900 text-white flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <PlayCircle class="w-4 h-4 text-accent" />
                    <span class="text-xs font-bold uppercase tracking-widest font-mono">播放核心 / Playback_Core</span>
                  </div>
                  <span class="text-[9px] font-mono opacity-60">IDLE_STATE_MONITOR</span>
                </div>
                <div class="p-4 space-y-4">
                  <!-- Control Grid -->
                  <div class="grid grid-cols-3 gap-3">
                    <button @click="execPlayerAction('PAUSE')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                      <Pause v-if="!playerStore.isPaused" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <Play v-else class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[10px] font-bold font-mono">{{ playerStore.isPaused ? '恢复' : '暂停' }}</span>
                    </button>
                    <button @click="execPlayerAction('SKIP')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                      <SkipForward class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[10px] font-bold font-mono">切歌</span>
                    </button>
                    <button @click="execPlayerAction('SHUFFLE')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                      <ListOrdered v-if="playerStore.playMode === 'SEQUENTIAL'" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <Shuffle v-else-if="playerStore.playMode === 'SHUFFLE'" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <Repeat1 v-else class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[10px] font-bold font-mono">{{ playerStore.playMode === 'SEQUENTIAL' ? '顺序' : playerStore.playMode === 'SHUFFLE' ? '随机' : '单曲循环' }}</span>
                    </button>
                  </div>

                  <!-- Playback Core Settings (Shuffle & Vote Skip) -->
                  <div class="space-y-3 p-3 bg-medical-50 border border-medical-100 rounded-sm">
                    <!-- Shuffle Controls -->
                    <div v-if="playerStore.isShuffle" class="grid grid-cols-2 gap-3 pb-3 border-b border-medical-100">
                      <div class="flex items-center justify-between">
                        <div class="flex flex-col">
                          <span class="text-[10px] font-bold text-medical-800">{{ playerStore.isFairShuffle ? '公平模式' : '全部随机' }}</span>
                          <span class="text-[8px] text-medical-400 font-mono uppercase">算法类型</span>
                        </div>
                        <button @click="execPlayerAction('TOGGLE_FAIR_SHUFFLE')" class="w-8 h-4 rounded-full relative transition-colors" :class="playerStore.isFairShuffle ? 'bg-accent' : 'bg-medical-300'">
                          <div class="absolute top-0.5 left-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-300" :style="{ transform: playerStore.isFairShuffle ? 'translateX(16px)' : 'translateX(0)' }"></div>
                        </button>
                      </div>
                      <div class="flex items-center justify-between border-l border-medical-100 pl-3">
                        <div class="flex flex-col">
                          <span class="text-[10px] font-bold text-medical-800">{{ playerStore.allowOfflineShuffle ? '含离线' : '仅在线' }}</span>
                          <span class="text-[8px] text-medical-400 font-mono uppercase">曲库范围</span>
                        </div>
                        <button @click="execPlayerAction('TOGGLE_ALLOW_OFFLINE')" class="w-8 h-4 rounded-full relative transition-colors" :class="playerStore.allowOfflineShuffle ? 'bg-accent' : 'bg-medical-300'">
                          <div class="absolute top-0.5 left-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-300" :style="{ transform: playerStore.allowOfflineShuffle ? 'translateX(16px)' : 'translateX(0)' }"></div>
                        </button>
                      </div>
                    </div>

                    <!-- Vote Skip Controls (Always Visible) -->
                    <div class="space-y-3">
                      <div class="flex items-center justify-between">
                        <div class="flex flex-col">
                          <span class="text-[10px] font-bold text-medical-800">投票切歌模式</span>
                          <span class="text-[8px] text-medical-400 font-mono uppercase">VOTE_SKIP_MODE</span>
                        </div>
                        <button @click="updateInstantConfig({ voteSkipEnabled: !playerStore.config.voteSkipEnabled })" class="w-8 h-4 rounded-full relative transition-colors" :class="playerStore.config.voteSkipEnabled ? 'bg-accent' : 'bg-medical-300'">
                          <div class="absolute top-0.5 left-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-300" :style="{ transform: playerStore.config.voteSkipEnabled ? 'translateX(16px)' : 'translateX(0)' }"></div>
                        </button>
                      </div>
                      <div v-if="playerStore.config.voteSkipEnabled" class="grid grid-cols-2 gap-3 pt-1 border-t border-medical-100">
                        <div class="space-y-1">
                          <label class="block text-[8px] font-bold text-medical-400 uppercase">比例 (0.1-1.0)</label>
                          <input 
                            :value="playerStore.config.voteSkipThreshold" 
                            @change="e => updateInstantConfig({ voteSkipThreshold: parseFloat(e.target.value) })"
                            type="number" step="0.1" min="0.1" max="1.0" 
                            class="w-full bg-white border border-medical-200 px-2 py-1 text-[10px] outline-none focus:border-accent" 
                          />
                        </div>
                        <div class="space-y-1">
                          <label class="block text-[8px] font-bold text-medical-400 uppercase">等待时间 (秒)</label>
                          <input 
                            :value="playerStore.config.voteSkipWaitTime" 
                            @change="e => updateInstantConfig({ voteSkipWaitTime: parseInt(e.target.value) })"
                            type="number" min="0" 
                            class="w-full bg-white border border-medical-200 px-2 py-1 text-[10px] outline-none focus:border-accent" 
                          />
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- Permission Locks -->
                  <div class="grid grid-cols-3 gap-2">
                    <button v-for="lock in locks" :key="lock.key" @click="toggleLock(lock.key, !lock.value)" class="flex items-center justify-center gap-2 py-2 px-1 border transition-all text-[9px] font-bold font-mono" :class="lock.value ? 'bg-red-50 border-red-200 text-red-500' : 'bg-white border-medical-200 text-medical-400 hover:border-accent hover:text-accent'">
                      <Lock v-if="lock.value" class="w-3 h-3" />
                      <Unlock v-else class="w-3 h-3" />
                      {{ lock.cnLabel }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- Section: System Parameters -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-medical-700 text-white flex items-center gap-2">
                  <Sliders class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">系统参数 / System_Parameters</span>
                </div>
                <div class="p-4 space-y-4">
                  <div class="grid grid-cols-2 gap-x-4 gap-y-3">
                  <div v-for="(val, key) in systemFields" :key="key" class="space-y-1">
                      <label class="block text-[9px] font-bold text-medical-400 font-mono uppercase">{{ key }}</label>
                      <input v-model.number="configProxy[val.field]" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1.5 text-xs outline-none focus:border-accent font-mono" />
                    </div>
                  </div>

                  <button @click="saveSystemConfig" class="w-full bg-medical-900 text-white py-2 text-xs font-bold hover:bg-accent transition-colors flex items-center justify-center gap-2">
                    <Save class="w-4 h-4" /> 应用并保存所有更改
                  </button>
                </div>
              </div>
            </div>

            <!-- Right Column: Environment & Danger Zone (5 cols) -->
            <div class="lg:col-span-5 space-y-6">
              
              <!-- Section: Room Environment -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-medical-800 text-white flex items-center gap-2">
                  <Globe class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">环境配置 / Environment</span>
                </div>
                <div class="p-4 space-y-4">
                  <!-- Password -->
                  <div class="space-y-2">
                    <label class="block text-[10px] font-bold text-medical-400 uppercase font-mono">房间进入密码</label>
                    <div class="flex gap-2">
                      <input v-model="roomPassword" placeholder="留空则设为公开" class="flex-1 bg-medical-50 border border-medical-200 px-3 py-1.5 text-xs outline-none focus:border-accent" />
                      <button @click="updateRoomPassword" class="bg-medical-900 text-white px-3 py-1.5 text-[10px] font-bold hover:bg-accent">设置</button>
                    </div>
                  </div>
                  <!-- Toggles -->
                  <div class="grid grid-cols-2 gap-3">
                    <div class="p-3 bg-medical-50 border border-medical-100 flex flex-col items-center gap-2 rounded-sm">
                      <span class="text-[9px] font-bold text-medical-400 uppercase font-mono">直播推流</span>
                      <button @click="toggleStream" class="w-10 h-5 rounded-full relative transition-colors" :class="playerStore.streamActive ? 'bg-accent' : 'bg-medical-300'">
                        <div class="absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full transition-transform duration-300" :style="{ transform: playerStore.streamActive ? 'translateX(20px)' : 'translateX(0)' }"></div>
                      </button>
                    </div>
                    <div class="p-3 bg-medical-50 border border-medical-100 flex flex-col gap-2 rounded-sm">
                      <span class="text-[9px] font-bold text-medical-400 uppercase font-mono text-center">数据清理</span>
                      <div class="flex flex-col gap-1 w-full">
                        <button @click="clearData('QUEUE')" class="w-full py-1 border border-medical-200 text-[8px] font-bold hover:bg-red-50 hover:text-red-500 transition-all">清理播放队列</button>
                        <button @click="clearData('OFFLINE')" class="w-full py-1 border border-medical-200 text-[8px] font-bold hover:bg-red-50 hover:text-red-500 transition-all">清理不在线成员歌曲</button>
                        <button @click="clearData('CHAT')" class="w-full py-1 border border-medical-200 text-[8px] font-bold hover:bg-red-50 hover:text-red-500 transition-all">清理聊天记录</button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Section: Credentials -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden chamfer-br">
                <div class="p-3 bg-medical-600 text-white flex items-center gap-2">
                  <Database class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">平台凭据 / Credentials</span>
                </div>
                <div class="p-4 space-y-4">
                   <div v-for="plat in platforms" :key="plat.id" class="space-y-2 border-b border-medical-50 pb-3 last:border-0 last:pb-0">
                    <div class="flex justify-between items-center">
                      <span class="text-[10px] font-bold text-medical-600 font-mono">{{ plat.name }} // {{ plat.tokenName }}</span>
                      <button 
                        @click="togglePlatform(plat.id)"
                        class="w-8 h-4 rounded-full relative transition-colors"
                        :class="playerStore.config[`${plat.id}Enabled`] ? 'bg-accent' : 'bg-medical-300'"
                      >
                        <div class="absolute top-0.5 left-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-300" :style="{ transform: playerStore.config[`${plat.id}Enabled`] ? 'translateX(16px)' : 'translateX(0)' }"></div>
                      </button>
                    </div>
                    <div class="flex gap-2">
                      <input 
                        type="password"
                        v-model="plat.value" 
                        :placeholder="'输入新 ' + plat.tokenName + '...'" 
                        class="flex-1 bg-medical-50 border border-medical-200 px-3 py-2 text-[10px] outline-none focus:border-accent font-mono"
                      />
                      <button @click="updateCookie(plat.id, plat.value)" class="bg-medical-900 text-white px-3 font-bold text-[10px] hover:bg-accent transition-colors">更新</button>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Danger Zone -->
              <div class="p-4 bg-red-50 border border-red-100 chamfer-br flex flex-col gap-3">
                <h4 class="text-red-600 text-[10px] font-black flex items-center gap-2 uppercase tracking-tighter">
                  <AlertTriangle class="w-4 h-4" /> 危险区域 / DANGER_ZONE.SH
                </h4>
                <button @click="handleReset" class="w-full py-2 bg-red-600 text-white font-bold text-[10px] hover:bg-red-700 transition-all uppercase tracking-widest shadow-sm">
                  全系统重置 (慎用)
                </button>
              </div>
            </div>
          </div>

        </div>

        <!-- Footer -->
        <div class="p-3 bg-medical-100 border-t border-medical-200 flex justify-between items-center text-[9px] font-mono text-medical-400">
          <span class="flex items-center gap-1"><ShieldCheck class="w-3 h-3 text-green-500" /> 安全连接: AES-256-GCM</span>
          <span>管理员哈希: {{ adminStore.adminPassword.substring(0, 4).toUpperCase() }}****</span>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { useAdminStore } from '../stores/admin';
import { usePlayerStore } from '../stores/player';
import { adminApi } from '../api/admin';
import { useToast } from '../composables/useToast';
import {
  Settings, X, Pause, Play, SkipForward, ListOrdered, Repeat1, Shuffle,
  Lock, Unlock, ShieldAlert, Save, AlertTriangle,
  PlayCircle, Database, Globe, Sliders, ShieldCheck
} from 'lucide-vue-next';

const adminStore = useAdminStore();
const playerStore = usePlayerStore();
const { success, error, warning } = useToast();

const roomPassword = ref('');

// Config Proxy for editing
const configProxy = ref({ ...playerStore.config });

const systemFields = {
  '队列最大长度': { field: 'maxQueueSize' },
  '历史记录容量': { field: 'maxHistorySize' },
  '用户点歌上限': { field: 'maxUserSongs' },
  '导入单次上限': { field: 'maxPlaylistImportSize' },
  '聊天记录容量': { field: 'maxChatHistorySize' },
  '发言频率限制(ms)': { field: 'minChatIntervalMs' }
};

watch(() => playerStore.config, (newVal) => {
  configProxy.value = { ...newVal };
}, { deep: true });

const saveSystemConfig = async () => {
  try {
    const data = await adminApi.updateConfig(adminStore.adminPassword, configProxy.value);
    success(data.message);
  } catch (e) {
    error('配置同步失败');
  }
};

const updateInstantConfig = async (update) => {
  try {
    const data = await adminApi.updateConfig(adminStore.adminPassword, update);
    success(data.message);
  } catch (e) {
    error('配置同步失败');
  }
};

const locks = computed(() => [
  { key: 'PAUSE', cnLabel: '锁定暂停', value: playerStore.isPauseLocked },
  { key: 'SKIP', cnLabel: '锁定切歌', value: playerStore.isSkipLocked },
  { key: 'SHUFFLE', cnLabel: '锁定模式', value: playerStore.isPlayModeLocked },
]);

const platforms = ref([
  { id: 'netease', name: '网易云音乐', tokenName: 'COOKIE', value: '' },
  { id: 'bilibili', name: '哔哩哔哩', tokenName: 'SESSDATA', value: '' }
]);

const execPlayerAction = async (action) => {
  try {
    const data = await adminApi.playerAction(adminStore.adminPassword, action);
    success(data.message);
  } catch (e) {
    error('指令执行失败');
  }
};

const toggleLock = async (type, locked) => {
  try {
    const data = await adminApi.setLock(adminStore.adminPassword, type, locked);
    success(data.message);
  } catch (e) {
    error('锁定同步失败');
  }
};

const updateRoomPassword = async () => {
  try {
    const data = await adminApi.setRoomPassword(adminStore.adminPassword, roomPassword.value);
    success(data.message);
  } catch (e) {
    error('密码更新失败');
  }
};

const toggleStream = async () => {
  try {
    const data = await adminApi.setStream(adminStore.adminPassword, !playerStore.streamActive);
    success(data.message);
  } catch (e) {
    error('直播流控制失败');
  }
};

const clearData = async (target) => {
  const targetName = {
    'QUEUE': '播放队列',
    'OFFLINE': '不在线成员的点播歌曲',
    'CHAT': '聊天记录'
  }[target] || target;
  
  if (!confirm(`确定要清空 ${targetName} 吗?`)) return;
  try {
    const data = await adminApi.clearData(adminStore.adminPassword, target);
    warning(data.message);
  } catch (e) {
    error('清理操作失败');
  }
};

const updateCookie = async (platform, value) => {
  if (!value) return;
  try {
    const data = await adminApi.setCookie(adminStore.adminPassword, platform, value);
    success(data.message);
  } catch (e) {
    error('凭据更新失败');
  }
};

const togglePlatform = async (platformId) => {
  const current = playerStore.config[`${platformId}Enabled`];
  const update = { [`${platformId}Enabled`]: !current };
  try {
    const data = await adminApi.updateConfig(adminStore.adminPassword, update);
    success(data.message);
  } catch (e) {
    error('平台状态切换失败');
  }
};

const handleReset = async () => {
  if (!confirm('!!! 警告 !!! \n这将重置整个系统。 \n你确定要继续吗？')) return;
  try {
    const data = await adminApi.resetSystem(adminStore.adminPassword);
    warning(data.message);
  } catch (e) {
    error('系统重置失败');
  }
};
</script>

<style scoped>
.animate-spin-slow {
  animation: spin 8s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.custom-scroll::-webkit-scrollbar { width: 4px; }
.custom-scroll::-webkit-scrollbar-track { background: transparent; }
.custom-scroll::-webkit-scrollbar-thumb { @apply bg-medical-200 rounded-full; }
.custom-scroll::-webkit-scrollbar-thumb:hover { @apply bg-medical-300; }
</style>
