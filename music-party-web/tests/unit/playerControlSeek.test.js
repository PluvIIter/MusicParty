import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { createApp, computed, onUnmounted, reactive, ref, watch } from 'vue';

const source = readFileSync('src/components/PlayerControl.vue', 'utf8');
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import\s[\s\S]*?;\s*$/gm, '');
const setupControl = new Function(
    'dependencies',
    `
    const { ref, computed, watch, onUnmounted, usePlayerStore, useUiStore, useUserStore, useToast, formatDuration, voteRules } = dependencies;
    ${script}
    return { barRef, dragging, onSeekPointerDown, onSeekPointerUp, onSeekPointerCancel, onSeekKeydown };
`
);

describe('playback-bound seek gestures', () => {
    let player, controls, app, host;
    const pointer = (clientX = 50, pointerId = 1) => ({ clientX, pointerId });
    beforeEach(() => {
        player = reactive({
            playbackId: 'first',
            connected: true,
            isLoading: false,
            isErrorState: false,
            config: { allowSeek: true },
            localProgress: 20000,
            nowPlaying: { music: { id: 'a', duration: 180000 } },
            seekTo: vi.fn(),
        });
        app = createApp({
            setup() {
                controls = setupControl({
                    ref,
                    computed,
                    watch,
                    onUnmounted,
                    usePlayerStore: () => player,
                    useUiStore: () => ({}),
                    useUserStore: () => ({}),
                    useToast: () => ({}),
                    formatDuration: vi.fn(),
                    voteRules: {},
                });
                return () => null;
            },
        });
        host = document.createElement('div');
        app.mount(host);
        controls.barRef.value = {
            getBoundingClientRect: () => ({ left: 0, width: 100 }),
            setPointerCapture: vi.fn(),
            releasePointerCapture: vi.fn(),
        };
    });
    afterEach(() => app.unmount());

    it('captures the instance and duration at drag start and sends once on release', () => {
        controls.onSeekPointerDown(pointer(20));
        expect(player.seekTo).not.toHaveBeenCalled();
        controls.onSeekPointerUp(pointer(50));
        expect(player.seekTo).toHaveBeenCalledExactlyOnceWith(90000, 'first');
        expect(controls.dragging.value).toBe(false);
    });

    it.each(['different song', 'same song new pass'])('cancels on %s and ignores the old release', (change) => {
        controls.onSeekPointerDown(pointer());
        if (change === 'different song') player.nowPlaying = { music: { id: 'b', duration: 90000 } };
        player.playbackId = 'second';
        expect(controls.dragging.value).toBe(false);
        controls.onSeekPointerUp(pointer(80));
        expect(player.seekTo).not.toHaveBeenCalled();
    });

    it.each(['permission', 'connection', 'loading'])('cancels when %s changes during dragging', (change) => {
        controls.onSeekPointerDown(pointer());
        if (change === 'permission') player.config.allowSeek = false;
        if (change === 'connection') player.connected = false;
        if (change === 'loading') player.isLoading = true;
        controls.onSeekPointerUp(pointer(80));
        expect(controls.dragging.value).toBe(false);
        expect(player.seekTo).not.toHaveBeenCalled();
    });

    it('checks the instance again on release even before a watcher can cancel', () => {
        controls.onSeekPointerDown(pointer());
        Object.defineProperty(player, 'playbackId', { value: 'second', configurable: true, writable: true });
        controls.onSeekPointerUp(pointer(80));
        expect(player.seekTo).not.toHaveBeenCalled();
    });

    it('pointer cancellation and a mismatched pointer cannot send a seek', () => {
        controls.onSeekPointerDown(pointer());
        controls.onSeekPointerUp(pointer(80, 2));
        expect(controls.dragging.value).toBe(true);
        controls.onSeekPointerCancel();
        controls.onSeekPointerUp(pointer(80));
        expect(player.seekTo).not.toHaveBeenCalled();
    });

    it('disables all seek gestures without an instance from the server', () => {
        player.playbackId = null;
        controls.onSeekPointerDown(pointer());
        controls.onSeekKeydown({ key: 'Home', preventDefault: vi.fn() });
        expect(controls.dragging.value).toBe(false);
        expect(player.seekTo).not.toHaveBeenCalled();
    });
});
