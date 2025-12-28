<template>
  <div class="h-full flex flex-col bg-white border-l border-medical-200">
     <div class="p-4 bg-medical-50 border-b border-medical-200 flex justify-between items-center">
         <h3 class="font-mono font-bold text-medical-900">播放队列 <span class="text-accent text-xs">[{{ queue.length }}]</span></h3>
     </div>
     
     <div class="flex-1 overflow-y-auto p-2 space-y-2">
         <div v-if="queue.length === 0" class="text-center py-10 text-xs font-mono text-medical-400">
             QUEUE EMPTY / WAITING FOR INPUT
         </div>

         <div 
            v-for="(item, idx) in queue" 
            :key="item.queueId"
            class="group relative flex items-center gap-2 p-2 bg-white border border-medical-100 hover:border-medical-300 transition-all"
         >
            <!-- 序号 -->
            <div class="w-6 text-center font-mono text-xs text-medical-400">{{ String(idx + 1).padStart(2, '0') }}</div>
            
            <div class="flex-1 min-w-0">
                <div class="text-sm font-bold text-medical-800 truncate">{{ item.music.name }}</div>
                <div class="flex justify-between items-center">
                   <div class="text-xs text-medical-500 truncate">{{ item.music.artists[0] }}</div>
                   <div class="text-[10px] text-medical-300 bg-medical-50 px-1 border border-medical-100">{{ item.enqueuedBy.name }}</div>
                </div>
            </div>

            <!-- 操作遮罩 -->
            <div class="absolute inset-y-0 right-0 bg-white/90 px-2 flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
                <button @click="player.topSong(item.queueId)" title="Top" class="p-1 hover:text-accent"><ArrowUpToLine class="w-4 h-4"/></button>
                <button @click="player.removeSong(item.queueId)" title="Remove" class="p-1 hover:text-red-500"><Trash2 class="w-4 h-4"/></button>
            </div>
            
            <!-- 标记置顶的歌曲 -->
            <div v-if="item.queueId.startsWith('TOP-')" class="absolute top-0 right-0 w-2 h-2 bg-accent"></div>
         </div>
     </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { usePlayerStore } from '../stores/player';
import { Trash2, ArrowUpToLine } from 'lucide-vue-next';

const player = usePlayerStore();
const queue = computed(() => player.queue);
</script>