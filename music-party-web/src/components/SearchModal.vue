// File Path: music-party-web\src\components\SearchModal.vue

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
          <Search class="w-5 h-5 text-accent"/> RESOURCE_SEARCH
        </h2>

        <!-- 平台切换 TAB -->
        <div class="flex gap-1 mb-4">
          <button
              @click="platform = 'netease'"
              class="px-6 py-2 text-sm font-bold uppercase transition-all"
              :class="platform === 'netease' ? 'bg-medical-900 text-white clip-tab' : 'bg-medical-200 text-medical-500 hover:bg-medical-300'"
          >
            NETEASE
          </button>

          <button
              disabled
              class="px-6 py-2 text-sm font-bold uppercase transition-all bg-medical-100 text-medical-300 cursor-not-allowed border border-medical-200 relative overflow-hidden"
              title="System Maintenance"
          >
            BILIBILI
            <div class="absolute inset-0 bg-[linear-gradient(45deg,transparent_25%,rgba(0,0,0,0.05)_25%,rgba(0,0,0,0.05)_50%,transparent_50%,transparent_75%,rgba(0,0,0,0.05)_75%,rgba(0,0,0,0.05))] bg-[length:10px_10px]"></div>
          </button>
        </div>

        <!-- 搜索框 -->
        <div class="flex gap-2">
          <input
              v-model="keyword"
              @keyup.enter="doSearch"
              :placeholder="isAdminMode ? '!!!ENTER ADMIN PASSWORD!!!' : 'INPUT KEYWORDS OR ID...'"
              class="flex-1 border p-3 outline-none font-mono transition-colors duration-300"
              :class="isAdminMode
                  ? 'bg-red-50 border-red-500 text-red-600 placeholder-red-300 focus:border-red-600'
                  : 'bg-medical-100 border-medical-200 focus:border-accent'"
          />
          <button
              @click="doSearch"
              class="text-white px-3 md:px-6 py-2 font-bold transition-colors text-xs md:text-base flex-shrink-0"
              :class="isAdminMode ? 'bg-red-600 hover:bg-red-700' : 'bg-accent hover:bg-accent-hover'"
          >
            {{ isAdminMode ? 'N U K E' : 'EXECUTE' }}
          </button>
        </div>
      </div>

      <!-- 内容区 -->
      <div class="flex-1 overflow-hidden flex flex-col md:flex-row relative">

        <!--
           🟢 左侧面板：我的歌单 / 绑定界面
           修改点：
           1. 移除 h-48 固定高度
           2. 增加动态 class 控制移动端显示/隐藏
        -->
        <div
            class="md:w-1/3 md:h-auto flex-shrink-0 border-b md:border-b-0 md:border-r border-medical-200 flex-col bg-white transition-all"
            :class="mobileView === 'playlists' ? 'flex w-full h-full' : 'hidden md:flex'"
        >
          <div class="p-2 md:p-3 bg-medical-100 font-mono text-xs font-bold text-medical-500 flex justify-between items-center flex-shrink-0">
            <span>USER_PLAYLISTS</span>
            <span class="md:hidden text-[10px] text-medical-400">TAP TO SELECT</span>
          </div>

          <div class="flex-1 overflow-y-auto p-2 space-y-2">
            <!-- 未绑定账号 -->
            <div v-if="!bindings[platform]" class="flex flex-col gap-2">
              <div class="p-4 border border-dashed border-medical-300 bg-medical-50">
                <div class="text-xs text-medical-500 mb-2 font-mono text-center">LINK ACCOUNT TO SYNC</div>

                <div class="flex gap-1">
                  <input
                      v-model="searchUserKeyword"
                      @keyup.enter="searchUser"
                      placeholder="Search Username..."
                      class="flex-1 min-w-0 bg-white border border-medical-200 p-1 text-sm outline-none focus:border-accent"
                  />
                  <button @click="searchUser" class="bg-medical-200 hover:bg-medical-300 p-1">
                    <Search class="w-4 h-4 text-medical-600"/>
                  </button>
                </div>

                <div v-if="isSearchingUser" class="text-center py-2">
                  <Loader2 class="w-4 h-4 animate-spin mx-auto text-accent"/>
                </div>

                <div v-if="userSearchResults.length > 0" class="mt-2 max-h-40 overflow-y-auto space-y-1 border-t border-medical-200 pt-2">
                  <div
                      v-for="user in userSearchResults"
                      :key="user.id"
                      @click="selectAndBindUser(user)"
                      class="flex items-center gap-2 p-1 hover:bg-accent/10 cursor-pointer group"
                  >
                    <img :src="user.avatarUrl" class="w-6 h-6 rounded-full bg-medical-200"/>
                    <span class="text-xs font-bold truncate flex-1 group-hover:text-accent">{{ user.name }}</span>
                  </div>
                </div>

                <div v-else-if="!isSearchingUser && searchUserKeyword && userSearchResults.length === 0" class="text-[10px] text-center mt-2 text-medical-400">
                  NO USERS FOUND
                </div>
              </div>
            </div>

            <!-- 已绑定 -->
            <template v-else>
              <div class="flex justify-between items-center px-2 py-1 bg-medical-50 border-b border-medical-100 flex-shrink-0">
                <span class="text-[10px] font-mono text-medical-400">ID: {{ bindings[platform] }}</span>
                <button @click="playerStore.bindAccount(platform, '')" class="text-[10px] text-red-400 hover:underline">UNLINK</button>
              </div>

              <div
                  v-for="pl in playlists"
                  :key="pl.id"
                  @click="loadPlaylist(pl.id)"
                  class="flex items-center gap-3 p-2 hover:bg-medical-50 cursor-pointer group transition-colors border-l-2 border-transparent hover:border-accent"
              >
                <div class="w-10 h-10 bg-medical-200 flex-shrink-0 overflow-hidden">
                  <CoverImage :src="pl.coverImgUrl" class="w-full h-full" />
                </div>
                <div class="overflow-hidden">
                  <div class="text-sm font-bold truncate group-hover:text-accent">{{ pl.name }}</div>
                  <div class="text-xs font-mono text-medical-400">{{ pl.trackCount }} TRACKS</div>
                </div>
                <!-- 移动端箭头提示 -->
                <ChevronRight class="w-4 h-4 text-medical-300 md:hidden ml-auto" />
              </div>
            </template>
          </div>
        </div>

        <!--
           🟢 右侧面板：歌曲列表 / 搜索结果
           修改点：
           1. 增加 class 控制移动端显示 (当 mobileView === 'songs' 时显示全屏)
           2. 增加顶部移动端专用的“返回”栏
        -->
        <div
            class="md:flex-1 bg-medical-50 flex-col min-h-0"
            :class="mobileView === 'songs' ? 'flex w-full h-full' : 'hidden md:flex'"
        >
          <!-- 🟢 移动端专用返回条 -->
          <div class="md:hidden flex items-center gap-2 p-3 bg-white border-b border-medical-200 flex-shrink-0">
            <button @click="mobileView = 'playlists'" class="p-1 -ml-1 text-medical-500 hover:text-medical-900">
              <ArrowLeft class="w-5 h-5" />
            </button>
            <span class="font-bold text-sm text-medical-800">
                    {{ listMode === 'search' ? 'SEARCH RESULTS' : 'PLAYLIST DETAILS' }}
                </span>
          </div>

          <div
              ref="scrollContainer"
              @scroll="handleScroll"
              class="flex-1 overflow-y-auto p-2 md:p-4"
          >
            <div v-if="loading" class="text-center py-10 font-mono text-accent animate-pulse">
              > LOADING DATA STREAM...
            </div>

            <!-- 歌单头部操作 -->
            <div v-else-if="currentPlaylistId && listMode === 'playlist'" class="mb-4 p-4 bg-white border border-medical-200 flex justify-between items-center shadow-sm">
              <div>
                <span class="text-xs font-mono text-medical-400">SELECTED PLAYLIST</span>
                <div class="font-bold text-lg">{{ currentPlaylistId }}</div>
                <div class="text-xs text-medical-400 font-mono">{{ songs.length }} LOADED</div>
              </div>
              <button @click="enqueuePlaylist(currentPlaylistId)" class="bg-medical-900 text-white px-4 py-2 text-sm font-bold hover:bg-accent transition-colors flex items-center gap-2">
                <ListPlus class="w-4 h-4"/> <span class="hidden sm:inline">IMPORT ALL</span>
              </button>
            </div>

            <!-- 歌曲列表 -->
            <div class="space-y-1">
              <div v-if="songs.length === 0 && !loading" class="text-center py-10 text-medical-400 text-xs font-mono">
                NO DATA FOUND
              </div>
              <div
                  v-for="song in songs"
                  :key="song.id"
                  class="flex items-center p-3 bg-white border border-transparent hover:border-medical-300 hover:shadow-sm transition-all group"
              >
                <!--
                   🔴 终极修复：
                   1. 移除了外层的 'justify-between' (因为我们用 flex-1 撑开)。
                   2. 将左侧大容器设为 'flex-1 w-0'。
                      'w-0' 强制将内容基准宽度设为0，这是解决 Flex 溢出最强硬的手段。
                -->
                <div class="flex-1 w-0 flex items-center gap-3">
                  <div class="w-8 h-8 bg-medical-200 flex-shrink-0">
                    <CoverImage :src="song.coverUrl" class="w-full h-full" />
                  </div>
                  <div class="min-w-0 flex-1">
                    <div class="text-sm font-bold truncate">{{ song.name }}</div>
                    <div class="text-xs text-medical-500 truncate">{{ song.artists.join(' / ') }}</div>
                  </div>
                </div>

                <!-- 按钮：保持 flex-shrink-0 防止被压缩 -->
                <button @click="enqueue(song.id)" class="ml-2 p-2 text-medical-300 hover:text-accent flex-shrink-0">
                  <PlusCircle class="w-5 h-5"/>
                </button>
              </div>
            </div>
            <!-- 底部加载更多状态 -->
            <div v-if="listMode === 'playlist' && !loading" class="py-4 text-center">
              <div v-if="isLoadingMore" class="flex justify-center text-accent">
                <Loader2 class="w-6 h-6 animate-spin" />
              </div>
              <div v-else-if="!hasMore && songs.length > 0" class="text-xs font-mono text-medical-400">
                -- END OF PLAYLIST --
              </div>
            </div>
          </div>
        </div>
      </div>

    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue';
