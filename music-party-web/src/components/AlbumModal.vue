<template>
    <div class="fixed inset-0 z-[80] bg-brand/80 backdrop-blur-sm flex items-center justify-center p-3 md:p-6">
        <section
            role="dialog"
            aria-modal="true"
            aria-labelledby="album-title"
            class="w-full max-w-2xl max-h-[85dvh] flex flex-col bg-surface border border-line shadow-2xl chamfer-br"
        >
            <header
                class="flex justify-between items-start gap-3 p-4 md:p-6 bg-surface-raised border-b border-line flex-shrink-0"
            >
                <div class="flex gap-3 min-w-0">
                    <CoverImage v-if="album" :src="album.coverUrl" class="w-16 h-16 flex-shrink-0" :scanline="false" />
                    <div class="min-w-0">
                        <div class="text-xs text-accent font-bold mb-1">整张专辑</div>
                        <h3 id="album-title" class="font-bold text-lg md:text-xl text-ink break-words">
                            {{ album?.name || '读取专辑' }}
                        </h3>
                        <p v-if="album" class="text-xs text-ink-muted mt-1 break-words">
                            {{ (album.artists || []).join(' / ') }} · {{ album.trackCount }} 首
                        </p>
                    </div>
                </div>
                <button
                    @click="emit('close')"
                    :disabled="importing"
                    aria-label="关闭专辑"
                    class="p-1 text-ink-muted hover:text-accent disabled:opacity-40"
                >
                    <X class="w-5 h-5" />
                </button>
            </header>
            <div class="flex-1 min-h-0 overflow-y-auto overscroll-contain p-4 md:px-6">
                <div
                    v-if="loading"
                    role="status"
                    class="py-10 text-center text-accent flex justify-center items-center gap-2"
                >
                    <Loader2 class="w-5 h-5 animate-spin" />正在读取专辑…
                </div>
                <div v-else-if="loadError" role="alert" class="py-8 text-center">
                    <p class="text-sm text-danger">{{ loadError }}</p>
                    <button @click="loadAlbum" class="mt-4 px-4 py-2 bg-brand text-ink-inverse text-sm">
                        重新读取
                    </button>
                </div>
                <ol v-else-if="album" class="space-y-1" aria-label="专辑曲目">
                    <li
                        v-for="(song, index) in album.songs"
                        :key="resourceKey(song)"
                        class="flex gap-3 items-center p-3 bg-surface-raised border border-line"
                    >
                        <span class="w-5 flex-shrink-0 text-xs text-ink-subtle font-mono">{{ index + 1 }}</span>
                        <div class="flex-1 min-w-0">
                            <div class="text-sm font-bold break-words">{{ song.name }}</div>
                            <div class="text-xs text-ink-muted truncate">{{ song.artists.join(' / ') }}</div>
                        </div>
                        <span v-if="inQueue(song)" class="flex-shrink-0 text-xs text-success">已在队列</span>
                        <button
                            type="button"
                            @click="addSong(song)"
                            :aria-label="(inQueue(song) ? '已在队列：' : '添加单曲：') + song.name"
                            :title="
                                inQueue(song) ? '已在队列' : userStore.isGuest ? '请先设置昵称并加入房间' : '添加这首歌'
                            "
                            :disabled="
                                importing ||
                                pendingIds.has(resourceKey(song)) ||
                                inQueue(song) ||
                                !playerStore.connected ||
                                userStore.isGuest ||
                                !sources.isEnabled(props.sourceId)
                            "
                            class="p-2 flex-shrink-0 text-ink-muted hover:text-accent disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            <Check v-if="inQueue(song)" class="w-5 h-5 text-success" />
                            <Loader2
                                v-else-if="pendingIds.has(resourceKey(song))"
                                class="w-5 h-5 animate-spin text-accent"
                            />
                            <PlusCircle v-else class="w-5 h-5" />
                        </button>
                    </li>
                </ol>
            </div>
            <footer v-if="album" class="p-4 md:px-6 bg-surface-raised border-t border-line flex-shrink-0">
                <p
                    v-if="feedback"
                    :role="importError ? 'alert' : 'status'"
                    class="text-sm mb-3 break-words"
                    :class="importError ? 'text-danger' : 'text-success'"
                >
                    {{ feedback }}
                </p>
                <button
                    @click="importAlbum"
                    :disabled="importing || allInQueue || !playerStore.connected"
                    class="w-full flex justify-center items-center gap-2 px-4 py-3 bg-accent hover:bg-accent-hover text-accent-contrast font-bold text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    <Loader2 v-if="importing" class="w-4 h-4 animate-spin" /><ListPlus v-else class="w-4 h-4" />
                    {{
                        importing
                            ? '正在添加…'
                            : allInQueue
                              ? '全部已在队列'
                              : !playerStore.connected
                                ? '等待房间连接…'
                                : `添加整张专辑（${album.trackCount} 首）`
                    }}
                </button>
            </footer>
        </section>
    </div>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue';
