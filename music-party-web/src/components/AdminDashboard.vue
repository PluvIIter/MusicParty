<template>
    <Transition
        enter-active-class="transition duration-500 ease-out"
        enter-from-class="opacity-0 translate-y-4"
        enter-to-class="opacity-100 translate-y-0"
        leave-active-class="transition duration-300 ease-in"
        leave-from-class="opacity-100 translate-y-0"
        leave-to-class="opacity-0 translate-y-4"
    >
        <div
            v-if="adminStore.showDashboard"
            class="fixed inset-0 z-[120] flex items-center justify-center p-4 bg-brand/40 backdrop-blur-md"
        >
            <div
                class="w-full max-w-5xl h-[90vh] bg-surface shadow-2xl border border-line flex flex-col chamfer-br overflow-hidden"
            >
                <!-- Header -->
                <div
                    class="px-4 py-3 md:px-6 md:py-4 bg-surface-raised border-b border-line flex justify-between items-center flex-shrink-0"
                >
                    <h2 class="text-xl font-black font-sans text-ink flex items-center gap-3">
                        <Settings class="w-5 h-5 text-accent animate-spin-slow" /> 管理终端
                    </h2>
                    <div class="flex items-center gap-4">
                        <button
                            @click="adminStore.showDashboard = false"
                            class="p-2 bg-surface-sunken hover:bg-line text-ink transition-colors"
                        >
                            <X class="w-6 h-6" />
                        </button>
                    </div>
                </div>

                <!-- Content Area -->
                <div class="flex-1 min-h-0 overflow-y-auto p-4 md:p-6 space-y-6 custom-scroll">
                    <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">
                        <!-- Left Column: Playback & Parameters (7 cols) -->
                        <div class="lg:col-span-7 space-y-6">
                            <!-- Section: Playback Mastery -->
                            <div class="bg-surface-raised border border-line shadow-sm overflow-hidden chamfer-br">
                                <div class="p-3 bg-brand text-ink-inverse flex items-center justify-between">
                                    <div class="flex items-center gap-2">
                                        <PlayCircle class="w-4 h-4 text-accent" />
                                        <span class="text-xs font-bold uppercase tracking-widest font-mono"
                                            >播放核心</span
                                        >
                                    </div>
                                </div>
                                <div class="p-4 space-y-4">
                                    <!-- Control Grid -->
                                    <div class="grid grid-cols-3 gap-3">
                                        <button
                                            @click="execPlayerAction('PAUSE')"
                                            class="flex flex-col items-center justify-center p-4 bg-surface border border-line hover:border-accent hover:text-accent transition-all group"
                                        >
                                            <Pause
                                                v-if="!playerStore.isPaused"
                                                class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform"
                                            />
                                            <Play
                                                v-else
                                                class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform"
                                            />
                                            <span class="text-[10px] font-bold font-mono">{{
                                                playerStore.isPaused ? '恢复' : '暂停'
                                            }}</span>
                                        </button>
                                        <button
                                            @click="execPlayerAction('SKIP')"
                                            class="flex flex-col items-center justify-center p-4 bg-surface border border-line hover:border-accent hover:text-accent transition-all group"
                                        >
                                            <SkipForward
                                                class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform"
                                            />
                                            <span class="text-[10px] font-bold font-mono">切歌</span>
                                        </button>
                                        <button
                                            @click="modeDialogOpen = true"
                                            aria-label="选择播放模式"
                                            :disabled="!playerStore.connected || playerStore.isPlayModeLocked"
                                            :title="`${currentMode.label}：${currentMode.description}`"
                                            class="flex flex-col items-center justify-center p-4 bg-surface border border-line hover:border-accent hover:text-accent transition-all group disabled:opacity-40 disabled:cursor-not-allowed"
                                        >
                                            <component
                                                :is="currentMode.icon"
                                                class="w-6 h-6 mb-2 group-hover:scale-110 transition-transform"
                                            />
                                            <span class="text-[10px] font-bold font-mono">{{ currentMode.label }}</span>
                                        </button>
                                    </div>

                                    <!-- Permission Locks -->
                                    <div class="grid grid-cols-3 gap-2">
                                        <button
                                            v-for="lock in locks"
                                            :key="lock.key"
                                            @click="toggleLock(lock.key, !lock.value)"
                                            class="flex items-center justify-center gap-2 py-2 px-1 border transition-all text-[9px] font-bold font-mono"
                                            :class="
                                                lock.value
                                                    ? 'bg-danger/10 border-danger/30 text-danger'
                                                    : 'bg-surface-raised border-line text-ink-subtle hover:border-accent hover:text-accent'
                                            "
                                        >
                                            <Lock v-if="lock.value" class="w-3 h-3" />
                                            <Unlock v-else class="w-3 h-3" />
                                            {{ lock.cnLabel }}
                                        </button>
                                    </div>

                                    <!-- Playback Core Settings (Shuffle & Vote Skip) -->
                                    <div class="space-y-3 p-3 bg-surface border border-line rounded-sm">
                                        <!-- Shuffle Controls -->
                                        <div
                                            v-if="playerStore.isShuffle || playerStore.isRoundRobin"
                                            class="pb-3 border-b border-line"
                                        >
                                            <div class="flex items-center justify-between">
                                                <div class="flex flex-col">
                                                    <span class="text-[10px] font-bold text-ink">{{
                                                        playerStore.allowOfflineShuffle ? '含离线' : '仅在线'
                                                    }}</span>
                                                    <span class="text-[8px] text-ink-subtle font-mono uppercase"
                                                        >曲库范围</span
                                                    >
                                                </div>
                                                <button
                                                    @click="execPlayerAction('TOGGLE_ALLOW_OFFLINE')"
                                                    class="w-8 h-4 rounded-full relative transition-colors"
                                                    :class="
                                                        playerStore.allowOfflineShuffle ? 'bg-accent' : 'bg-line-strong'
                                                    "
                                                >
                                                    <div
                                                        class="absolute top-0.5 left-0.5 w-3 h-3 bg-surface-raised rounded-full transition-transform duration-300"
                                                        :style="{
                                                            transform: playerStore.allowOfflineShuffle
                                                                ? 'translateX(16px)'
                                                                : 'translateX(0)',
                                                        }"
                                                    ></div>
                                                </button>
                                            </div>
                                        </div>

                                        <!-- Vote Skip Controls (Always Visible) -->
                                        <div class="space-y-3">
                                            <div class="flex items-center justify-between">
                                                <div class="flex flex-col">
                                                    <span class="text-[10px] font-bold text-ink">投票切歌模式</span>
                                                    <span class="text-[8px] text-ink-subtle font-mono uppercase"
                                                        >达到比例后自动切歌；关闭则仅管理员可切</span
                                                    >
                                                </div>
                                                <button
                                                    @click="
                                                        updateInstantConfig({
                                                            voteSkipEnabled: !playerStore.config.voteSkipEnabled,
                                                        })
                                                    "
                                                    class="w-8 h-4 rounded-full relative transition-colors"
                                                    :class="
                                                        playerStore.config.voteSkipEnabled
                                                            ? 'bg-accent'
                                                            : 'bg-line-strong'
                                                    "
                                                >
                                                    <div
                                                        class="absolute top-0.5 left-0.5 w-3 h-3 bg-surface-raised rounded-full transition-transform duration-300"
                                                        :style="{
                                                            transform: playerStore.config.voteSkipEnabled
                                                                ? 'translateX(16px)'
                                                                : 'translateX(0)',
                                                        }"
                                                    ></div>
                                                </button>
                                            </div>
                                            <div
                                                v-if="playerStore.config.voteSkipEnabled"
                                                class="grid grid-cols-2 gap-3 pt-1 border-t border-line"
                                            >
                                                <div class="space-y-1">
                                                    <label class="block text-[8px] font-bold text-ink-subtle uppercase"
                                                        >比例 (0.1-1.0)</label
                                                    >
                                                    <input
                                                        :value="playerStore.config.voteSkipThreshold"
                                                        @change="
                                                            (e) =>
                                                                updateInstantConfig({
                                                                    voteSkipThreshold: parseFloat(e.target.value),
                                                                })
                                                        "
                                                        type="number"
                                                        step="0.1"
                                                        min="0.1"
                                                        max="1.0"
                                                        class="w-full bg-surface-raised border border-line px-2 py-1 text-[10px] outline-none focus:border-accent"
                                                    />
                                                </div>
                                                <div class="space-y-1">
                                                    <label class="block text-[8px] font-bold text-ink-subtle uppercase"
                                                        >等待时间 (秒)</label
                                                    >
                                                    <input
                                                        :value="playerStore.config.voteSkipWaitTime"
                                                        @change="
                                                            (e) =>
                                                                updateInstantConfig({
                                                                    voteSkipWaitTime: parseInt(e.target.value),
                                                                })
                                                        "
                                                        type="number"
                                                        min="0"
                                                        class="w-full bg-surface-raised border border-line px-2 py-1 text-[10px] outline-none focus:border-accent"
                                                    />
                                                </div>
                                            </div>
                                        </div>
                                        <div class="flex items-center justify-between gap-3 pt-3 border-t border-line">
                                            <div class="flex flex-col">
                                                <span class="text-[10px] font-bold text-ink">允许认领离线成员歌单</span>
                                                <span class="text-[8px] text-ink-subtle"
                                                    >成员离线超过 10
                                                    秒后歌单可被接管；关闭后停止认领，已认领的保留</span
                                                >
                                            </div>
                                            <button
                                                type="button"
                                                role="switch"
                                                aria-label="允许认领离线成员歌单"
                                                :aria-checked="!!playerStore.config.allowPlaylistClaim"
                                                @click="togglePlaylistClaim"
                                                :disabled="claimSettingsBusy"
                                                class="w-8 h-4 shrink-0 rounded-full relative transition-colors"
                                                :class="
                                                    playerStore.config.allowPlaylistClaim
                                                        ? 'bg-accent'
                                                        : 'bg-line-strong'
                                                "
                                            >
                                                <div
                                                    class="w-3 h-3 bg-surface-raised rounded-full absolute top-0.5 transition-all"
                                                    :class="
                                                        playerStore.config.allowPlaylistClaim ? 'left-4' : 'left-0.5'
                                                    "
                                                ></div>
                                            </button>
                                        </div>
                                        <div class="flex items-center justify-between gap-3 pt-3 border-t border-line">
                                            <div class="flex flex-col">
                                                <span class="text-[10px] font-bold text-ink">播完保留歌单</span>
                                                <span class="text-[8px] text-ink-subtle"
                                                    >循环保留已播歌曲；轮询时每位成员轮完再重复</span
                                                >
                                            </div>
                                            <button
                                                type="button"
                                                role="switch"
                                                aria-label="播完保留歌单"
                                                :aria-checked="!!playerStore.config.retainPlaylist"
                                                @click="
                                                    updateInstantConfig({
                                                        retainPlaylist: !playerStore.config.retainPlaylist,
                                                    })
                                                "
                                                class="w-8 h-4 shrink-0 rounded-full relative transition-colors"
                                                :class="
                                                    playerStore.config.retainPlaylist ? 'bg-accent' : 'bg-line-strong'
                                                "
                                            >
                                                <div
                                                    class="w-3 h-3 bg-surface-raised rounded-full absolute top-0.5 transition-all"
                                                    :class="playerStore.config.retainPlaylist ? 'left-4' : 'left-0.5'"
                                                ></div>
                                            </button>
                                        </div>
                                        <div class="flex items-center justify-between gap-3 pt-3 border-t border-line">
                                            <div class="flex flex-col">
                                                <span class="text-[10px] font-bold text-ink">允许拖动进度条</span>
                                                <span class="text-[8px] text-ink-subtle"
                                                    >成员可拖动进度跳转，房间内所有人同步跟随（默认关闭）</span
                                                >
                                            </div>
                                            <button
                                                type="button"
                                                role="switch"
                                                aria-label="允许拖动进度条"
                                                :aria-checked="!!playerStore.config.allowSeek"
                                                @click="
                                                    updateInstantConfig({ allowSeek: !playerStore.config.allowSeek })
                                                "
                                                class="w-8 h-4 shrink-0 rounded-full relative transition-colors"
                                                :class="playerStore.config.allowSeek ? 'bg-accent' : 'bg-line-strong'"
                                            >
                                                <div
                                                    class="w-3 h-3 bg-surface-raised rounded-full absolute top-0.5 transition-all"
                                                    :class="playerStore.config.allowSeek ? 'left-4' : 'left-0.5'"
                                                ></div>
                                            </button>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <!-- Section: Private Radio -->
                            <div class="bg-surface-raised border border-line shadow-sm overflow-hidden chamfer-br">
                                <div class="p-3 bg-brand text-ink-inverse flex items-center gap-2">
                                    <Radio class="w-4 h-4" />
                                    <span class="text-xs font-bold uppercase tracking-widest font-mono">私人电台</span>
                                </div>
                                <div class="p-4 space-y-4">
                                    <!-- 模式开关（两个开关都是"点选开启、再点关闭"；全关即整体关闭，无需独立"关闭"按钮） -->
                                    <div class="space-y-1">
                                        <span class="text-[9px] font-bold text-ink-subtle font-mono uppercase"
                                            >模式 · 开关</span
                                        >
                                        <div class="grid grid-cols-2 gap-1 p-1 bg-surface border border-line">
                                            <button
                                                @click="setPrivateDjMode(privateDj.mode === 'FM' ? 'OFF' : 'FM')"
                                                class="py-1.5 text-[10px] font-bold transition-colors"
                                                :class="
                                                    privateDj.mode === 'FM'
                                                        ? 'bg-accent text-accent-contrast'
                                                        : 'text-ink-muted hover:bg-line'
                                                "
                                            >
                                                私人FM
                                            </button>
                                            <button
                                                @click="setPrivateDjMode(privateDj.mode === 'DJ' ? 'OFF' : 'DJ')"
                                                class="py-1.5 text-[10px] font-bold transition-colors"
                                                :class="
                                                    privateDj.mode === 'DJ'
                                                        ? 'bg-accent text-accent-contrast'
                                                        : 'text-ink-muted hover:bg-line'
                                                "
                                            >
                                                私人DJ
                                            </button>
                                        </div>
                                        <p class="text-[8px] text-ink-subtle">
                                            点选即开启，再点已选中的模式即关闭；私人DJ模式=先播语音再播歌；加入队列功能固定为私人FM
                                        </p>
                                    </div>

                                    <!-- 三个功能开关 -->
                                    <div class="space-y-2">
                                        <div
                                            v-for="sw in privateDjSwitches"
                                            :key="sw.field"
                                            class="flex items-center justify-between p-2 bg-surface border border-line"
                                        >
                                            <div class="flex flex-col">
                                                <span class="text-[10px] font-bold text-ink">{{ sw.label }}</span>
                                                <span class="text-[8px] text-ink-subtle font-mono uppercase">{{
                                                    sw.hint
                                                }}</span>
                                            </div>
                                            <button
                                                @click="togglePrivateDjSwitch(sw.field)"
                                                class="w-8 h-4 rounded-full relative transition-colors disabled:opacity-40"
                                                :class="privateDj[sw.field] ? 'bg-accent' : 'bg-line-strong'"
                                                :disabled="privateDj.mode === 'OFF'"
                                            >
                                                <div
                                                    class="absolute top-0.5 left-0.5 w-3 h-3 bg-surface-raised rounded-full transition-transform duration-300"
                                                    :style="{
                                                        transform: privateDj[sw.field]
                                                            ? 'translateX(16px)'
                                                            : 'translateX(0)',
                                                    }"
                                                ></div>
                                            </button>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <!-- Right Column: Environment & Danger Zone (5 cols) -->
                        <div class="lg:col-span-5 space-y-6">
                            <!-- Section: Room Environment -->
                            <div class="bg-surface-raised border border-line shadow-sm overflow-hidden chamfer-br">
                                <div class="p-3 bg-brand text-ink-inverse flex items-center gap-2">
                                    <Globe class="w-4 h-4" />
                                    <span class="text-xs font-bold uppercase tracking-widest font-mono">环境配置</span>
                                </div>
                                <div class="p-4 space-y-4">
                                    <!-- Password -->
                                    <div class="space-y-2">
                                        <label class="block text-[10px] font-bold text-ink-subtle uppercase font-mono"
                                            >房间进入密码</label
                                        >
                                        <div class="flex gap-2">
                                            <input
                                                v-model="roomPassword"
                                                placeholder="留空则设为公开"
                                                class="flex-1 bg-surface border border-line px-3 py-1.5 text-xs outline-none focus:border-accent"
                                            />
                                            <button
                                                @click="updateRoomPassword"
                                                class="bg-brand text-ink-inverse px-3 py-1.5 text-[10px] font-bold hover:bg-accent hover:text-accent-contrast"
                                            >
                                                设置
                                            </button>
                                        </div>
                                    </div>
                                    <!-- Toggles -->
                                    <div class="grid grid-cols-2 gap-3">
                                        <div
                                            class="p-3 bg-surface border border-line flex flex-col items-center gap-2 rounded-sm"
                                        >
                                            <span class="text-[9px] font-bold text-ink-subtle uppercase font-mono"
                                                >直播推流</span
                                            >
                                            <button
                                                @click="toggleStream"
                                                class="w-10 h-5 rounded-full relative transition-colors"
                                                :class="playerStore.streamActive ? 'bg-accent' : 'bg-line-strong'"
                                            >
                                                <div
                                                    class="absolute top-0.5 left-0.5 w-4 h-4 bg-surface-raised rounded-full transition-transform duration-300"
                                                    :style="{
                                                        transform: playerStore.streamActive
                                                            ? 'translateX(20px)'
                                                            : 'translateX(0)',
                                                    }"
                                                ></div>
                                            </button>
                                        </div>
                                        <div class="p-3 bg-surface border border-line flex flex-col gap-2 rounded-sm">
                                            <span
                                                class="text-[9px] font-bold text-ink-subtle uppercase font-mono text-center"
                                                >数据清理</span
                                            >
                                            <div class="flex flex-col gap-1 w-full">
                                                <button
                                                    @click="clearData('QUEUE')"
                                                    class="w-full py-1 border border-line text-[8px] font-bold hover:bg-danger/10 hover:text-danger transition-all"
                                                >
                                                    清理播放队列
                                                </button>
                                                <button
                                                    @click="clearData('OFFLINE')"
                                                    class="w-full py-1 border border-line text-[8px] font-bold hover:bg-danger/10 hover:text-danger transition-all"
                                                >
                                                    清理不在线成员歌曲
                                                </button>
                                                <button
                                                    @click="clearData('CHAT')"
                                                    class="w-full py-1 border border-line text-[8px] font-bold hover:bg-danger/10 hover:text-danger transition-all"
                                                >
                                                    清理聊天记录
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <!-- Section: System Parameters -->
                            <div class="bg-surface-raised border border-line shadow-sm overflow-hidden chamfer-br">
                                <div class="p-3 bg-brand text-ink-inverse flex items-center gap-2">
                                    <Sliders class="w-4 h-4" />
                                    <span class="text-xs font-bold uppercase tracking-widest font-mono">系统参数</span>
                                </div>
                                <div class="p-4 space-y-4">
                                    <div class="grid grid-cols-2 gap-x-4 gap-y-3">
                                        <div v-for="(val, key) in systemFields" :key="key" class="space-y-1">
                                            <label
                                                class="block text-[9px] font-bold text-ink-subtle font-mono uppercase"
                                                >{{ key }}</label
                                            >
                                            <input
                                                v-model.number="configProxy[val.field]"
                                                type="number"
                                                class="w-full bg-surface border border-line px-2 py-1.5 text-xs outline-none focus:border-accent font-mono"
                                            />
                                        </div>
                                    </div>

                                    <button
                                        @click="saveSystemConfig"
                                        class="w-full bg-brand text-ink-inverse py-2 text-xs font-bold hover:bg-accent hover:text-accent-contrast transition-colors flex items-center justify-center gap-2"
                                    >
                                        <Save class="w-4 h-4" /> 应用并保存所有更改
                                    </button>
                                </div>
                            </div>
                            <!-- Section: Credentials -->
                            <div class="bg-surface-raised border border-line shadow-sm overflow-hidden chamfer-br">
                                <div class="p-3 bg-brand text-ink-inverse flex items-center gap-2">
                                    <Database class="w-4 h-4" />
                                    <span class="text-xs font-bold uppercase tracking-widest font-mono"
                                        >音乐源配置</span
                                    >
                                </div>
                                <div class="p-4 space-y-4">
                                    <div
                                        v-for="plat in platforms"
                                        :key="plat.id"
                                        class="space-y-2 border-b border-line pb-3 last:border-0 last:pb-0"
                                    >
                                        <div class="flex justify-between items-center">
                                            <span class="text-[10px] font-bold text-ink-muted font-mono">{{
                                                plat.label
                                            }}</span>
                                            <button
                                                @click="togglePlatform(plat.id)"
                                                role="switch"
                                                :aria-label="'启用音乐源：' + plat.label"
                                                :aria-checked="plat.enabled"
                                                class="w-8 h-4 rounded-full relative transition-colors"
                                                :class="plat.enabled ? 'bg-accent' : 'bg-line-strong'"
                                            >
                                                <div
                                                    class="absolute top-0.5 left-0.5 w-3 h-3 bg-surface-raised rounded-full transition-transform duration-300"
                                                    :style="{
                                                        transform: plat.enabled ? 'translateX(16px)' : 'translateX(0)',
                                                    }"
                                                ></div>
                                            </button>
                                        </div>
                                        <p class="text-[10px] text-ink-subtle">
                                            交付模式：{{ plat.deliveryMode }}（部署配置，重启生效）
                                        </p>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </Transition>

    <!-- 播放模式选择 -->
    <Teleport to="body">
        <div
            v-if="modeDialogOpen"
            class="fixed inset-0 z-[130] bg-brand/50 backdrop-blur-sm flex items-center justify-center p-4"
            @click.self="modeDialogOpen = false"
        >
            <div
                role="dialog"
                aria-modal="true"
                aria-label="选择播放模式"
                class="w-full max-w-sm bg-surface-raised border border-line shadow-xl p-4 space-y-3"
            >
                <div class="flex items-center justify-between">
                    <h3 class="font-bold text-ink">播放模式</h3>
                    <button type="button" @click="modeDialogOpen = false" aria-label="关闭播放模式选择">
                        <X class="w-4 h-4 text-ink-muted" />
                    </button>
                </div>
                <div class="space-y-1">
                    <button
                        v-for="mode in modes"
                        :key="mode.value"
                        type="button"
                        @click="selectPlayMode(mode.value)"
                        class="w-full flex items-center gap-3 p-2 text-left border transition-colors"
                        :class="
                            playerStore.playMode === mode.value
                                ? 'border-accent text-accent bg-accent/10'
                                : 'border-line text-ink hover:bg-surface-sunken'
                        "
                    >
                        <component :is="mode.icon" class="w-4 h-4 shrink-0" />
                        <span class="flex-1 min-w-0">
                            <span class="block text-xs font-bold">{{ mode.label }}</span>
                            <span class="block text-[10px] text-ink-muted">{{ mode.description }}</span>
                        </span>
                        <Check v-if="playerStore.playMode === mode.value" class="w-4 h-4 shrink-0" />
                    </button>
                </div>
            </div>
        </div>
    </Teleport>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue';
