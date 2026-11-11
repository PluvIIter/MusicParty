<template>
    <div
        v-if="isOpen"
        class="fixed inset-0 z-[60] bg-brand/80 backdrop-blur-sm flex items-center justify-center p-0 md:p-4"
    >
        <!-- 移动端全屏面板；桌面端居中弹窗。安全区 padding 兼容刘海屏/全面屏 -->
        <div
            class="w-full max-w-4xl bg-surface h-full md:h-[80vh] flex flex-col shadow-2xl relative chamfer-br max-h-full pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]"
        >
            <!-- 关闭按钮 (移动端避开刘海区域) -->
            <button
                @click="emit('close')"
                aria-label="关闭搜索"
                class="absolute top-[env(safe-area-inset-top)] right-0 p-4 hover:text-accent z-50"
            >
                <X class="w-6 h-6" />
            </button>

            <!-- 头部 -->
            <div class="p-4 md:p-6 border-b border-line bg-surface-raised flex-shrink-0">
                <h2 class="text-xl md:text-2xl font-bold font-mono mb-3 md:mb-4 text-ink flex items-center gap-2">
                    <Search class="w-5 h-5 text-accent" /> SEARCH
                </h2>

                <!-- 平台切换 TAB (移动端收紧内边距，防小屏换行)；全长方形，选中用主题色高亮 -->
                <div class="flex gap-1 mb-3 md:mb-4 flex-wrap">
                    <button
                        v-for="source in sources.sources"
                        :key="source.id"
                        @click="selectPlatform(source.id)"
                        :disabled="!sources.isEnabled(source.id)"
                        class="px-4 md:px-6 py-2 text-sm font-bold uppercase transition-all"
                        :class="[
                            mode === 'search' && platform === source.id
                                ? 'bg-accent text-accent-contrast'
                                : 'bg-line text-ink-muted hover:bg-line-strong',
                            !sources.isEnabled(source.id) ? 'opacity-30 cursor-not-allowed grayscale' : '',
                        ]"
                    >
                        {{ source.label }}
                    </button>
                    <!-- 本地点赞列表标签（仅本地缓存，不上报服务器） -->
                    <button
                        @click="mode = 'likesong'"
                        class="px-4 md:px-6 py-2 text-sm font-bold transition-all"
                        :class="
                            mode === 'likesong'
                                ? 'bg-accent text-accent-contrast'
                                : 'bg-line text-ink-muted hover:bg-line-strong'
                        "
                    >
                        点赞的音乐
                    </button>
                </div>

                <p v-if="sources.loadError" role="alert" class="text-danger text-xs mb-2">
                    {{ sources.loadError }} <button @click="sources.load()">重试</button>
                </p>
                <!-- 搜索框 -->
                <div class="flex gap-2">
                    <input
                        v-model="keyword"
                        @keyup.enter="handleSearchAction"
                        :placeholder="mode === 'likesong' ? '搜索点赞的歌曲...' : '搜索音乐...'"
                        class="flex-1 min-w-0 border p-2.5 md:p-3 outline-none transition-colors duration-300 font-sans bg-surface-sunken border-line focus:border-accent"
                    />
                    <button
                        @click="handleSearchAction"
                        class="text-accent-contrast px-3 md:px-6 py-2 font-bold transition-colors text-xs md:text-base flex-shrink-0 font-sans bg-accent hover:bg-accent-hover"
                    >
                        SEARCH
                    </button>
                </div>
            </div>

            <!-- 内容区 -->
            <div class="flex-1 overflow-hidden flex flex-col md:flex-row relative">
                <!-- LikeSong：本地点赞歌曲列表（仅本地缓存，不上报服务器） -->
                <div v-if="mode === 'likesong'" class="flex-1 bg-surface flex-col min-h-0 flex">
                    <div
                        class="flex items-center justify-between p-3 bg-surface-raised border-b border-line flex-shrink-0"
                    >
                        <span class="font-bold text-sm text-ink">点赞的音乐</span>
                        <span class="text-[10px] font-mono text-ink-subtle">{{ likedSongs.length }} LIKED</span>
                    </div>
                    <div class="flex-1 overflow-y-auto overscroll-contain p-2 md:p-4">
                        <div
                            v-if="filteredLikedSongs.length === 0"
                            class="text-center py-10 text-ink-subtle text-xs font-mono"
                        >
                            {{ keyword.trim() ? 'NO MATCH' : 'NO LIKED SONGS YET' }}
                        </div>
                        <div class="space-y-1">
                            <div
                                v-for="song in filteredLikedSongs"
                                :key="song.platform + ':' + song.id"
                                class="flex items-center p-3 bg-surface-raised border border-transparent transition-all group hover:border-line-strong hover:shadow-sm"
                            >
                                <div class="flex-1 w-0 flex items-center gap-3">
                                    <div class="w-8 h-8 bg-line flex-shrink-0 relative overflow-hidden">
                                        <CoverImage :src="song.coverUrl" class="w-full h-full" :scanline="false" />
                                    </div>
                                    <div class="min-w-0 flex-1">
                                        <div class="text-sm font-bold truncate">{{ song.name }}</div>
                                        <div class="text-xs text-ink-muted truncate">
                                            {{ song.artists.join(' / ') }}
                                        </div>
                                    </div>
                                </div>
                                <button
                                    @click="addLikedClick(song)"
                                    :disabled="
                                        isUnplayable(song) || isInQueue(song) || pendingIds.has(resourceKey(song))
                                    "
                                    :title="isUnplayable(song) ? '音乐源已禁用，收藏记录仍保留' : '添加单曲'"
                                    class="ml-2 p-2 flex-shrink-0 transition-all duration-300"
                                    :class="[
                                        isInQueue(song)
                                            ? 'text-success cursor-default'
                                            : pendingIds.has(resourceKey(song))
                                              ? 'text-accent cursor-wait'
                                              : 'text-ink-subtle hover:text-accent',
                                    ]"
                                >
                                    <!-- 状态 1: 正在添加 -->
                                    <Loader2 v-if="pendingIds.has(resourceKey(song))" class="w-5 h-5 animate-spin" />
                                    <!-- 状态 2: 已经在队列中 -->
                                    <Check v-else-if="isInQueue(song)" class="w-5 h-5" />
                                    <!-- 状态 3: 普通添加按钮 -->
                                    <PlusCircle v-else class="w-5 h-5" />
                                </button>
                                <button
                                    @click="openLikedSource(song)"
                                    title="打开源页面"
                                    class="ml-1 p-2 flex-shrink-0 text-ink-subtle hover:text-accent transition-all duration-300"
                                >
                                    <ExternalLink class="w-5 h-5" />
                                </button>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- 左侧：我的歌单 / 绑定 -->
                <div
                    class="md:w-1/3 md:h-auto flex-shrink-0 border-b md:border-b-0 md:border-r border-line flex-col bg-surface-raised transition-all"
                    :class="
                        mode !== 'search'
                            ? 'hidden'
                            : mobileView === 'playlists'
                              ? 'flex w-full h-full'
                              : 'hidden md:flex'
                    "
                >
                    <div
                        class="p-2 md:p-3 bg-surface-sunken text-xs font-bold text-ink-muted flex justify-between items-center font-sans"
                    >
                        <span>公开用户收藏</span>
                        <button
                            type="button"
                            @click="refreshSidebar"
                            :disabled="isPlaylistsLoading"
                            class="text-[10px] font-bold px-2 py-0.5 border border-line hover:text-accent hover:border-accent disabled:opacity-40 transition-colors"
                        >
                            {{ isPlaylistsLoading ? '刷新中' : '刷新' }}
                        </button>
                    </div>

                    <div class="flex-1 overflow-y-auto overscroll-contain p-2 space-y-2">
                        <select
                            v-if="categories.length"
                            v-model="categoryId"
                            aria-label="收藏分类"
                            class="w-full bg-surface border border-line p-2 text-sm"
                        >
                            <option v-for="item in categories" :key="item.id" :value="item.id">{{ item.label }}</option>
                        </select>
                        <p v-else class="text-xs p-3 text-ink-subtle">此音乐源没有公开用户收藏</p>
                        <!-- 未绑定 -->
                        <div
                            v-if="categories.length && !bindings[platform]"
                            class="p-4 border border-dashed border-line-strong bg-surface"
                        >
                            <div class="text-xs text-ink-muted mb-2 text-center font-sans">
                                保存公开用户 ID（仅在此浏览器）
                            </div>
                            <div class="flex gap-1 mb-2">
                                <input
                                    v-model="targetUserId"
                                    aria-label="公开用户 ID"
                                    placeholder="公开用户 ID"
                                    class="min-w-0 flex-1 bg-surface-raised border border-line p-1 text-sm"
                                />
                                <button
                                    @click="bindUser({ id: targetUserId })"
                                    :disabled="!targetUserId.trim()"
                                    class="p-1 text-accent text-xs"
                                >
                                    保存
                                </button>
                            </div>
                            <div v-if="sources.supports(platform, 'userSearch')" class="flex gap-1">
                                <input
                                    v-model="searchUserKeyword"
                                    @keyup.enter="searchUser"
                                    placeholder="搜索用户名"
                                    class="flex-1 min-w-0 bg-surface-raised border border-line p-1 text-sm outline-none focus:border-accent font-sans"
                                />
                                <button
                                    @click="searchUser"
                                    class="bg-accent hover:bg-accent-hover p-1 transition-colors"
                                >
                                    <Search class="w-4 h-4 text-accent-contrast" />
                                </button>
                            </div>
                            <!-- 搜索结果列表 -->
                            <div
                                v-if="userSearchResults.length > 0"
                                class="mt-2 max-h-40 overflow-y-auto space-y-1 border-t border-line pt-2"
                            >
                                <div
                                    v-for="user in userSearchResults"
                                    :key="user.id"
                                    @click="bindUser(user)"
                                    class="flex items-center gap-2 p-1 hover:bg-accent/10 cursor-pointer group"
                                >
                                    <img :src="user.avatarUrl" class="w-6 h-6 rounded-full bg-line" />
                                    <span class="text-xs font-bold truncate flex-1">{{ user.name }}</span>
                                </div>
                            </div>
                            <div v-if="isSearchingUser" class="text-center py-2">
                                <Loader2 class="w-4 h-4 animate-spin mx-auto text-accent" />
                            </div>
                        </div>

                        <!-- 已绑定：歌单列表 -->
                        <template v-else-if="categories.length">
                            <div class="flex justify-between items-center px-2 py-1 bg-surface border-b border-line">
                                <span class="text-[10px] font-mono text-ink-subtle">ID: {{ bindings[platform] }}</span>
                                <button
                                    @click="userStore.updateBinding(platform, '')"
                                    class="text-[10px] text-danger hover:underline"
                                >
                                    UNLINK
                                </button>
                            </div>

                            <div v-if="isPlaylistsLoading" class="flex justify-center py-8">
                                <Loader2 class="w-6 h-6 animate-spin text-accent" />
                            </div>

                            <div
                                v-else
                                v-for="pl in playlists"
                                :key="pl.id"
                                @click="handleSelectPlaylist(pl)"
                                class="flex items-center gap-3 p-2 hover:bg-surface cursor-pointer group transition-colors border-l-2 border-transparent hover:border-accent"
                            >
                                <div class="w-10 h-10 bg-line flex-shrink-0 overflow-hidden">
                                    <CoverImage :src="pl.coverUrl" class="w-full h-full" :scanline="false" />
                                </div>
                                <div class="overflow-hidden">
                                    <div class="text-sm font-bold truncate group-hover:text-accent">{{ pl.name }}</div>
                                    <div class="text-xs font-mono text-ink-subtle">{{ pl.trackCount }} TRACKS</div>
                                </div>
                                <ChevronRight class="w-4 h-4 text-ink-subtle md:hidden ml-auto" />
                            </div>
                        </template>

                        <button
                            v-if="collectionCursor"
                            @click="fetchPlaylists(true)"
                            :disabled="isPlaylistsLoading"
                            class="p-2 text-accent text-xs"
                        >
                            更多收藏
                        </button>
                    </div>
                </div>

                <!-- 右侧：歌曲列表 -->
                <div
                    class="md:flex-1 bg-surface flex-col min-h-0"
                    :class="
                        mode !== 'search' ? 'hidden' : mobileView === 'songs' ? 'flex w-full h-full' : 'hidden md:flex'
                    "
                >
                    <!-- 移动端返回条 -->
                    <div
                        class="md:hidden flex items-center gap-2 p-3 bg-surface-raised border-b border-line flex-shrink-0"
                    >
                        <button @click="mobileView = 'playlists'" class="p-1 -ml-1 text-ink-muted hover:text-ink">
                            <ArrowLeft class="w-5 h-5" />
                        </button>
                        <span class="font-bold text-sm text-ink">{{
                            listMode === 'search' ? 'SEARCH RESULTS' : 'PLAYLIST DETAILS'
                        }}</span>
                    </div>

                    <div @scroll="handleScroll" class="flex-1 overflow-y-auto overscroll-contain p-2 md:p-4">
                        <div v-if="loading" class="text-center py-10 font-mono text-accent animate-pulse">
                            > LOADING DATA STREAM...
                        </div>

                        <!-- 歌单操作头 -->
                        <div
                            v-else-if="currentPlaylistId && listMode === 'playlist'"
                            class="mb-4 p-4 bg-surface-raised border border-line flex justify-between items-center shadow-sm"
                        >
                            <div>
                                <div class="text-xs font-sans text-ink-subtle">用户歌单</div>
                                <div class="font-bold text-lg">{{ currentPlaylistId }}</div>
                                <div class="text-xs text-ink-subtle font-mono">{{ songs.length }} LOADED</div>
                            </div>
                            <button
                                @click="handleImportPlaylist"
                                :disabled="importing"
                                class="bg-brand text-ink-inverse px-4 py-2 text-sm font-bold hover:bg-accent hover:text-accent-contrast transition-colors flex items-center gap-2 font-sans"
                            >
                                <ListPlus class="w-4 h-4" /> <span class="hidden sm:inline">导入全部</span>
                            </button>
                        </div>

                        <!-- 歌曲列表渲染 -->
                        <div class="space-y-1">
                            <div
                                v-if="songs.length === 0 && !loading"
                                class="text-center py-10 text-ink-subtle text-xs font-mono"
                            >
                                NO DATA FOUND
                            </div>
                            <div
                                v-for="song in songs"
                                :key="resourceKey(song)"
                                class="flex items-center p-3 border border-transparent transition-all group"
                                :class="
                                    isUnplayable(song)
                                        ? 'opacity-50 grayscale bg-surface cursor-not-allowed'
                                        : 'bg-surface-raised hover:border-line-strong hover:shadow-sm'
                                "
                            >
                                <div class="flex-1 w-0 flex items-center gap-3">
                                    <button
                                        v-if="sources.supports(song.platform, 'album')"
                                        type="button"
                                        @click="selectedAlbumSong = song"
                                        :aria-label="'查看《' + song.name + '》所属专辑'"
                                        title="点击封面查看所属专辑"
                                        class="w-11 h-11 -m-1.5 p-1.5 flex-shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-surface-raised"
                                    >
                                        <CoverImage
                                            :src="song.coverUrl"
                                            class="w-full h-full transition-shadow hover:ring-2 hover:ring-accent"
                                            :scanline="false"
                                        />
                                    </button>
                                    <div v-else class="w-8 h-8 bg-line flex-shrink-0 relative overflow-hidden">
                                        <CoverImage :src="song.coverUrl" class="w-full h-full" :scanline="false" />
                                    </div>
                                    <div class="min-w-0 flex-1">
                                        <div class="text-sm font-bold truncate">{{ song.name }}</div>
                                        <div class="text-xs text-ink-muted truncate">
                                            {{ song.artists.join(' / ') }}
                                        </div>
                                    </div>
                                </div>

                                <button
                                    v-if="!isUnplayable(song)"
                                    @click="handleAddClick(song)"
                                    :title="isInQueue(song) ? '已在队列' : '添加单曲'"
                                    :aria-label="(isInQueue(song) ? '已在队列：' : '添加单曲：') + song.name"
                                    class="ml-2 p-2 flex-shrink-0 transition-all duration-300"
                                    :class="[
                                        isInQueue(song)
                                            ? 'text-success cursor-default'
                                            : pendingIds.has(resourceKey(song))
                                              ? 'text-accent cursor-wait'
                                              : 'text-ink-subtle hover:text-accent',
                                    ]"
                                    :disabled="pendingIds.has(resourceKey(song)) || isInQueue(song)"
                                >
                                    <!-- 状态 1: 正在添加 -->
                                    <Loader2 v-if="pendingIds.has(resourceKey(song))" class="w-5 h-5 animate-spin" />

                                    <!-- 状态 2: 已经在队列中 -->
                                    <Check v-else-if="isInQueue(song)" class="w-5 h-5" />

                                    <!-- 状态 3: 普通添加按钮 -->
                                    <PlusCircle v-else class="w-5 h-5" />
                                </button>
                            </div>
                        </div>

                        <!-- 加载更多 -->
                        <div v-if="listMode === 'playlist' && !loading" class="py-4 text-center">
                            <div v-if="isLoadingMore" class="flex justify-center text-accent">
                                <Loader2 class="w-6 h-6 animate-spin" />
                            </div>
                            <button
                                v-else-if="hasMore"
                                @click="loadMoreTracks"
                                class="px-4 py-2 text-xs font-bold text-accent border border-line"
                            >
                                加载更多曲目
                            </button>
                            <div v-else-if="!hasMore && songs.length > 0" class="text-xs font-mono text-ink-subtle">
                                -- END OF PLAYLIST --
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
        <AlbumModal
            v-if="selectedAlbumSong"
            :source-id="selectedAlbumSong.platform"
            :song-id="selectedAlbumSong.id"
            @close="selectedAlbumSong = null"
        />
    </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useSourcesStore } from '../stores/sources';
