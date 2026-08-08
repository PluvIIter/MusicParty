// src/composables/useLikedSongs.js
// 点赞歌曲列表：仅存于本机 localStorage，不涉及服务器。
// 模块级单例：点赞页（CenterConsole）与搜索页（SearchModal）共享同一份响应式状态。
import { ref } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';

const likedSongs = ref([]);
let loaded = false;

function load() {
  if (loaded) return;
  loaded = true;
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.LIKED_SONGS);
    likedSongs.value = raw ? JSON.parse(raw) : [];
  } catch {
    likedSongs.value = [];
  }
}

function persist() {
  try {
    localStorage.setItem(STORAGE_KEYS.LIKED_SONGS, JSON.stringify(likedSongs.value));
  } catch {
    // 本地缓存写入失败，静默忽略
  }
}

export function useLikedSongs() {
  load();
  const isLiked = (platform, id) =>
    likedSongs.value.some((s) => s.platform === platform && s.id === id);

  // 把一首歌加入点赞列表（按 platform+id 去重，新赞的排最前）
  const addLikedSong = (music) => {
    load();
    if (!music || !music.id || isLiked(music.platform, music.id)) return;
    likedSongs.value.unshift({
      id: music.id,
      platform: music.platform,
      name: music.name || '',
      artists: music.artists || [],
      coverUrl: music.coverUrl || '',
      url: music.url || ''
    });
    persist();
  };

  const removeLikedSong = (platform, id) => {
    load();
    likedSongs.value = likedSongs.value.filter((s) => !(s.platform === platform && s.id === id));
    persist();
  };

  return { likedSongs, isLiked, addLikedSong, removeLikedSong };
}
