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
              <Settings class="w-6 h-6 text-accent animate-spin-slow" /> ADMIN_TERMINAL
            </h2>
            <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-[0.2em]">> SYSTEM CONTROL INTERFACE v2.5</p>
          </div>
          <div class="flex items-center gap-4">
            <button @click="adminStore.logout" class="text-xs font-bold text-red-500 hover:underline uppercase font-mono">Terminate Session</button>
            <button @click="adminStore.showDashboard = false" class="p-2 bg-medical-100 hover:bg-medical-200 text-medical-900 transition-colors">
              <X class="w-6 h-6" />
            </button>
          </div>
        </div>

        <!-- Content Area -->
        <div class="flex-1 overflow-y-auto p-4 md:p-6 space-y-6 custom-scroll bg-medical-50/50">
          
          <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">
            
            <!-- Left Column: Playback & Environment (7 cols) -->
            <div class="lg:col-span-7 space-y-6">
              
              <!-- Section: Playback Mastery -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden">
                <div class="p-3 bg-medical-900 text-white flex items-center gap-2">
                  <PlayCircle class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">Playback_Core</span>
                </div>
                <div class="p-4 space-y-4">
                  <!-- Control Grid -->
                  <div class="grid grid-cols-3 gap-3">
                    <button @click="execPlayerAction('PAUSE')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                      <Pause v-if="!playerStore.isPaused" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <Play v-else class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[10px] font-bold font-mono">{{ playerStore.isPaused ? 'RESUME' : 'PAUSE' }}</span>
                    </button>
                    <button @click="execPlayerAction('SKIP')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                      <SkipForward class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[10px] font-bold font-mono">SKIP</span>
                    </button>
                    <button @click="execPlayerAction('SHUFFLE')" class="flex flex-col items-center justify-center p-4 bg-medical-50 border border-medical-200 hover:border-accent hover:text-accent transition-all group" :class="playerStore.isShuffle ? 'border-accent text-accent' : ''">
                      <Shuffle class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                      <span class="text-[10px] font-bold font-mono">SHUFFLE</span>
                    </button>
                  </div>

                  <!-- Shuffle Sub-Settings -->
                  <div v-if="playerStore.isShuffle" class="grid grid-cols-2 gap-3 p-3 bg-medical-50 border border-medical-100 rounded">
                    <div class="flex items-center justify-between">
                      <div class="flex flex-col">
                        <span class="text-[10px] font-bold text-medical-800">{{ playerStore.isFairShuffle ? 'Fair Mode' : 'Total Mode' }}</span>
                        <span class="text-[8px] text-medical-400 font-mono">ALGO_TYPE</span>
                      </div>
                      <button @click="execPlayerAction('TOGGLE_FAIR_SHUFFLE')" class="w-8 h-4 rounded-full relative transition-colors" :class="playerStore.isFairShuffle ? 'bg-accent' : 'bg-medical-300'">
                        <div class="absolute top-0.5 w-3 h-3 bg-white rounded-full transition-all" :class="playerStore.isFairShuffle ? 'left-4.5' : 'left-0.5'"></div>
                      </button>
                    </div>
                    <div class="flex items-center justify-between">
                      <div class="flex flex-col">
                        <span class="text-[10px] font-bold text-medical-800">{{ playerStore.allowOfflineShuffle ? 'Offline ON' : 'Offline OFF' }}</span>
                        <span class="text-[8px] text-medical-400 font-mono">POOL_SCOPE</span>
                      </div>
                      <button @click="execPlayerAction('TOGGLE_ALLOW_OFFLINE')" class="w-8 h-4 rounded-full relative transition-colors" :class="playerStore.allowOfflineShuffle ? 'bg-green-500' : 'bg-medical-300'">
                        <div class="absolute top-0.5 w-3 h-3 bg-white rounded-full transition-all" :class="playerStore.allowOfflineShuffle ? 'left-4.5' : 'left-0.5'"></div>
                      </button>
                    </div>
                  </div>

                  <!-- Permission Locks -->
                  <div class="grid grid-cols-3 gap-2">
                    <button v-for="lock in locks" :key="lock.key" @click="toggleLock(lock.key, !lock.value)" class="flex items-center justify-center gap-2 py-2 px-1 border transition-all text-[9px] font-bold font-mono" :class="lock.value ? 'bg-red-50 border-red-200 text-red-500' : 'bg-white border-medical-200 text-medical-400 hover:border-accent hover:text-accent'">
                      <Lock v-if="lock.value" class="w-3 h-3" />
                      <Unlock v-else class="w-3 h-3" />
                      {{ lock.label.split(' ')[1].toUpperCase() }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- Section: Credentials & External APIs -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden">
                <div class="p-3 bg-medical-800 text-white flex items-center gap-2">
                  <Database class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">Cloud_Credentials</span>
                </div>
                <div class="p-4 space-y-4">
                   <div v-for="plat in platforms" :key="plat.id" class="space-y-2">
                    <div class="flex justify-between items-center">
                      <span class="text-[10px] font-bold text-medical-600 font-mono">{{ plat.name }} // {{ plat.tokenName }}</span>
                    </div>
                    <div class="flex gap-2">
                      <input 
                        type="password"
                        v-model="plat.value" 
                        :placeholder="'ENTER ' + plat.tokenName + '...'" 
                        class="flex-1 bg-medical-50 border border-medical-200 px-3 py-2 text-xs outline-none focus:border-accent font-mono"
                      />
                      <button @click="updateCookie(plat.id, plat.value)" class="bg-medical-900 text-white px-4 font-bold text-[10px] hover:bg-accent transition-colors">UPDATE</button>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Right Column: System Config & Danger Zone (5 cols) -->
            <div class="lg:col-span-5 space-y-6">
              
              <!-- Section: Room Environment -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden">
                <div class="p-3 bg-medical-700 text-white flex items-center gap-2">
                  <Globe class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">Environment_Variable</span>
                </div>
                <div class="p-4 space-y-4">
                  <!-- Password -->
                  <div class="space-y-2">
                    <label class="block text-[10px] font-bold text-medical-400 uppercase font-mono">Entry_Password</label>
                    <div class="flex gap-2">
                      <input v-model="roomPassword" placeholder="PUBLIC" class="flex-1 bg-medical-50 border border-medical-200 px-3 py-1.5 text-xs outline-none focus:border-accent" />
                      <button @click="updateRoomPassword" class="bg-medical-900 text-white px-3 py-1.5 text-[10px] font-bold hover:bg-accent">SET</button>
                    </div>
                  </div>
                  <!-- Toggles -->
                  <div class="grid grid-cols-2 gap-3">
                    <div class="p-3 bg-medical-50 border border-medical-100 flex flex-col items-center gap-2">
                      <span class="text-[9px] font-bold text-medical-400 uppercase font-mono">Live_Stream</span>
                      <button @click="toggleStream" class="w-10 h-5 rounded-full relative transition-colors" :class="playerStore.streamActive ? 'bg-green-500' : 'bg-medical-300'">
                        <div class="absolute top-0.5 w-4 h-4 bg-white rounded-full transition-all" :class="playerStore.streamActive ? 'left-5.5' : 'left-0.5'"></div>
                      </button>
                    </div>
                    <div class="p-3 bg-medical-50 border border-medical-100 flex flex-col gap-1">
                      <span class="text-[9px] font-bold text-medical-400 uppercase font-mono text-center">Clean_Action</span>
                      <div class="flex gap-1 justify-center">
                        <button @click="clearData('QUEUE')" class="p-1 border border-medical-200 text-[8px] font-bold hover:bg-red-50 hover:text-red-500 transition-all">Q</button>
                        <button @click="clearData('OFFLINE')" class="p-1 border border-medical-200 text-[8px] font-bold hover:bg-red-50 hover:text-red-500 transition-all">O</button>
                        <button @click="clearData('CHAT')" class="p-1 border border-medical-200 text-[8px] font-bold hover:bg-red-50 hover:text-red-500 transition-all">C</button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Section: System Parameters (APIs & Limits) -->
              <div class="bg-white border border-medical-200 shadow-sm overflow-hidden">
                <div class="p-3 bg-medical-600 text-white flex items-center gap-2">
                  <Sliders class="w-4 h-4" />
                  <span class="text-xs font-bold uppercase tracking-widest font-mono">System_Parameters</span>
                </div>
                <div class="p-4 space-y-4">
                  <div class="grid grid-cols-2 gap-x-4 gap-y-3">
                    <div v-for="(val, key) in systemFields" :key="key" class="space-y-1">
                      <label class="block text-[9px] font-bold text-medical-400 font-mono">{{ key.toUpperCase() }}</label>
                      <input v-model.number="configProxy[val.field]" type="number" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-xs outline-none focus:border-accent" />
                    </div>
                  </div>
                  <!-- Platform Toggles in Params -->
                  <div class="flex gap-4 pt-2 border-t border-medical-100">
                    <div class="flex items-center gap-2">
                      <span class="text-[9px] font-bold text-medical-400 font-mono">NETEASE:</span>
                      <button @click="configProxy.neteaseEnabled = !configProxy.neteaseEnabled" class="w-8 h-4 rounded-full relative transition-colors" :class="configProxy.neteaseEnabled ? 'bg-accent' : 'bg-medical-300'">
                        <div class="absolute top-0.5 w-3 h-3 bg-white rounded-full transition-all" :class="configProxy.neteaseEnabled ? 'left-4.5' : 'left-0.5'"></div>
                      </button>
                    </div>
                    <div class="flex items-center gap-2">
                      <span class="text-[9px] font-bold text-medical-400 font-mono">BILIBILI:</span>
                      <button @click="configProxy.bilibiliEnabled = !configProxy.bilibiliEnabled" class="w-8 h-4 rounded-full relative transition-colors" :class="configProxy.bilibiliEnabled ? 'bg-accent' : 'bg-medical-300'">
                        <div class="absolute top-0.5 w-3 h-3 bg-white rounded-full transition-all" :class="configProxy.bilibiliEnabled ? 'left-4.5' : 'left-0.5'"></div>
                      </button>
                    </div>
                  </div>
                  <button @click="saveSystemConfig" class="w-full bg-medical-900 text-white py-2 text-xs font-bold hover:bg-accent transition-colors flex items-center justify-center gap-2">
                    <Save class="w-4 h-4" /> COMMIT_CHANGES
                  </button>
                </div>
              </div>

              <!-- Danger Zone -->
              <div class="p-4 bg-red-50 border border-red-100 chamfer-br flex flex-col gap-3">
                <h4 class="text-red-600 text-[10px] font-black flex items-center gap-2 uppercase tracking-tighter">
                  <AlertTriangle class="w-4 h-4" /> DANGER_ZONE.SH
                </h4>
                <button @click="handleReset" class="w-full py-2 bg-red-600 text-white font-bold text-[10px] hover:bg-red-700 transition-all uppercase tracking-widest shadow-sm">
                  Full System Reset
                </button>
              </div>
            </div>
          </div>

        </div>

        <!-- Footer -->
        <div class="p-3 bg-medical-100 border-t border-medical-200 flex justify-between items-center text-[9px] font-mono text-medical-400">
          <span class="flex items-center gap-1"><ShieldCheck class="w-3 h-3 text-green-500" /> SECURE_CONNECTION: AES-256-GCM</span>
          <span>ADMIN_HASH: {{ adminStore.adminPassword.substring(0, 4).toUpperCase() }}****</span>
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
  Settings, X, Pause, Play, SkipForward, Shuffle, 
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
  queue_max: { field: 'maxQueueSize' },
  hist_size: { field: 'maxHistorySize' },
  user_songs: { field: 'maxUserSongs' },
  import_lim: { field: 'maxPlaylistImportSize' },
  chat_hist: { field: 'maxChatHistorySize' },
  chat_rate: { field: 'minChatIntervalMs' }
};