import { useAdminStore } from '../stores/admin';
import { usePlayerStore } from '../stores/player';
import { adminApi } from '../api/admin';
import { useToast } from '../composables/useToast';
import { toAdminConfigPayload } from '../domain/adminConfig';
import { PLAY_MODES } from '../domain/playModes';
import { useSourcesStore } from '../stores/sources';
import {
    Settings,
    X,
    Check,
    ListOrdered,
    Shuffle,
    Repeat,
    Repeat1,
    Pause,
    Play,
    SkipForward,
    Lock,
    Unlock,
    ShieldAlert,
    Save,
    AlertTriangle,
    PlayCircle,
    Database,
    Globe,
    Sliders,
    Radio,
} from 'lucide-vue-next';

const adminStore = useAdminStore();
const playerStore = usePlayerStore();
const sources = useSourcesStore();
onMounted(() => sources.load());
const { success, error, warning } = useToast();

// 播放模式：方块按钮 + 弹窗选择（与暂停/切歌按钮同款）
const MODE_ICONS = { SEQUENTIAL: ListOrdered, SHUFFLE: Shuffle, ROUND_ROBIN: Repeat, REPEAT_ONE: Repeat1 };
const modes = computed(() => PLAY_MODES.map((mode) => ({ ...mode, icon: MODE_ICONS[mode.value] })));
const currentMode = computed(() => modes.value.find((mode) => mode.value === playerStore.playMode) || modes.value[0]);
const modeDialogOpen = ref(false);
const selectPlayMode = (mode) => {
    modeDialogOpen.value = false;
    if (mode !== playerStore.playMode) execPlayerAction('SET_PLAY_MODE', mode);
};

