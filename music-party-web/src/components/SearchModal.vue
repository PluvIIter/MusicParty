<template>
  <div v-if="isOpen" class="fixed inset-0 z-[60] bg-medical-900/80 backdrop-blur-sm flex items-center justify-center p-4">
    <div class="w-full max-w-4xl bg-medical-50 h-[85vh] md:h-[80vh] flex flex-col shadow-2xl relative chamfer-br max-h-full">

      <!-- 关闭按钮 -->
      <button @click="emit('close')" class="absolute top-0 right-0 p-4 hover:text-accent z-50">
        <X class="w-6 h-6" />
      </button>

      <!-- 头部 -->
      <div class="p-4 md:p-6 border-b border-medical-200 bg-white flex-shrink-0">
        <h2 class="text-xl md:text-2xl font-bold font-mono mb-4 text-medical-900 flex items-center gap-2">
          <Search class="w-5 h-5 text-accent"/> SEARCH
        </h2>

        <!-- 平台切换 TAB -->
        <div class="flex gap-1 mb-4">
          <button
              v-for="p in ['netease', 'bilibili']" :key="p"
              @click="platform = p"
              class="px-6 py-2 text-sm font-bold uppercase transition-all"
              :class="platform === p ? 'bg-medical-900 text-white clip-tab' : 'bg-medical-200 text-medical-500 hover:bg-medical-300'"
          >
            {{ p }}
          </button>
        </div>

        <!-- 搜索框 -->
        <div class="flex gap-2">
          <input
              v-model="keyword"
              @keyup.enter="doSearch"
              :placeholder="isAdminMode ? '!!! ENTER ADMIN PASSWORD !!!' : '搜索音乐...'"
              class="flex-1 border p-3 outline-none transition-colors duration-300 font-sans"
              :class="isAdminMode ? 'bg-red-50 border-red-500 text-red-600 focus:border-red-600' : 'bg-medical-100 border-medical-200 focus:border-accent'"
          />
          <button
              @click="handleSearchAction"
              class="text-white px-3 md:px-6 py-2 font-bold transition-colors text-xs md:text-base flex-shrink-0"
              :class="isAdminMode ? 'bg-red-600 hover:bg-red-700' : 'bg-accent hover:bg-accent-hover'"
          >
            {{ isAdminMode ? 'UNLOCK' : 'SEARCH' }}
          </button>
        </div>
      </div>

      <!-- 内容区 -->
      <div class="flex-1 overflow-hidden flex flex-col md:flex-row relative">

        <!-- 左侧：我的歌单 / 绑定 -->
        <div class="md:w-1/3 md:h-auto flex-shrink-0 border-b md:border-b-0 md:border-r border-medical-200 flex-col bg-white transition-all"
             :class="mobileView === 'playlists' ? 'flex w-full h-full' : 'hidden md:flex'"
        >
          <div class="p-2 md:p-3 bg-medical-100 text-xs font-bold text-medical-500 flex justify-between items-center">
            <span>用户歌单</span>
          </div>

          <div class="flex-1 overflow-y-auto p-2 space-y-2">
            <!-- 未绑定 -->
            <div v-if="!bindings[platform]" class="p-4 border border-dashed border-medical-300 bg-medical-50">
              <div class="text-xs text-medical-500 mb-2 text-center">绑定用户以获取用户歌单</div>
              <div class="flex gap-1">
                <input v-model="searchUserKeyword" @keyup.enter="searchUser" placeholder="搜索用户名" class="flex-1 min-w-0 bg-white border border-medical-200 p-1 text-sm outline-none focus:border-accent font-sans" />
                <button @click="searchUser" class="bg-medical-200 hover:bg-medical-300 p-1"><Search class="w-4 h-4 text-medical-600"/></button>
              </div>
              <!-- 搜索结果列表 -->
              <div v-if="userSearchResults.length > 0" class="mt-2 max-h-40 overflow-y-auto space-y-1 border-t border-medical-200 pt-2">
                <div v-for="user in userSearchResults" :key="user.id" @click="bindUser(user)" class="flex items-center gap-2 p-1 hover:bg-accent/10 cursor-pointer group">
                  <img :src="user.avatarUrl" class="w-6 h-6 rounded-full bg-medical-200"/>
                  <span class="text-xs font-bold truncate flex-1">{{ user.name }}</span>
                </div>
              </div>
              <div v-if="isSearchingUser" class="text-center py-2"><Loader2 class="w-4 h-4 animate-spin mx-auto text-accent"/></div>
            </div>

            <!-- 已绑定：歌单列表 -->
            <template v-else>
              <div class="flex justify-between items-center px-2 py-1 bg-medical-50 border-b border-medical-100">
                <span class="text-[10px] font-mono text-medical-400">ID: {{ bindings[platform] }}</span>
                <button @click="playerStore.bindAccount(platform, '')" class="text-[10px] text-red-400 hover:underline">UNLINK</button>
              </div>
              
              <div v-if="isPlaylistsLoading" class="flex justify-center py-8">
                <Loader2 class="w-6 h-6 animate-spin text-accent" />
              </div>
              
              <div v-else v-for="pl in playlists" :key="pl.id" @click="handleSelectPlaylist(pl.id)" class="flex items-center gap-3 p-2 hover:bg-medical-50 cursor-pointer group transition-colors border-l-2 border-transparent hover:border-accent">
                <div class="w-10 h-10 bg-medical-200 flex-shrink-0 overflow-hidden"><CoverImage :src="pl.coverImgUrl" class="w-full h-full" /></div>
                <div class="overflow-hidden">
                  <div class="text-sm font-bold truncate group-hover:text-accent">{{ pl.name }}</div>
                  <div class="text-xs font-mono text-medical-400">{{ pl.trackCount }} TRACKS</div>
                </div>
                <ChevronRight class="w-4 h-4 text-medical-300 md:hidden ml-auto" />
              </div>
            </template>
          </div>
        </div>

        <!-- 右侧：歌曲列表 -->
        <div class="md:flex-1 bg-medical-50 flex-col min-h-0" :class="mobileView === 'songs' ? 'flex w-full h-full' : 'hidden md:flex'">
          <!-- 移动端返回条 -->
          <div class="md:hidden flex items-center gap-2 p-3 bg-white border-b border-medical-200 flex-shrink-0">
            <button @click="mobileView = 'playlists'" class="p-1 -ml-1 text-medical-500 hover:text-medical-900"><ArrowLeft class="w-5 h-5" /></button>
            <span class="font-bold text-sm text-medical-800">{{ listMode === 'search' ? 'SEARCH RESULTS' : 'PLAYLIST DETAILS' }}</span>
          </div>

          <div @scroll="handleScroll" class="flex-1 overflow-y-auto p-2 md:p-4">
            <div v-if="loading" class="text-center py-10 font-mono text-accent animate-pulse">> LOADING DATA STREAM...</div>

            <!-- 歌单操作头 -->
            <div v-else-if="currentPlaylistId && listMode === 'playlist'" class="mb-4 p-4 bg-white border border-medical-200 flex justify-between items-center shadow-sm">
              <div>
                <div class="text-xs font-sans text-medical-400">用户歌单</div>
                <div class="font-bold text-lg">{{ currentPlaylistId }}</div>
                <div class="text-xs text-medical-400 font-mono">{{ songs.length }} LOADED</div>
              </div>
              <button @click="handleImportPlaylist" class="bg-medical-900 text-white px-4 py-2 text-sm font-bold hover:bg-accent transition-colors flex items-center gap-2">
                <ListPlus class="w-4 h-4"/> <span class="hidden sm:inline">导入全部</span>
              </button>
            </div>

            <!-- 歌曲列表渲染 -->
            <div class="space-y-1">
              <div v-if="songs.length === 0 && !loading" class="text-center py-10 text-medical-400 text-xs font-mono">NO DATA FOUND</div>
              <div v-for="song in songs" :key="song.id" class="flex items-center p-3 border border-transparent transition-all group" :class="isUnplayable(song) ? 'opacity-50 grayscale bg-medical-50 cursor-not-allowed' : 'bg-white hover:border-medical-300 hover:shadow-sm'">
                <div class="flex-1 w-0 flex items-center gap-3">
                  <div class="w-8 h-8 bg-medical-200 flex-shrink-0 relative overflow-hidden"><CoverImage :src="song.coverUrl" class="w-full h-full" /></div>
                  <div class="min-w-0 flex-1">
                    <div class="text-sm font-bold truncate">{{ song.name }}</div>
                    <div class="text-xs text-medical-500 truncate">{{ song.artists.join(' / ') }}</div>
                  </div>
                </div>
                <div v-if="isUnplayable(song)" class="ml-2 flex-shrink-0"><span class="px-1.5 py-0.5 text-[10px] font-mono font-bold text-medical-400 border border-medical-300 bg-medical-100 rounded-sm">>10MIN</span></div>

                <button
                    v-else
                    @click="handleAddClick(song)"
                    class="ml-2 p-2 flex-shrink-0 transition-all duration-300"
                    :class="[
                        isInQueue(song.id) ? 'text-green-500 cursor-default' :
                        pendingIds.has(song.id) ? 'text-accent cursor-wait' :
                        'text-medical-300 hover:text-accent'
                    ]"
                    :disabled="pendingIds.has(song.id) || isInQueue(song.id)"
                >
                  <!-- 状态 1: 正在添加 -->
                  <Loader2 v-if="pendingIds.has(song.id)" class="w-5 h-5 animate-spin" />

                  <!-- 状态 2: 已经在队列中 -->
                  <Check v-else-if="isInQueue(song.id)" class="w-5 h-5" />

                  <!-- 状态 3: 普通添加按钮 -->
                  <PlusCircle v-else class="w-5 h-5"/>
                </button>
              </div>
            </div>

            <!-- 加载更多 -->
            <div v-if="listMode === 'playlist' && !loading" class="py-4 text-center">
              <div v-if="isLoadingMore" class="flex justify-center text-accent"><Loader2 class="w-6 h-6 animate-spin" /></div>
              <div v-else-if="!hasMore && songs.length > 0" class="text-xs font-mono text-medical-400">-- END OF PLAYLIST --</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useSearchLogic } from '../composables/useSearchLogic';
