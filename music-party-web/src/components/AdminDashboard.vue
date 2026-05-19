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
      <div class="w-full max-w-4xl h-[90vh] bg-medical-50 shadow-2xl border border-medical-200 flex flex-col chamfer-br overflow-hidden">
        
        <!-- Header -->
        <div class="p-4 md:p-6 bg-white border-b border-medical-200 flex justify-between items-center flex-shrink-0">
          <div>
            <h2 class="text-2xl font-black font-mono text-medical-900 flex items-center gap-3">
              <Settings class="w-6 h-6 text-accent animate-spin-slow" /> ADMIN_TERMINAL
            </h2>
            <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-[0.2em]">> SYSTEM CONTROL INTERFACE v2.0</p>
          </div>
          <div class="flex items-center gap-4">
            <button @click="adminStore.logout" class="text-xs font-bold text-red-500 hover:underline uppercase font-mono">Terminate Session</button>
            <button @click="adminStore.showDashboard = false" class="p-2 bg-medical-100 hover:bg-medical-200 text-medical-900 transition-colors">
              <X class="w-6 h-6" />
            </button>
          </div>
        </div>

        <!-- Content Area -->
        <div class="flex-1 overflow-y-auto p-4 md:p-8 space-y-8 custom-scroll">
          
          <!-- Row 1: Player & Locks -->
          <div class="grid grid-cols-1 md:grid-cols-2 gap-8">
            
            <!-- Player Override -->
            <div class="space-y-4">
              <div class="flex items-center gap-2 mb-2">
                <div class="w-1 h-4 bg-accent"></div>
                <h3 class="text-xs font-bold text-medical-600 uppercase tracking-widest font-mono">Player Override</h3>
              </div>
              <div class="grid grid-cols-3 gap-3">
                <button @click="execPlayerAction('PAUSE')" class="flex flex-col items-center justify-center p-4 bg-white border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                  <Pause v-if="!playerStore.isPaused" class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                  <Play v-else class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                  <span class="text-[10px] font-bold font-mono">{{ playerStore.isPaused ? 'RESUME' : 'PAUSE' }}</span>
                </button>
                <button @click="execPlayerAction('SKIP')" class="flex flex-col items-center justify-center p-4 bg-white border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                  <SkipForward class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                  <span class="text-[10px] font-bold font-mono">SKIP</span>
                </button>
                <button @click="execPlayerAction('SHUFFLE')" class="flex flex-col items-center justify-center p-4 bg-white border border-medical-200 hover:border-accent hover:text-accent transition-all group">
                  <Shuffle class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform" />
                  <span class="text-[10px] font-bold font-mono">SHUFFLE</span>
                </button>
              </div>
            </div>

            <!-- Permission Locks -->
            <div class="space-y-4">
              <div class="flex items-center gap-2 mb-2">
                <div class="w-1 h-4 bg-accent"></div>
                <h3 class="text-xs font-bold text-medical-600 uppercase tracking-widest font-mono">User Restrictions</h3>
              </div>
              <div class="space-y-2">
                <div v-for="lock in locks" :key="lock.key" class="flex items-center justify-between p-3 bg-white border border-medical-200">
                  <div class="flex items-center gap-3">
                    <component :is="lock.icon" class="w-4 h-4 text-medical-400" />
                    <span class="text-sm font-bold text-medical-800">{{ lock.label }}</span>
                  </div>
                  <button 
                    @click="toggleLock(lock.key, !lock.value)"
                    class="w-12 h-6 rounded-full transition-colors relative"
                    :class="lock.value ? 'bg-red-500' : 'bg-medical-200'"
                  >
                    <div class="absolute top-1 w-4 h-4 bg-white rounded-full transition-all" :class="lock.value ? 'left-7' : 'left-1'"></div>
                  </button>
                </div>
              </div>
            </div>

          </div>

          <!-- Row 2: Room Settings -->
          <div class="space-y-4">
            <div class="flex items-center gap-2 mb-2">
              <div class="w-1 h-4 bg-accent"></div>
              <h3 class="text-xs font-bold text-medical-600 uppercase tracking-widest font-mono">Room Environment</h3>
            </div>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
              <!-- Room Password -->
              <div class="p-4 bg-white border border-medical-200 space-y-3">
                <label class="block text-[10px] font-bold text-medical-400 uppercase font-mono">Room Password</label>
                <div class="flex gap-2">
                  <input v-model="roomPassword" placeholder="LEAVE BLANK FOR PUBLIC" class="flex-1 bg-medical-50 border border-medical-200 px-3 py-2 text-sm outline-none focus:border-accent" />
                  <button @click="updateRoomPassword" class="bg-medical-900 text-white px-4 py-2 text-xs font-bold hover:bg-accent transition-colors">UPDATE</button>
                </div>
                <p class="text-[9px] text-medical-400 italic">* Empty password allows anyone to enter without authentication.</p>
              </div>
              <!-- Stream & Clear -->
              <div class="grid grid-cols-2 gap-4">
                <div class="p-4 bg-white border border-medical-200 flex flex-col justify-between">
                   <label class="block text-[10px] font-bold text-medical-400 uppercase font-mono mb-2">Live Stream</label>
                   <div class="flex items-center justify-between">
                     <span class="text-xs font-bold">{{ playerStore.streamActive ? 'ACTIVE' : 'DISABLED' }}</span>
                     <button 
                        @click="toggleStream"
                        class="w-12 h-6 rounded-full transition-colors relative"
                        :class="playerStore.streamActive ? 'bg-green-500' : 'bg-medical-200'"
                      >
                        <div class="absolute top-1 w-4 h-4 bg-white rounded-full transition-all" :class="playerStore.streamActive ? 'left-7' : 'left-1'"></div>
                      </button>
                   </div>
                </div>
                <div class="p-4 bg-white border border-medical-200 flex flex-col justify-between">
                   <label class="block text-[10px] font-bold text-medical-400 uppercase font-mono mb-2">Clean Up</label>
                   <div class="flex gap-2">
                     <button @click="clearData('QUEUE')" class="flex-1 py-1 border border-medical-200 text-[10px] font-bold hover:bg-red-50 hover:text-red-500 transition-colors">QUEUE</button>
                     <button @click="clearData('CHAT')" class="flex-1 py-1 border border-medical-200 text-[10px] font-bold hover:bg-red-50 hover:text-red-500 transition-colors">CHAT</button>
                   </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Row 3: Cookies -->
          <div class="space-y-4">
            <div class="flex items-center gap-2 mb-2">
              <div class="w-1 h-4 bg-accent"></div>
              <h3 class="text-xs font-bold text-medical-600 uppercase tracking-widest font-mono">External API Configuration</h3>
            </div>
            <div class="space-y-4">
              <div v-for="plat in platforms" :key="plat.id" class="p-4 bg-white border border-medical-200">
                <div class="flex justify-between items-center mb-2">
                  <span class="text-xs font-bold uppercase font-mono">{{ plat.name }} {{ plat.tokenName }}</span>
                  <span class="text-[9px] text-medical-400 font-mono">STATUS: CONNECTED</span>
                </div>
                <div class="flex gap-2">
                  <textarea 
                    v-model="plat.value" 
                    :placeholder="'PASTE ' + plat.tokenName + ' HERE...'" 
                    rows="2"
                    class="flex-1 bg-medical-50 border border-medical-200 px-3 py-2 text-xs outline-none focus:border-accent font-mono resize-none"
                  ></textarea>
                  <button @click="updateCookie(plat.id, plat.value)" class="bg-medical-900 text-white px-4 font-bold text-xs hover:bg-accent transition-colors flex items-center gap-2">
                    <Save class="w-3 h-3" /> SAVE
                  </button>
                </div>
              </div>
            </div>
          </div>

          <!-- Danger Zone -->
          <div class="pt-8 border-t border-medical-200">
            <div class="p-6 bg-red-50 border border-red-200 chamfer-br flex flex-col md:flex-row items-center justify-between gap-4">
              <div>
                <h4 class="text-red-600 font-bold flex items-center gap-2">
                  <AlertTriangle class="w-5 h-5" /> DANGER_ZONE.SH
                </h4>
                <p class="text-xs text-red-400 mt-1">Purge all system states, clear queues, history, and reset player anchors. Use with extreme caution.</p>
              </div>
              <button @click="handleReset" class="px-8 py-3 bg-red-600 text-white font-black text-sm hover:bg-red-700 transition-all active:scale-95 shadow-lg shadow-red-200 uppercase tracking-widest">
                Execute System Reset
              </button>
            </div>
          </div>

        </div>

        <!-- Footer -->
        <div class="p-4 bg-medical-100 border-t border-medical-200 flex justify-between items-center text-[10px] font-mono text-medical-400">
          <span>SECURE_CONNECTION: AES-256-GCM</span>
          <span>ADMIN_UID: {{ adminStore.adminPassword.substring(0, 4).toUpperCase() }}****</span>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useAdminStore } from '../stores/admin';
