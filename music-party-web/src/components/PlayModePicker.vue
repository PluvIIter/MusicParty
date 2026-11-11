<template>
    <div ref="root" class="relative" @focusout="onFocusOut" @keydown="onKeydown">
        <button
            :id="id"
            ref="trigger"
            type="button"
            :disabled="disabled"
            aria-haspopup="menu"
            :aria-expanded="open"
            :aria-controls="menuId"
            :aria-label="`播放模式：${current.label}`"
            :title="disabled ? '离线、游客或播放模式已锁定' : `${current.label}：${current.description}`"
            class="p-2 text-ink-muted hover:text-accent disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            :class="boxed ? 'bg-surface-sunken rounded-sm' : ''"
            @click="toggle"
        >
            <component :is="icons[current.value]" class="w-5 h-5" aria-hidden="true" />
        </button>
        <Transition
            enter-active-class="transition duration-150 ease-out"
            enter-from-class="opacity-0 translate-y-2"
            enter-to-class="opacity-100 translate-y-0"
            leave-active-class="transition duration-100 ease-in"
            leave-to-class="opacity-0 translate-y-2"
        >
            <div
                v-if="open"
                :id="menuId"
                role="menu"
                aria-label="选择播放模式"
                :style="menuStyle"
                class="absolute bottom-full mb-2 w-44 max-w-[calc(100vw-1rem)] p-1 bg-surface-raised border border-line shadow-xl z-50"
            >
                <button
                    v-for="mode in PLAY_MODES"
                    :key="mode.value"
                    type="button"
                    role="menuitemradio"
                    :aria-checked="modelValue === mode.value"
                    :title="mode.description"
                    class="w-full flex items-center gap-3 p-2 text-xs text-left hover:bg-surface-sunken focus:bg-surface-sunken focus:outline-accent"
                    :class="modelValue === mode.value ? 'text-accent' : 'text-ink'"
                    @click="select(mode.value)"
                >
                    <component :is="icons[mode.value]" class="w-4 h-4" aria-hidden="true" />
                    {{ mode.label }}
                </button>
            </div>
        </Transition>
    </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue';
import { ListOrdered, Shuffle, Repeat, Repeat1 } from 'lucide-vue-next';
import { PLAY_MODES } from '../domain/playModes';

const props = defineProps({
    id: { type: String, default: undefined },
    modelValue: { type: String, default: 'SEQUENTIAL' },
    disabled: { type: Boolean, default: false },
    // 移动端控制条里需要和其它按钮一致的方块背景
    boxed: { type: Boolean, default: false },
});
const emit = defineEmits(['select']);
const icons = { SEQUENTIAL: ListOrdered, SHUFFLE: Shuffle, ROUND_ROBIN: Repeat, REPEAT_ONE: Repeat1 };
const current = computed(() => PLAY_MODES.find(({ value }) => value === props.modelValue) || PLAY_MODES[0]);
const root = ref(null);
const trigger = ref(null);
const open = ref(false);
const menuStyle = ref({ right: '0px' });
const menuId = useId();
const options = () => [...(root.value?.querySelectorAll('[role="menuitemradio"]') || [])];
const close = (restore = false) => {
    open.value = false;
    if (restore) trigger.value?.focus();
};
const toggle = async () => {
    if (props.disabled) return;
    if (open.value) return close();
    const rect = root.value?.getBoundingClientRect();
    const width = Math.min(176, window.innerWidth - 16);
    const left = Math.max(8, Math.min(rect?.right - width || 8, window.innerWidth - width - 8));
    menuStyle.value = { left: `${left - (rect?.left || 0)}px` };
    open.value = true;
    await nextTick();
    options()[PLAY_MODES.findIndex(({ value }) => value === current.value.value)]?.focus();
};
const select = (mode) => {
    if (!props.disabled) emit('select', mode);
    close(true);
};
const onKeydown = (event) => {
    if (event.key === 'Escape' && open.value) {
        event.preventDefault();
        event.stopPropagation();
        close(true);
    } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        if (!open.value) return toggle();
        const buttons = options();
        const index = buttons.indexOf(document.activeElement);
        const next =
            event.key === 'Home'
                ? 0
                : event.key === 'End'
                  ? buttons.length - 1
                  : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
        buttons[next]?.focus();
    }
};
const onOutside = (event) => {
    if (!root.value?.contains(event.target)) close();
};
const onFocusOut = (event) => {
    if (!root.value?.contains(event.relatedTarget)) close();
};
watch(
    () => props.disabled,
    (disabled) => {
        if (disabled) close();
    }
);
onMounted(() => document.addEventListener('pointerdown', onOutside));
onUnmounted(() => document.removeEventListener('pointerdown', onOutside));
</script>
