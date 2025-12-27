<template>
  <div class="p-4">
    <div class="mb-4 flex items-center justify-between">
        <h3 class="font-mono text-xs font-bold text-medical-400">ONLINE_OPERATIVES</h3>
        <div class="text-xs font-mono bg-accent/10 text-accent px-1">{{ users.length }}</div>
    </div>
    
    <div class="space-y-3">
        <!-- 自己 -->
        <div class="flex items-center gap-3 pb-3 border-b border-medical-200 border-dashed">
            <div class="w-8 h-8 bg-medical-900 text-white flex items-center justify-center font-bold text-xs">ME</div>
            <div class="flex-1 min-w-0">
                <input 
                    v-model="newName" 
                    @blur="doRename"
                    @keyup.enter="doRename"
                    class="w-full bg-transparent border-b border-transparent focus:border-accent outline-none text-sm font-bold"
                />
            </div>
            <div class="w-2 h-2 bg-green-500 rounded-full animate-pulse"></div>
        </div>

        <!-- 其他人 -->
        <div v-for="u in others" :key="u.sessionId" class="flex items-center gap-3 opacity-60">
             <div class="w-8 h-8 bg-medical-200 text-medical-500 flex items-center justify-center font-bold text-xs">OP</div>
             <div class="text-sm font-bold truncate flex-1">{{ u.name }}</div>
             <div class="w-2 h-2 bg-medical-300 rounded-full"></div>
        </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';

const userStore = useUserStore();
const playerStore = usePlayerStore();
const users = computed(() => userStore.onlineUsers);
const me = computed(() => userStore.currentUser);
const newName = ref(me.value.name);

watch(() => me.value.name, (n) => newName.value = n);

const others = computed(() => users.value.filter(u => u.sessionId !== me.value.sessionId));

const doRename = () => {
    if(newName.value && newName.value !== me.value.name) {
        playerStore.renameUser(newName.value);
    }
};
</script>