import { usePlaylistLogic } from '../composables/usePlaylistLogic';
import { X, Search, PlusCircle, ListPlus, Loader2, ArrowLeft, ChevronRight, Check } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';

const props = defineProps(['isOpen']);
const emit = defineEmits(['close']);
const playerStore = usePlayerStore();

// 1. 引入搜索逻辑
const {
  platform, keyword, songs, loading, listMode, isAdminMode, doSearch
} = useSearchLogic(emit);

const handleSearchAction = async () => {
  await doSearch();
  // 无论 listMode 之前是不是 search，只要用户手动搜索了，就切到列表视图
  if (!isAdminMode.value) {
    mobileView.value = 'songs';
  }
};

// 2. 引入歌单逻辑 (注入依赖)
const {
  playlists, currentPlaylistId, searchUserKeyword, userSearchResults,
  isSearchingUser, isPlaylistsLoading, hasMore, isLoadingMore, bindings,
  searchUser, bindUser, loadPlaylist, handleScroll
} = usePlaylistLogic(platform, songs, listMode, loading);

// 3. UI 状态
const mobileView = ref('playlists');
const MAX_DURATION = 10 * 60 * 1000;

// 4. 交互胶水代码
const isUnplayable = (song) => {
  return platform.value === 'bilibili' && song.duration > MAX_DURATION;
};