import { useSearchLogic } from '../composables/useSearchLogic';
import { usePlaylistLogic } from '../composables/usePlaylistLogic';
import { useLikedSongs } from '../composables/useLikedSongs';
import { resourceKey, openExternal, safeExternalUrl } from '../domain/sources';
import { musicApi } from '../api/music';
import { useToast } from '../composables/useToast';
import {
    X,
    Search,
    PlusCircle,
    ListPlus,
    Loader2,
    ArrowLeft,
    ChevronRight,
    Check,
    ExternalLink,
} from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import AlbumModal from './AlbumModal.vue';
const props = defineProps(['isOpen']);
const emit = defineEmits(['close']);
const playerStore = usePlayerStore();
const userStore = useUserStore();
const sources = useSourcesStore();
const selectedAlbumSong = ref(null);
const targetUserId = ref('');
const { platform, keyword, songs, loading, listMode, doSearch, cancelSearch } = useSearchLogic();
const mode = ref('search');
const mobileView = ref('playlists');
const { likedSongs } = useLikedSongs();
const filteredLikedSongs = computed(() => {
    const kw = keyword.value.trim().toLowerCase();
    return likedSongs.value.filter(
        (s) =>
            !kw ||
            s.name.toLowerCase().includes(kw) ||
            (s.artists || []).some((a) => String(a).toLowerCase().includes(kw))
    );
});
const {
    playlists,
    categories,
    categoryId,
    currentPlaylistId,
    currentContainer,
    searchUserKeyword,
    userSearchResults,
    isSearchingUser,
    isPlaylistsLoading,
    hasMore,
    isLoadingMore,
    bindings,
    searchUser,
    bindUser,
    loadPlaylist,
    handleScroll,
    loadMoreTracks,
    fetchPlaylists,
    collectionCursor,
} = usePlaylistLogic(
    platform,
    songs,
    listMode,
    loading,
    computed(() => props.isOpen && mode.value === 'search')
);
watch(() => [bindings.value[platform.value], categoryId.value], cancelSearch, { flush: 'sync' });
const handleSearchAction = async () => {
    if (mode.value === 'likesong' || !sources.supports(platform.value, 'search')) return;
    await doSearch();
    mobileView.value = 'songs';
};
const selectPlatform = async (id) => {
    if (mode.value === 'search' && platform.value === id) return;
    mode.value = 'search';
    platform.value = id;
    if (keyword.value.trim()) await handleSearchAction();
};
const isUnplayable = (song) => !sources.isEnabled(song.platform);
const refreshSidebar = () => fetchPlaylists();
const handleSelectPlaylist = (container) => {
    loadPlaylist(container);
    mobileView.value = 'songs';
};
const { success, error } = useToast();
const importing = ref(false);
const handleImportPlaylist = async () => {
    if (importing.value || !currentContainer.value) return;
    importing.value = true;
    try {
        const result = await playerStore.enqueueContainer(
            platform.value,
            currentContainer.value.collectionType,
            currentContainer.value.id
        );
        success(result.message);
    } catch (e) {
        error(e.message);
    } finally {
        importing.value = false;
    }
};
const pendingIds = ref(new Set());
const timers = new Set();
const isInQueue = (song) => playerStore.queue.some((item) => resourceKey(item.music) === resourceKey(song));
const handleAddClick = (song) => {
    const key = resourceKey(song);
    if (isUnplayable(song) || pendingIds.value.has(key) || isInQueue(song)) return;
    pendingIds.value.add(key);
    playerStore.enqueue(song.platform, song.id);
    const timer = setTimeout(() => {
        pendingIds.value.delete(key);
        timers.delete(timer);
    }, 2000);
    timers.add(timer);
};
const addLikedClick = handleAddClick;
const sourceLinks = new Map();
const openLikedSource = async (song) => {
    if (song.externalUrl) return openExternal(song.externalUrl);
    const key = resourceKey(song);
    if (sourceLinks.has(key)) return openExternal(sourceLinks.get(key));
    const tab = window.open('about:blank', '_blank');
    if (tab) tab.opener = null;
    try {
        const result = await musicApi.getExternalUrl(song.platform, song.id);
        const url = safeExternalUrl(result.externalUrl);
        if (!url) throw new Error('此音乐源暂不提供源页面链接');
        sourceLinks.set(key, url);
        if (tab && !tab.closed) tab.location.replace(url);
        else openExternal(url);
    } catch (e) {
        tab?.close();
        error(e.response?.data?.message || e.message || '源页面链接读取失败');
    }
};
watch(listMode, (value) => {
    if (value === 'search' || value === 'collection') mobileView.value = 'songs';
});
watch(
    mode,
    () => {
        cancelSearch();
    },
    { flush: 'sync' }
);
watch(
    () => props.isOpen,
    (open) => {
        if (open) {
            mobileView.value = listMode.value === 'playlist' || listMode.value === 'collection' ? 'songs' : 'playlists';
            sources.load();
        } else {
            selectedAlbumSong.value = null;
            cancelSearch();
        }
    }
);
watch(
    () => sources.enabled.map((s) => s.id),
    (ids) => {
        if (!ids.includes(platform.value)) platform.value = ids[0] || '';
    }
);
onMounted(() => sources.load());
onUnmounted(() => timers.forEach(clearTimeout));
</script>
