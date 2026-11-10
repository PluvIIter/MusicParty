import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { computed, createApp, nextTick, onMounted, onUnmounted, reactive, ref, useId, watch } from 'vue';

const source = readFileSync('src/components/PlayModePicker.vue', 'utf8');
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import\s[\s\S]*?;\s*$/gm, '');
const setupPicker = new Function(
    'dependencies',
    `
    const { computed, nextTick, onMounted, onUnmounted, ref, useId, watch, defineProps, defineEmits, PLAY_MODES } = dependencies;
    const ListOrdered = {}, Shuffle = {}, Repeat = {}, Repeat1 = {};
    ${script}
    return { root, trigger, open, toggle, select, onKeydown, onFocusOut };
`
);
const PLAY_MODES = ['SEQUENTIAL', 'SHUFFLE', 'ROUND_ROBIN', 'REPEAT_ONE'].map((value) => ({ value }));

describe('accessible play mode picker', () => {
    let props, picker, emit, app, host, trigger, buttons;
    beforeEach(() => {
        props = reactive({ modelValue: 'ROUND_ROBIN', disabled: false });
        emit = vi.fn();
        host = document.createElement('div');
        trigger = document.createElement('button');
        buttons = PLAY_MODES.map(() => {
            const b = document.createElement('button');
            b.setAttribute('role', 'menuitemradio');
            return b;
        });
        host.append(trigger, ...buttons);
        document.body.append(host);
        app = createApp({
            setup() {
                picker = setupPicker({
                    computed,
                    // eslint-disable-next-line vue/valid-next-tick -- Inject Vue's scheduler into the extracted setup.
                    nextTick,
                    onMounted,
                    onUnmounted,
                    ref,
                    useId,
                    watch,
                    PLAY_MODES,
                    defineProps: () => props,
                    defineEmits: () => emit,
                });
                return () => null;
            },
        });
        app.mount(document.createElement('div'));
        picker.root.value = host;
        picker.trigger.value = trigger;
    });
    afterEach(() => {
        app.unmount();
        host.remove();
    });
    it('focuses the current mode, wraps arrow keys and restores focus on Escape', async () => {
        await picker.toggle();
        expect(document.activeElement).toBe(buttons[2]);
        picker.onKeydown({ key: 'End', preventDefault: vi.fn() });
        expect(document.activeElement).toBe(buttons[3]);
        picker.onKeydown({ key: 'ArrowDown', preventDefault: vi.fn() });
        expect(document.activeElement).toBe(buttons[0]);
        picker.onKeydown({ key: 'Escape', preventDefault: vi.fn(), stopPropagation: vi.fn() });
        expect(picker.open.value).toBe(false);
        expect(document.activeElement).toBe(trigger);
        expect(emit).not.toHaveBeenCalled();
    });
    it('sends a specified selection and returns focus to the trigger', async () => {
        await picker.toggle();
        picker.select('SHUFFLE');
        expect(emit).toHaveBeenCalledExactlyOnceWith('select', 'SHUFFLE');
        expect(document.activeElement).toBe(trigger);
        expect(picker.open.value).toBe(false);
    });
    it('outside pointer and focus leaving dismiss without stealing focus', async () => {
        await picker.toggle();
        document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }));
        expect(picker.open.value).toBe(false);
        await picker.toggle();
        picker.onFocusOut({ relatedTarget: document.body });
        expect(picker.open.value).toBe(false);
    });
    it('closes on permission loss and cannot open or select while disabled', async () => {
        await picker.toggle();
        props.disabled = true;
        await nextTick();
        expect(picker.open.value).toBe(false);
        await picker.toggle();
        picker.select('SHUFFLE');
        expect(picker.open.value).toBe(false);
        expect(emit).not.toHaveBeenCalled();
    });
});