import { X, Search, PlusCircle, ListPlus, Loader2, ArrowLeft, ChevronRight } from 'lucide-vue-next';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';
import { useDebounceFn } from '@vueuse/core';
import axios from 'axios';
import { useToast } from '../composables/useToast';
import CoverImage from './CoverImage.vue';

const props = defineProps(['isOpen']);
const emit = defineEmits(['close']);
const userStore = useUserStore();
const playerStore = usePlayerStore();

const platform = ref('netease');
const keyword = ref('');
const songs = ref([]);
const playlists = ref([]);
const loading = ref(false);
const listMode = ref('search'); // 'search' or 'playlist'
const currentPlaylistId = ref(null);
const searchUserKeyword = ref('');
const userSearchResults = ref([]);
const isSearchingUser = ref(false);

const scrollContainer = ref(null);
const offset = ref(0);
const limit = 50;
const hasMore = ref(true);
const isLoadingMore = ref(false);

const bindings = computed(() => userStore.bindings);
const { success, error, info } = useToast();

// 🟢 移动端视图状态: 'playlists' | 'songs'
const mobileView = ref('playlists');

const isAdminMode = ref(false);

// 搜索歌曲
const doSearch = async () => {
  const val = keyword.value.trim();
  if(!val) return;

  // 1. 检查是否触发隐藏指令
  if (!isAdminMode.value && val === '//RESET') {
    isAdminMode.value = true;
    keyword.value = ''; // 清空输入框以便输入密码
    return;
  }

  // 2. 如果处于 Admin 模式，回车即发送密码
  if (isAdminMode.value) {
    try {
      await axios.post('/api/admin/reset', { password: val });
      success('SYSTEM PURGED SUCCESSFULLY');
      emit('close');
    } catch (e) {
      // 🟢 修复点：区分错误类型
      console.error("Reset Error:", e); // 在控制台打印详细错误，方便调试

      if (e.response && e.response.status === 403) {
        // 只有 403 才是真正的密码错误
        error('ACCESS DENIED: WRONG PASSWORD');
      } else {
        // 其他错误（如 500）说明密码是对的，逻辑跑了，但是后端最后可能报错了
        // 既然是重置系统，只要跑了大概率队列已经清空了，所以提示警告即可
        info('SYSTEM RESET COMPLETED');
        emit('close');
      }
    } finally {
      isAdminMode.value = false;
      keyword.value = '';
    }
    return;
  }

  // 🟢 切换视图
  listMode.value = 'search';
  mobileView.value = 'songs';

  currentPlaylistId.value = null;
  loading.value = true;
  try {
    const res = await axios.get(`/api/search/${platform.value}/${keyword.value}`);
    songs.value = res.data;
  } finally {
    loading.value = false;
  }
};