const claimSettingsBusy = ref(false);
const togglePlaylistClaim = async () => {
    if (claimSettingsBusy.value) return;
    claimSettingsBusy.value = true;
    try {
        const response = await fetch('/api/admin/playlist-claim', {
            method: 'POST',
            cache: 'no-store',
            headers: { 'X-Admin-Password': adminStore.adminPassword, 'Content-Type': 'application/json' },
            body: JSON.stringify({ allowPlaylistClaim: !playerStore.config.allowPlaylistClaim }),
        });
        if (!response.ok) throw new Error('歌单认领设置保存失败');
        success((await response.json()).message);
    } catch (e) {
        error(e.message);
    } finally {
        claimSettingsBusy.value = false;
    }
};

const roomPassword = ref('');

// Config Proxy for editing
const configProxy = ref({ ...playerStore.config });

const systemFields = {
    队列最大长度: { field: 'maxQueueSize' },
    历史记录容量: { field: 'maxHistorySize' },
    用户点歌上限: { field: 'maxUserSongs' },
    导入单次上限: { field: 'maxPlaylistImportSize' },
    聊天记录容量: { field: 'maxChatHistorySize' },
    '发言频率限制(ms)': { field: 'minChatIntervalMs' },
};

watch(
    () => playerStore.config,
    (newVal) => {
        configProxy.value = { ...newVal };
    },
    { deep: true }
);