watch(() => playerStore.config, (newVal) => {
  configProxy.value = { ...newVal };
}, { deep: true });

const saveSystemConfig = async () => {
  try {
    await adminApi.updateConfig(adminStore.adminPassword, configProxy.value);
    success('SYSTEM_PARAMETERS_UPDATED');
  } catch (e) {
    error('CONFIG_SYNC_FAILED');
  }
};

const locks = computed(() => [
  { key: 'PAUSE', label: 'Lock Pause', value: playerStore.isPauseLocked },
  { key: 'SKIP', label: 'Lock Skip', value: playerStore.isSkipLocked },
  { key: 'SHUFFLE', label: 'Lock Shuffle', value: playerStore.isShuffleLocked },
]);

const platforms = ref([
  { id: 'netease', name: 'Netease', tokenName: 'COOKIE', value: '' },
  { id: 'bilibili', name: 'Bilibili', tokenName: 'SESSDATA', value: '' }
]);

const execPlayerAction = async (action) => {
  try {
    await adminApi.playerAction(adminStore.adminPassword, action);
    success(`PLAYER_${action}_EXECUTED`);
  } catch (e) {
    error('COMMAND_FAILED');
  }
};

const toggleLock = async (type, locked) => {
  try {
    await adminApi.setLock(adminStore.adminPassword, type, locked);
    success(`${type}_LOCK_STATE_MODIFIED`);
  } catch (e) {
    error('LOCK_SYNC_FAILED');
  }
};

