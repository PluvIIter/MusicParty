<template>
  <div
      class="group relative flex items-center gap-2 p-2 bg-white border border-medical-100 hover:border-medical-300 transition-all mb-2 h-14"
  >
    <!-- 序号 -->
    <div v-if="index !== undefined" class="w-6 text-center font-mono text-xs text-medical-400">{{ String(index + 1).padStart(2, '0') }}</div>
    <div v-else class="w-6 text-center font-mono text-xs text-medical-400">#</div>

    <div class="flex-1 min-w-0">
      <div class="text-sm font-bold text-medical-800 truncate">{{ item.music.name }}</div>
      <div class="flex justify-between items-center">
        <div v-if="!item.status || item.status === 'READY'" class="text-xs text-medical-500 truncate">
          {{ item.music.artists[0] }}
        </div>

        <!-- 下载中状态：显示闪烁的 LOADING -->
        <div v-else-if="item.status === 'DOWNLOADING' || item.status === 'PENDING'" class="text-xs font-mono font-bold text-accent animate-pulse flex items-center gap-1">
          <Loader2 class="w-3 h-3 animate-spin" /> LOADING...
        </div>

        <!-- 失败状态 -->
        <div v-else-if="item.status === 'FAILED'" class="text-xs font-mono font-bold text-red-500">
          DOWNLOAD FAILED
        </div>
        <div class="text-[10px] text-medical-300 bg-medical-50 px-1 border border-medical-100">
          {{ userStore.resolveName(item.enqueuedBy.token, item.enqueuedBy.name) }}
        </div>
      </div>
    </div>

    <!-- 操作遮罩 -->
    <div v-if="!userStore.isGuest" class="absolute inset-y-0 right-0 bg-white/90 px-2 flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
      <button @click="player.topSong(item.queueId)" title="Top" class="p-1 hover:text-accent"><ArrowUpToLine class="w-4 h-4"/></button>
      <button @click="player.removeSong(item.queueId)" title="Remove" class="p-1 hover:text-red-500"><Trash2 class="w-4 h-4"/></button>
    </div>

    <!-- 标记置顶的歌曲 -->
    <div v-if="item.queueId.startsWith('TOP-')" class="absolute top-0 right-0 w-2 h-2 bg-accent"></div>
  </div>
</template>

<script setup>
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { Trash2, ArrowUpToLine, Loader2 } from 'lucide-vue-next';

const props = defineProps({
  item: {
    type: Object,
    required: true
  },
  index: {
    type: Number,
    default: undefined
  }
});

const player = usePlayerStore();
const userStore = useUserStore();
</script>