const saveSystemConfig = async () => {
    try {
        const data = await adminApi.updateConfig(adminStore.adminPassword, toAdminConfigPayload(configProxy.value));
        success(data.message);
    } catch (e) {
        error('配置同步失败');
    }
};

const updateInstantConfig = async (update) => {
    try {
        const data = await adminApi.updateConfig(adminStore.adminPassword, toAdminConfigPayload(update));
        success(data.message);
    } catch (e) {
        error('配置同步失败');
    }
};

const locks = computed(() => [
    { key: 'PAUSE', cnLabel: '锁定暂停', value: playerStore.isPauseLocked },
    { key: 'SKIP', cnLabel: '锁定切歌', value: playerStore.isSkipLocked },
    { key: 'SHUFFLE', cnLabel: '锁定模式', value: playerStore.isPlayModeLocked },
]);

const platforms = computed(() =>
    [...new Set([...sources.sources.map((source) => source.id), ...Object.keys(playerStore.config.sources || {})])].map(
        (id) => ({
            id,
            label: sources.descriptor(id)?.label || id,
            enabled: playerStore.config.sources?.[id]?.enabled ?? sources.isEnabled(id),
            deliveryMode: playerStore.config.sources?.[id]?.deliveryMode || sources.descriptor(id)?.delivery?.mode,
        })
    )
);