import { usePlayerStore } from '../stores/player';
import { adminApi } from '../api/admin';
import { useToast } from '../composables/useToast';
import { 
  Settings, X, Pause, Play, SkipForward, Shuffle, 
  Lock, Unlock, ShieldAlert, Save, AlertTriangle 
} from 'lucide-vue-next';

const adminStore = useAdminStore();
const playerStore = usePlayerStore();
const { success, error, warning } = useToast();

const roomPassword = ref('');

const locks = computed(() => [
  { key: 'PAUSE', label: 'Lock Pause/Play', value: playerStore.isPauseLocked, icon: Pause },
  { key: 'SKIP', label: 'Lock Skip Function', value: playerStore.isSkipLocked, icon: SkipForward },
  { key: 'SHUFFLE', label: 'Lock Shuffle Mode', value: playerStore.isShuffleLocked, icon: Shuffle },
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
    success(`${type}_LOCK_${locked ? 'ENABLED' : 'DISABLED'}`);
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
.custom-scroll::-webkit-scrollbar { width: 6px; }
.custom-scroll::-webkit-scrollbar-track { background: transparent; }
.custom-scroll::-webkit-scrollbar-thumb { @apply bg-medical-200 rounded-full; }
.custom-scroll::-webkit-scrollbar-thumb:hover { @apply bg-medical-300; }
</style>