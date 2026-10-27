<template>
  <div class="fixed inset-0 z-[80] bg-medical-900/80 backdrop-blur-sm flex items-center justify-center p-3 md:p-6">
    <section role="dialog" aria-modal="true" aria-labelledby="album-title" class="w-full max-w-2xl max-h-[85dvh] flex flex-col bg-medical-50 border border-medical-200 shadow-2xl chamfer-br">
      <header class="flex justify-between items-start gap-3 p-4 md:p-6 bg-white border-b border-medical-200 flex-shrink-0">
        <div class="flex gap-3 min-w-0">
          <CoverImage v-if="album" :src="album.coverUrl" class="w-16 h-16 flex-shrink-0" :scanline="false" />
          <div class="min-w-0">
            <div class="text-xs text-accent font-bold mb-1">整张专辑</div>
            <h3 id="album-title" class="font-bold text-lg md:text-xl text-medical-900 break-words">{{ album?.name || '读取专辑' }}</h3>
            <p v-if="album" class="text-xs text-medical-500 mt-1 break-words">{{ album.artists.join(' / ') }} · {{ album.trackCount }} 首</p>
          </div>
        </div>
        <button @click="emit('close')" :disabled="importing" aria-label="关闭专辑" class="p-1 text-medical-500 hover:text-accent disabled:opacity-40"><X class="w-5 h-5" /></button>
      </header>
      <div class="flex-1 min-h-0 overflow-y-auto overscroll-contain p-4 md:px-6">
        <div v-if="loading" role="status" class="py-10 text-center text-accent flex justify-center items-center gap-2"><Loader2 class="w-5 h-5 animate-spin" />正在读取专辑…</div>
        <div v-else-if="loadError" role="alert" class="py-8 text-center">
          <p class="text-sm text-red-600">{{ loadError }}</p>
          <button @click="loadAlbum" class="mt-4 px-4 py-2 bg-medical-900 text-white text-sm">重新读取</button>
        </div>
        <ol v-else-if="album" class="space-y-1" aria-label="专辑曲目">
          <li v-for="(song, index) in album.songs" :key="song.id" class="flex gap-3 items-center p-3 bg-white border border-medical-100">
            <span class="w-5 flex-shrink-0 text-xs text-medical-400 font-mono">{{ index + 1 }}</span>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-bold break-words">{{ song.name }}</div>
              <div class="text-xs text-medical-500 truncate">{{ song.artists.join(' / ') }}</div>
            </div>
            <span v-if="inQueue(song.id)" class="flex-shrink-0 text-xs text-green-600">已在队列</span>
          </li>
        </ol>
      </div>
      <footer v-if="album" class="p-4 md:px-6 bg-white border-t border-medical-200 flex-shrink-0">
        <p class="text-xs text-medical-500 mb-3">按专辑曲序添加，自动跳过队列中的歌曲；实际数量受房间导入、点歌和队列上限限制。</p>
        <p v-if="feedback" :role="importError ? 'alert' : 'status'" class="text-sm mb-3 break-words" :class="importError ? 'text-red-600' : 'text-green-700'">{{ feedback }}</p>
        <button @click="importAlbum" :disabled="importing || allInQueue || !playerStore.connected" class="w-full flex justify-center items-center gap-2 px-4 py-3 bg-accent hover:bg-accent-hover text-white font-bold text-sm disabled:opacity-50 disabled:cursor-not-allowed">
          <Loader2 v-if="importing" class="w-4 h-4 animate-spin" /><ListPlus v-else class="w-4 h-4" />
          {{ importing ? '正在添加…' : allInQueue ? '全部已在队列' : !playerStore.connected ? '等待房间连接…' : `添加整张专辑（${album.trackCount} 首）` }}
        </button>
      </footer>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { X, ListPlus, Loader2 } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import { musicApi } from '../api/music';
import { usePlayerStore } from '../stores/player';

const props = defineProps({ songId: { type: String, required: true } });
const emit = defineEmits(['close']);
const playerStore = usePlayerStore();
const album = ref(null), loading = ref(true), loadError = ref('');
const importing = ref(false), feedback = ref(''), importError = ref(false);
let active = true;
const inQueue = id => playerStore.queue.some(item => item.music.platform === 'netease' && item.music.id === id);
const allInQueue = computed(() => album.value?.songs.length > 0 && album.value.songs.every(song => inQueue(song.id)));
const loadAlbum = async () => {
  loading.value = true; loadError.value = '';
  try {
    const result = await musicApi.getSongAlbum(props.songId);
    if (active) album.value = result;
  } catch (error) {
    if (active) loadError.value = error.response?.data?.message || error.message || '专辑读取失败，请稍后重试';
  } finally { if (active) loading.value = false; }
};
const importAlbum = async () => {
  if (importing.value || !album.value || allInQueue.value) return;
  importing.value = true; importError.value = false; feedback.value = '';
  try {
    const result = await playerStore.enqueueAlbum(album.value.id);
    if (active) feedback.value = result.message;
  } catch (error) {
    if (active) { importError.value = true; feedback.value = error.message; }
  } finally { if (active) importing.value = false; }
};
onMounted(loadAlbum);
onUnmounted(() => { active = false; });
</script>