// 私人电台/私人DJ 状态（来自 config.privateDj，服务端广播；mode 即开关：OFF=关闭/FM=私人FM/DJ=私人DJ）
const privateDj = computed(
    () =>
        playerStore.config.privateDj || {
            mode: 'OFF',
            fillBlankEnabled: false,
            joinQueueEnabled: false,
            custodyEnabled: false,
        }
);

const privateDjSwitches = [
    { field: 'fillBlankEnabled', label: '填充空白', hint: '队列无有效歌曲时播FM/DJ' },
    { field: 'joinQueueEnabled', label: '加入队列', hint: '随机/轮询模式生效·固定私人FM' },
    { field: 'custodyEnabled', label: '播放托管', hint: '无视队列·仅播FM/DJ' },
];

const setPrivateDjMode = async (mode) => {
    if (mode !== 'OFF' && playerStore.config.recommendationsAvailable === false) {
        error('部署指定的私人FM/DJ音源未启用或不支持此功能');
        return;
    }
    try {
        const data = await adminApi.updatePrivateDj(adminStore.adminPassword, { mode });
        success(data.message);
    } catch (e) {
        error('模式切换失败');
    }
};

const togglePrivateDjSwitch = async (field) => {
    const update = { [field]: !privateDj.value[field] };
    try {
        const data = await adminApi.updatePrivateDj(adminStore.adminPassword, update);
        success(data.message);
    } catch (e) {
        error('功能开关更新失败');
    }
};