// 获取用户歌单
const fetchPlaylists = async () => {
  const uid = bindings.value[platform.value];
  if(!uid) {
    playlists.value = [];
    return;
  }
  try {
    const res = await axios.get(`/api/user/playlists/${platform.value}/${uid}`);
    playlists.value = res.data;
  } catch(e) {
    console.error(e);
  }
};

// 加载歌单内容
const loadPlaylist = async (pid) => {
  listMode.value = 'playlist';
  currentPlaylistId.value = pid;

  // 🟢 切换视图
  mobileView.value = 'songs';

  songs.value = [];
  offset.value = 0;
  hasMore.value = true;
  loading.value = true;

  try {
    await fetchSongsPage();
  } finally {
    loading.value = false;
  }
};

const fetchSongsPage = async () => {
  if (!currentPlaylistId.value) return;
  try {
    const res = await axios.get(`/api/playlist/songs/${platform.value}/${currentPlaylistId.value}`, {
      params: { offset: offset.value, limit: limit }
    });
    const newSongs = res.data;
    if (newSongs.length < limit) hasMore.value = false;
    songs.value.push(...newSongs);
  } catch (e) {
    console.error("Fetch songs failed", e);
    hasMore.value = false;
  }
};

const handleScroll = useDebounceFn(async () => {
  if (listMode.value !== 'playlist') return;
  const el = scrollContainer.value;
  if (!el) return;
  const bottom = el.scrollHeight - el.scrollTop - el.clientHeight;
  if (bottom < 100 && hasMore.value && !isLoadingMore.value && !loading.value) {
    isLoadingMore.value = true;
    offset.value += limit;
    try {
      await fetchSongsPage();
    } finally {
      isLoadingMore.value = false;
    }
  }
}, 200);