import { X, ListPlus, Loader2, PlusCircle, Check } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import { musicApi } from '../api/music';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useSourcesStore } from '../stores/sources';
import { resourceKey, createRequestScope, isCanceled } from '../domain/sources';

const props = defineProps({
    sourceId: { type: String, required: true },
    songId: { type: String, default: '' },
    albumId: { type: String, default: '' },
});
const emit = defineEmits(['close']);
const playerStore = usePlayerStore();
const userStore = useUserStore();
const sources = useSourcesStore();
const requests = createRequestScope();
const pendingIds = ref(new Set());
const pendingTimers = new Set();
const album = ref(null),
    loading = ref(true),
    loadError = ref('');
const importing = ref(false),
    feedback = ref(''),
    importError = ref(false);
let active = true;
const inQueue = (song) => playerStore.queue.some((item) => resourceKey(item.music) === resourceKey(song));
const allInQueue = computed(() => album.value?.songs.length > 0 && album.value.songs.every((song) => inQueue(song)));
const loadAlbum = async () => {
    const request = requests.start();
    const sourceId = props.sourceId;
    const songId = props.songId;
    const albumId = props.albumId;
    loading.value = true;
    loadError.value = '';
    album.value = null;
    try {
        const result = await (albumId
            ? musicApi.getContainer(sourceId, 'album', albumId, request.signal)
            : musicApi.getSongAlbum(sourceId, songId, request.signal));
        if (!request.current()) return;
        const songs = [];
        const cursors = new Set();
        let cursor = null;
        do {
            const page = await musicApi.getContainerTracks(
                sourceId,
                result.collectionType,
                result.id,
                cursor,
                request.signal
            );
            if (!request.current()) return;
            songs.push(...page.items);
            cursor = page.nextCursor;
            if (cursor != null && cursors.has(cursor)) throw new Error('分页游标重复');
            cursors.add(cursor);
        } while (cursor != null);
        if (request.current()) album.value = { ...result, songs };
    } catch (error) {
        if (request.current() && !isCanceled(error))
            loadError.value = error.response?.data?.message || error.message || '专辑读取失败，请稍后重试';
    } finally {
        if (request.current()) loading.value = false;
    }
};
const addSong = (song) => {
    if (
        importing.value ||
        pendingIds.value.has(resourceKey(song)) ||
        inQueue(song) ||
        !playerStore.connected ||
        userStore.isGuest ||
        !sources.isEnabled(props.sourceId)
    )
        return;
    pendingIds.value.add(resourceKey(song));
    try {
        // Use the existing single-song command; queue broadcasts confirm the result.
        playerStore.enqueue(song.platform, song.id);
        const timer = setTimeout(() => {
            pendingTimers.delete(timer);
            pendingIds.value.delete(resourceKey(song));
        }, 2000);
        pendingTimers.add(timer);
    } catch (error) {
        pendingIds.value.delete(resourceKey(song));
        importError.value = true;
        feedback.value = error.message || '单曲添加失败，请重试';
    }
};
const importAlbum = async () => {
    if (importing.value || !album.value || allInQueue.value) return;
    importing.value = true;
    importError.value = false;
    feedback.value = '';
    try {
        const result = await playerStore.enqueueContainer(props.sourceId, album.value.collectionType, album.value.id);
        if (active) feedback.value = result.message;
    } catch (error) {
        if (active) {
            importError.value = true;
            feedback.value = error.message;
        }
    } finally {
        if (active) importing.value = false;
    }
};
watch(() => [props.sourceId, props.songId, props.albumId], loadAlbum, { immediate: true });
onUnmounted(() => {
    active = false;
    requests.cancel();
    pendingTimers.forEach(clearTimeout);
    pendingTimers.clear();
});
</script>