const execPlayerAction = async (action, playMode) => {
    try {
        const data = await adminApi.playerAction(adminStore.adminPassword, action, playMode);
        success(data.message);
    } catch (e) {
        error('指令执行失败');
    }
};

const toggleLock = async (type, locked) => {
    try {
        const data = await adminApi.setLock(adminStore.adminPassword, type, locked);
        success(data.message);
    } catch (e) {
        error('锁定同步失败');
    }
};

const updateRoomPassword = async () => {
    try {
        const data = await adminApi.setRoomPassword(adminStore.adminPassword, roomPassword.value);
        success(data.message);
    } catch (e) {
        error('密码更新失败');
    }
};

const toggleStream = async () => {
    try {
        const data = await adminApi.setStream(adminStore.adminPassword, !playerStore.streamActive);
        success(data.message);
    } catch (e) {
        error('直播流控制失败');
    }
};

const clearData = async (target) => {
    const targetName =
        {
            QUEUE: '播放队列',
            OFFLINE: '不在线成员的点播歌曲',
            CHAT: '聊天记录',
        }[target] || target;

    if (!confirm(`确定要清空 ${targetName} 吗?`)) return;
    try {
        const data = await adminApi.clearData(adminStore.adminPassword, target);
        warning(data.message);
    } catch (e) {
        error('清理操作失败');
    }
};

const togglePlatform = async (platformId) => {
    const update = {
        sources: {
            [platformId]: {
                enabled: !(playerStore.config.sources?.[platformId]?.enabled ?? sources.isEnabled(platformId)),
            },
        },
    };
    try {
        const data = await adminApi.updateConfig(adminStore.adminPassword, toAdminConfigPayload(update));
        success(data.message);
    } catch (e) {
        error('平台状态切换失败');
    }
};
</script>

<style scoped>
.animate-spin-slow {
    animation: spin 8s linear infinite;
}
@keyframes spin {
    from {
        transform: rotate(0deg);
    }
    to {
        transform: rotate(360deg);
    }
}
.custom-scroll::-webkit-scrollbar {
    width: 4px;
}
.custom-scroll::-webkit-scrollbar-track {
    background: transparent;
}
.custom-scroll::-webkit-scrollbar-thumb {
    @apply bg-line rounded-full;
}
.custom-scroll::-webkit-scrollbar-thumb:hover {
    @apply bg-line-strong;
}
</style>
