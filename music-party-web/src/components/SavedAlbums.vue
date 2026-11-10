<template>
    <section aria-label="收藏专辑" :class="embedded ? 'flex flex-col w-full' : 'flex-1 min-h-0 flex flex-col w-full'">
        <header
            :class="
                embedded
                    ? 'pb-1.5 flex items-center justify-between gap-2 flex-shrink-0'
                    : 'p-3 md:p-4 bg-surface-raised border-b border-line flex gap-3 items-start justify-between flex-shrink-0'
            "
        >
            <div class="min-w-0">
                <h3 class="font-bold text-ink" :class="embedded ? 'text-[11px]' : ''">
                    收藏专辑

                </h3>
                <p v-if="!embedded" class="text-xs text-ink-muted mt-1">
                    房间当前 Cookie 登录账号的网易云收藏，与绑定的歌单用户 ID 无关。
                </p>
            </div>

        </header>
        <div :class="embedded ? 'overflow-visible' : 'flex-1 min-h-0 overflow-y-auto overscroll-contain p-3 md:p-4'">
            <div v-if="loading && !albums.length" role="status" class="text-center py-10 text-sm text-accent">
                正在读取收藏专辑…
            </div>
            <div
                v-if="error"
                role="alert"
                class="p-4 mb-3 bg-surface-raised border border-danger/30 text-sm text-danger"
            >
                <p>{{ error }}</p>
                <button
                    @click="load(albums.length === 0)"
                    :disabled="loading"
                    class="mt-3 px-3 py-2 bg-brand text-ink-inverse disabled:opacity-40"
                >
                    重试读取收藏
                </button>
            </div>
            <p v-if="!loading && !error && !albums.length" class="py-10 text-center text-sm text-ink-muted">
                当前账号还没有收藏专辑。可先在网易云收藏，再点侧栏右上角的「刷新」。
            </p>
            <div :class="embedded ? 'flex flex-col gap-1.5' : 'grid grid-cols-1 md:grid-cols-2 gap-2'">
                <button
                    v-for="album in albums"
                    :key="album.id"
                    @click="selected = album.id"
                    :aria-label="'查看专辑：' + album.name"
                    class="flex items-center min-w-0 text-left bg-surface-raised border border-line hover:border-accent group"
                    :class="embedded ? 'gap-2 p-1.5' : 'gap-3 p-3'"
                >
                    <CoverImage
                        :src="album.coverUrl"
                        class="flex-shrink-0"
                        :class="embedded ? 'w-9 h-9' : 'w-14 h-14'"
                        :scanline="false"
                    />
                    <div class="min-w-0 flex-1">
                        <div
                            class="font-bold group-hover:text-accent"
                            :class="embedded ? 'text-[11px] truncate' : 'text-sm break-words'"
                        >
                            {{ album.name }}
                        </div>
                        <div class="text-ink-muted truncate" :class="embedded ? 'text-[9px]' : 'text-xs mt-1'">
                            {{ album.artists.join(' / ') }}
                        </div>
                        <div class="text-ink-subtle" :class="embedded ? 'text-[9px]' : 'text-xs mt-1'">
                            {{ album.trackCount }} 首
                        </div>
                    </div>
                    <ChevronRight v-if="!embedded" class="w-4 h-4 flex-shrink-0 text-ink-subtle" />
                </button>
            </div>
            <div v-if="albums.length" class="py-4 text-center">
                <button
                    v-if="hasMore"
                    @click="load(false)"
                    :disabled="loading"
                    class="px-5 py-2 text-sm font-bold bg-brand text-ink-inverse disabled:opacity-40"
                >
                    {{ loading ? '正在读取…' : '加载更多专辑' }}
                </button>
                <p v-else class="text-xs text-ink-subtle">已显示全部收藏专辑</p>
            </div>
        </div>
        <AlbumModal v-if="selected" :key="selected" :album-id="selected" @close="selected = ''" />
    </section>
</template>

<script setup>
const props = defineProps({
    // 嵌入到侧栏时去掉自己的滚动容器与外层留白
    embedded: { type: Boolean, default: false },
});
import { ref, onMounted, onUnmounted } from 'vue';
import { ChevronRight } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import AlbumModal from './AlbumModal.vue';
import { musicApi } from '../api/music';

const albums = ref([]),
    total = ref(null),
    hasMore = ref(false);
const loading = ref(false),
    error = ref(''),
    selected = ref('');
let offset = 0,
    active = true;
const load = async (reset = false) => {
    if (loading.value) return;
    loading.value = true;
    error.value = '';
    if (reset) {
        offset = 0;
        albums.value = [];
        total.value = null;
        hasMore.value = false;
    }
    try {
        const page = await musicApi.getSavedAlbums(offset, 25);
        if (!active) return;
        const seen = new Set(albums.value.map((album) => album.id));
        albums.value.push(...page.items.filter((album) => !seen.has(album.id) && seen.add(album.id)));
        total.value = page.total;
        hasMore.value = page.hasMore && page.nextOffset > offset;
        offset = page.nextOffset;
    } catch (e) {
        if (active) error.value = e.response?.data?.message || '收藏专辑读取失败，请稍后重试';
    } finally {
        if (active) loading.value = false;
    }
};
onMounted(() => load(true));
// 由父组件（搜索窗侧栏）统一触发刷新
defineExpose({ reload: () => load(true) });
onUnmounted(() => {
    active = false;
});
</script>