const handleSelectPlaylist = (pid) => {
  loadPlaylist(pid);
  mobileView.value = 'songs';
};

const handleImportPlaylist = () => {
  playerStore.enqueuePlaylist(platform.value, currentPlaylistId.value);
  emit('close');
};

// 本地维护正在添加中的歌曲 ID 集合
const pendingIds = ref(new Set());

// 检查歌曲是否已经在队列中
const isInQueue = (songId) => {
  return playerStore.queue.some(item => item.music.id === songId);
};

// 处理点击添加
const handleAddClick = (song) => {
  // 防抖：如果正在添加或已在队列，直接忽略
  if (pendingIds.value.has(song.id) || isInQueue(song.id)) return;

  // 1. 设置 loading 状态
  pendingIds.value.add(song.id);

  // 2. 发送请求
  playerStore.enqueue(platform.value, song.id);

  // 3. 设定一个“冷却时间”用于视觉反馈 (2秒)
  // WebSocket 是异步的，我们不需要一直等到服务器返回，
  // 这里的 loading 主要是为了告诉用户“操作已受理”并防止连点
  setTimeout(() => {
    pendingIds.value.delete(song.id);
  }, 2000);
};

// 监听搜索动作 -> 自动切视图
watch(listMode, (mode) => {
  if (mode === 'search') mobileView.value = 'songs';
});

watch(() => props.isOpen, (val) => {
  if (val) mobileView.value = 'playlists';
});
</script>

<style scoped>
.clip-tab { clip-path: polygon(0 0, 100% 0, 90% 100%, 0 100%); }
</style>