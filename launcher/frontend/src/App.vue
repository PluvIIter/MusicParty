<script setup>
import { ref, onMounted, reactive } from 'vue';
import { LoadConfig, SaveConfig, StartServices, StopServices } from './wailsjs/go/main/App';
import { EventsOn } from './wailsjs/runtime';

const config = reactive({
  serverIp: '0.0.0.0',
  serverPort: '8848',
  adminPassword: '',
  neteaseCookie: '',
  biliSessData: '',
  ffmpegPath: ''
});

const isRunning = ref(false);
const logs = ref([]);
const logContainer = ref(null);

onMounted(async () => {
  const cfg = await LoadConfig();
  Object.assign(config, cfg);
  
  EventsOn("log", (msg) => {
    logs.value.push({
      id: Date.now() + Math.random(),
      text: msg
    });
    if (logs.value.length > 500) logs.value.shift();
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
  await StartServices();
};

const handleStop = async () => {
  await StopServices();
  isRunning.value = false;
};
</script>

<template>
  <div class="h-screen flex flex-col p-6 space-y-6">
    <!-- Header -->
    <div class="flex justify-between items-end border-b-2 border-medical-900 pb-4">
      <div>
        <h1 class="text-3xl font-black tracking-tighter">MUSIC PARTY</h1>
        <p class="text-[10px] font-mono text-medical-400 mt-1 uppercase tracking-widest">> DEPLOYMENT TERMINAL v1.0</p>
      </div>
      <div class="flex items-center gap-4">
        <div class="flex flex-col items-end">
          <span class="text-[9px] font-bold text-medical-400 uppercase">System Status</span>
          <span :class="isRunning ? 'text-green-500' : 'text-red-500'" class="text-xs font-black font-mono">
            {{ isRunning ? '● RUNNING' : '○ STANDBY' }}
          </span>
        </div>
      </div>
    </div>

    <!-- Main Content -->
    <div class="flex-1 min-h-0 flex gap-6">
      <!-- Left: Settings -->
      <div class="w-80 flex flex-col gap-4">
        <div class="space-y-4 bg-white p-4 border border-medical-200 shadow-sm">
           <div class="space-y-1">
             <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">Bind IP</label>
             <input v-model="config.serverIp" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-sm font-mono outline-none focus:border-accent" />
           </div>
           <div class="space-y-1">
             <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">Port</label>
             <input v-model="config.serverPort" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-sm font-mono outline-none focus:border-accent" />
           </div>
           <div class="space-y-1">
             <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">Admin Password</label>
             <input v-model="config.adminPassword" type="password" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-sm outline-none focus:border-accent" />
           </div>
        </div>

        <div class="flex-1 bg-white p-4 border border-medical-200 shadow-sm space-y-4">
           <div class="space-y-1">
             <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">Netease Cookie (Optional)</label>
             <textarea v-model="config.neteaseCookie" rows="2" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-[10px] font-mono outline-none focus:border-accent resize-none"></textarea>
           </div>
           <div class="space-y-1">
             <label class="text-[10px] font-bold text-medical-500 uppercase font-mono">Bili SESSDATA (Optional)</label>
             <input v-model="config.biliSessData" class="w-full bg-medical-50 border border-medical-200 px-2 py-1 text-[10px] font-mono outline-none focus:border-accent" />
           </div>
        </div>

        <button 
          v-if="!isRunning"
          @click="handleStart"
          class="w-full py-4 bg-medical-900 text-white font-black text-xl hover:bg-accent transition-all chamfer-br active:scale-95 shadow-lg shadow-medical-200"
        >
          INITIALIZE
        </button>
        <button 
          v-else
          @click="handleStop"
          class="w-full py-4 bg-red-600 text-white font-black text-xl hover:bg-red-700 transition-all chamfer-br active:scale-95 shadow-lg shadow-red-100"
        >
          TERMINATE
        </button>
      </div>

      <!-- Right: Terminal -->
      <div class="flex-1 flex flex-col bg-medical-900 chamfer-br p-4 overflow-hidden border-2 border-medical-900 shadow-inner">
        <div class="flex justify-between items-center mb-2 border-b border-white/10 pb-2">
          <span class="text-[10px] font-mono text-white/40 uppercase tracking-widest">Execution Log</span>
          <span class="text-[10px] font-mono text-accent animate-pulse">READY_</span>
        </div>
        <div ref="logContainer" class="flex-1 overflow-y-auto space-y-1 terminal-scroll">
          <div v-for="log in logs" :key="log.id" class="text-[11px] font-mono leading-relaxed">
            <span class="text-white/30 mr-2">[{{ new Date().toLocaleTimeString() }}]</span>
            <span :class="{
              'text-white': !log.text.includes('ERROR') && !log.text.includes('SYSTEM'),
              'text-red-400': log.text.includes('ERROR'),
              'text-accent': log.text.includes('SYSTEM')
            }">{{ log.text }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="flex justify-between text-[9px] font-mono text-medical-400 uppercase tracking-widest">
      <span>Core Architecture: SpringBoot + Node.js + Go</span>
      <span>Build: 2024.AUTO_EDIT</span>
    </div>
  </div>
</template>