const updateRoomPassword = async () => {
  try {
    await adminApi.setRoomPassword(adminStore.adminPassword, roomPassword.value);
    success('ROOM_PASSWORD_SYNCHRONIZED');
  } catch (e) {
    error('PASSWORD_UPDATE_FAILED');
  }
};

const toggleStream = async () => {
  try {
    await adminApi.setStream(adminStore.adminPassword, !playerStore.streamActive);
    success('STREAM_SERVICE_UPDATED');
  } catch (e) {
    error('STREAM_CONTROL_FAILED');
  }
};

const clearData = async (target) => {
  if (!confirm(`Are you sure you want to clear ${target}?`)) return;
  try {
    await adminApi.clearData(adminStore.adminPassword, target);
    warning(`${target}_DATA_PURGED`);
  } catch (e) {
    error('PURGE_FAILED');
  }
};

const updateCookie = async (platform, value) => {
  if (!value) return;
  try {
    await adminApi.setCookie(adminStore.adminPassword, platform, value);
    success(`${platform.toUpperCase()}_CREDENTIALS_UPDATED`);
  } catch (e) {
    error('CONFIG_UPDATE_FAILED');
  }
};

const handleReset = async () => {
  if (!confirm('!!! WARNING !!! \nTHIS WILL RESET THE ENTIRE SYSTEM. \nARE YOU ABSOLUTELY SURE?')) return;
  try {
    await adminApi.resetSystem(adminStore.adminPassword);
    warning('SYSTEM_PURGED_AND_RESTARTED');
  } catch (e) {
    error('RESET_FAILED');
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
