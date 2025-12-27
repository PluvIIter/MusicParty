// File Path: music-party-web\src\components\AuthOverlay.vue

<template>
  <div v-if="!passed" class="fixed inset-0 z-[200] bg-medical-50 flex items-center justify-center p-4">
    <div class="bg-white p-8 shadow-2xl border border-medical-200 w-full max-w-md chamfer-br relative">
      <!-- 装饰 -->
      <div class="absolute top-0 left-0 w-2 h-full bg-medical-900"></div>

      <div class="mb-6">
        <h2 class="text-2xl font-black text-medical-900 tracking-tighter">
          {{ isSetupMode ? 'INITIALIZE SYSTEM' : 'PbSECURITY ACCESS' }}
        </h2>
        <p class="text-xs font-mono text-medical-500 mt-1">
          {{ isSetupMode ? 'PLEASE CONFIGURE ROOM ACCESS.' : 'RESTRICTED AREA. ENTER PASSCODE.' }}
        </p>
      </div>

      <div class="space-y-4">
        <!-- 只有在非 Setup 模式，或者 Setup 模式下想设置密码时才显示输入框 -->
        <input
            v-if="!isSetupMode || (isSetupMode && setupType === 'password')"
            v-model="inputPassword"
            type="password"
            :placeholder="isSetupMode ? 'SET NEW PASSWORD' : 'INPUT PASSWORD'"
            @keyup.enter="handleAction"
            class="w-full bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent font-monoTZ text-center tracking-widest text-lg"
            autofocus
        />

        <!-- 正常模式 / 设置密码模式 的确认按钮 -->
        <button
            v-if="!isSetupMode || setupType === 'password'"
            @click="handleAction"
            :disabled="loading"
            class="w-full bg-medical-900 text-white font-bold py-3 hover:bg-accent transition-colors disabled:opacity-50"
        >
          {{ loading ? 'VERIFYING...' : (isSetupMode ? 'CONFIRM PASSWORD' : 'UNLOCK') }}
        </button>

        <!-- Setup 模式下的选择区域 -->
        <div v-if="isSetupMode && setupType === 'initial'" class="space-y-3">
          <button
              @click="setupType = 'password'"
              class="w-full bg-medical-900 text-white font-bold py-3 hover:bg-accent transition-colors chamfer-br"
          >
            SET PASSWORD PROTECTION
          </button>

          <div class="relative flex py-2 items-center">
            <div class="flex-grow border-t border-medical-200"></div>
            <span class="flex-shrink-0 mx-4 text-medical-300 text-xs font-mono">OR</span>
            <div class="flex-grow border-t border-medical-200"></div>
          </div>

          <button
              @click="setupNoPassword"
              class="w-full bg-white border border-medical-300 text-medical-500 font-bold py-3 hover:bg-medical-100 transition-colors hover:text-medical-900"
          >
            NO PASSWORD (PUBLIC)
          </button>
        </div>

        <!-- Setup 模式下返回选择 -->
        <button
            v-if="isSetupMode && setupType === 'password'"
            @click="setupType = 'initial'"
            class="w-full text-xs text-medical-400 hover:text-medical-900 mt-2 underline"
        >
          &lt; BACK
        </button>

      </div>

      <div v-if="errorMsg" class="mt-4 text-center text-red-500 font-mono text-xs animate-pulse">
        > ERROR: {{ errorMsg }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import axios from 'axios';

const emit = defineEmits(['unlocked']);

const passed = ref(false);
const isSetupMode = ref(false);
const setupType = ref('initial'); // 'initial' | 'password'
const inputPassword = ref('');
const errorMsg = ref('');
const loading = ref(false);

const STORAGE_KEY = 'mp_room_password';

// 检查状态
const checkStatus = async () => {
  loading.value = true;
  try {
    const statusRes = await axios.get('/api/auth/status');
    // 解构新的返回对象
    const { isSetup, hasProtection } = statusRes.data;

    if (!isSetup) {
      // 1. 未初始化 -> 进入设置模式
      isSetupMode.value = true;
    } else {
      // 2. 已初始化
      if (!hasProtection) {
        // A. 无密码模式 -> 直接放行
        passed.value = true;
        emit('unlocked');
      } else {
        // B. 有密码模式 -> 检查本地缓存
        const cachedPass = localStorage.getItem(STORAGE_KEY);
        if (cachedPass) {
          await verify(cachedPass, true);
        }
      }
    }
  } catch (e) {
    errorMsg.value = "CONNECTION FAILED";
  } finally {
    loading.value = false;
  }
};

const verify = async (pwd, isAuto = false) => {
  try {
    await axios.post('/api/auth/verify', { password: pwd });
    // 验证通过
    localStorage.setItem(STORAGE_KEY, pwd);
    passed.value = true;
    emit('unlocked');
  } catch (e) {
    if (!isAuto) {
      errorMsg.value = "INVALID PASSWORD";
      inputPassword.value = '';
    } else {
      // 自动登录失败，清除缓存
      localStorage.removeItem(STORAGE_KEY);
    }
  }
};

// 设置密码
const setup = async () => {
  if (!inputPassword.value) {
    errorMsg.value = "PASSWORD CANNOT BE EMPTY";
    return;
  }
  await performSetup(inputPassword.value);
};

// 设置为无密码
const setupNoPassword = async () => {
  await performSetup("");
};

// 统一的 Setup 请求逻辑
const performSetup = async (pwd) => {
  loading.value = true;
  try {
    await axios.post('/api/auth/setup', { password: pwd });
    // 设置成功后，自动视为验证通过
    if(pwd) localStorage.setItem(STORAGE_KEY, pwd);
    passed.value = true;
    emit('unlocked');
  } catch (e) {
    errorMsg.value = "SETUP FAILED";
    loading.value = false;
  }
};

const handleAction = () => {
  if (loading.value) return;
  errorMsg.value = '';
  if (isSetupMode.value) {
    setup();
  } else {
    verify(inputPassword.value);
  }
};

onMounted(() => {
  checkStatus();
});
</script>