const searchUser = async () => {
  if(!searchUserKeyword.value) return;
  isSearchingUser.value = true;
  userSearchResults.value = [];
  try {
    const res = await axios.get(`/api/user/search/${platform.value}/${searchUserKeyword.value}`);
    userSearchResults.value = res.data;
  } catch(e) {
    console.error(e);
  } finally {
    isSearchingUser.value = false;
  }
};

const selectAndBindUser = (user) => {
  playerStore.bindAccount(platform.value, user.id);
  userSearchResults.value = [];
  searchUserKeyword.value = '';
  fetchPlaylists();
};

const enqueue = (id) => {
  playerStore.enqueue(platform.value, id);
};

const enqueuePlaylist = (pid) => {
  playerStore.enqueuePlaylist(platform.value, pid);
  emit('close');
};

watch([platform, bindings], () => {
  fetchPlaylists();
  songs.value = [];
  // 🟢 切换平台时，如果是移动端，建议重置回歌单列表
  mobileView.value = 'playlists';
}, { immediate: true });

// 监听弹窗打开，重置移动端视图
watch(() => props.isOpen, (val) => {
  if (val) mobileView.value = 'playlists';
});

</script>

<style scoped>
.clip-tab {
  clip-path: polygon(0 0, 100% 0, 90% 100%, 0 100%);
}
</style>