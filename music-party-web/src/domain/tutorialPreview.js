import { ref } from 'vue';

/**
 * 教程「没声音时先看这几处」需要指给用户看「点此开启声音」按钮，
 * 但该按钮平时只在浏览器拦截自动播放时才出现。教程进入该步骤时临时把它展示出来，
 * 离开步骤或关闭教程后立刻恢复。
 */
export const unlockPreview = ref(false);
