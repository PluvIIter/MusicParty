<template>
  <section aria-label="收藏专辑" class="flex-1 min-h-0 flex flex-col w-full">
    <header class="p-3 md:p-4 bg-white border-b border-medical-200 flex gap-3 items-start justify-between flex-shrink-0">
      <div class="min-w-0">
        <h3 class="font-bold text-medical-900">收藏专辑 <span v-if="total !== null" class="text-xs text-medical-500">{{ albums.length }} / {{ total }}</span></h3>
        <p class="text-xs text-medical-500 mt-1">房间当前 Cookie 登录账号的网易云收藏，与绑定的歌单用户 ID 无关。</p>
      </div>
      <button @click="load(true)" :disabled="loading" class="flex-shrink-0 px-3 py-2 text-xs font-bold border border-medical-200 hover:text-accent disabled:opacity-40">刷新收藏</button>
    </header>
    <div class="flex-1 min-h-0 overflow-y-auto overscroll-contain p-3 md:p-4">
      <div v-if="loading && !albums.length" role="status" class="text-center py-10 text-sm text-accent">正在读取收藏专辑…</div>
      <div v-if="error" role="alert" class="p-4 mb-3 bg-white border border-red-200 text-sm text-red-600">
        <p>{{ error }}</p>
        <button @click="load(albums.length === 0)" :disabled="loading" class="mt-3 px-3 py-2 bg-medical-900 text-white disabled:opacity-40">重试读取收藏</button>
      </div>
      <p v-if="!loading && !error && !albums.length" class="py-10 text-center text-sm text-medical-500">当前账号还没有收藏专辑。可先在网易云收藏，再点“刷新收藏”。</p>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-2">
        <button v-for="album in albums" :key="album.id" @click="selected = album.id" :aria-label="'查看专辑：' + album.name"
            class="flex gap-3 items-center p-3 min-w-0 text-left bg-white border border-medical-200 hover:border-accent group">
          <CoverImage :src="album.coverUrl" class="w-14 h-14 flex-shrink-0" :scanline="false" />
          <div class="min-w-0 flex-1">
            <div class="font-bold text-sm break-words group-hover:text-accent">{{ album.name }}</div>
            <div class="text-xs text-medical-500 truncate mt-1">{{ album.artists.join(' / ') }}</div>
            <div class="text-xs text-medical-400 mt-1">{{ album.trackCount }} 首</div>
          </div>
          <ChevronRight class="w-4 h-4 flex-shrink-0 text-medical-400" />
        </button>
      </div>
      <div v-if="albums.length" class="py-4 text-center">
        <button v-if="hasMore" @click="load(false)" :disabled="loading" class="px-5 py-2 text-sm font-bold bg-medical-900 text-white disabled:opacity-40">{{ loading ? '正在读取…' : '加载更多专辑' }}</button>
        <p v-else class="text-xs text-medical-400">已显示全部收藏专辑</p>
      </div>
    </div>
    <AlbumModal v-if="selected" :key="selected" :album-id="selected" @close="selected = ''" />
  </section>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { ChevronRight } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import AlbumModal from './AlbumModal.vue';
import { musicApi } from '../api/music';

const albums = ref([]), total = ref(null), hasMore = ref(false);
const loading = ref(false), error = ref(''), selected = ref('');
let offset = 0, active = true;
const load = async (reset = false) => {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  if (reset) { offset = 0; albums.value = []; total.value = null; hasMore.value = false; }
  try {
    const page = await musicApi.getSavedAlbums(offset, 25);
    if (!active) return;
    const seen = new Set(albums.value.map(album => album.id));
    albums.value.push(...page.items.filter(album => !seen.has(album.id) && seen.add(album.id)));
    total.value = page.total;
    hasMore.value = page.hasMore && page.nextOffset > offset;
    offset = page.nextOffset;
  } catch (e) {
    if (active) error.value = e.response?.data?.message || '收藏专辑读取失败，请稍后重试';
  } finally { if (active) loading.value = false; }
};
onMounted(() => load(true));
onUnmounted(() => { active = false; });
</script>
