import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';
import { useAudio } from '../../src/composables/useAudio';

vi.mock('../../src/services/socket', () => ({ socketService: { send: vi.fn() } }));
const cleanups = [];
afterEach(() => {
    cleanups.splice(0).forEach((cleanup) => cleanup());
    vi.useRealTimers();
});

const setup = async (readyState) => {
    vi.useFakeTimers();
    const audio = {
        readyState,
        duration: 180,
        currentTime: 0,
        paused: true,
        ended: false,
        seeking: false,
        playbackRate: 1,
        buffered: { length: 0 },
        play: vi.fn().mockRejectedValue(new DOMException('Gesture required', 'NotAllowedError')),
        load: vi.fn(),
        pause: vi.fn(),
    };
    const player = reactive({
        nowPlaying: null,
        playbackId: 'first',
        isPaused: false,
        connected: true,
        getCurrentProgress: () => 0,
        getSyncStatus: () => ({ fresh: true, revision: 1, sampleId: 1, sampleAt: 0 }),
    });
    let state;
    const host = document.createElement('div');
    document.body.append(host);
    const app = createApp({
        setup() {
            state = useAudio(ref(audio), player);
            return () => h('div');
        },
    });
    app.mount(host);
    cleanups.push(() => {
        app.unmount();
        host.remove();
    });
    player.nowPlaying = {
        music: {
            id: 'new',
            platform: 'netease',
            url: 'fixture.wav',
            duration: 180000,
            artists: ['Fixture'],
            name: 'Fixture',
            coverUrl: '',
        },
    };
    await nextTick();
    await nextTick();
    await Promise.resolve();
    expect(state.needsUserGesture.value).toBe(true);
    expect(audio.play).toHaveBeenCalledTimes(1);
    return { audio, player, state };
};

describe('local sound unlock', () => {
    it.each([0, 1])('plays synchronously inside the gesture when readyState is %s', async (readyState) => {
        const { audio, state } = await setup(readyState);
        let finish;
        audio.play.mockImplementation(
            () =>
                new Promise((resolve) => {
                    finish = resolve;
                })
        );
        state.retryLocally();
        expect(audio.play).toHaveBeenCalledTimes(2);
        expect(state.needsUserGesture.value).toBe(false);
        finish();
        await Promise.resolve();
        expect(state.needsUserGesture.value).toBe(false);
    });

    it('retains the unlock action when playback is rejected again', async () => {
        const { audio, state } = await setup(0);
        state.retryLocally();
        await Promise.resolve();
        expect(audio.play).toHaveBeenCalledTimes(2);
        expect(state.needsUserGesture.value).toBe(true);
    });

    it('resets failed local playback when the same source starts a new instance', async () => {
        const { audio, player, state } = await setup(0);
        state.isErrorState.value = true;
        state.retryCount.value = 2;
        audio.play.mockResolvedValue();
        player.playbackId = 'second';
        await nextTick();
        await nextTick();
        await Promise.resolve();
        expect(state.isErrorState.value).toBe(false);
        expect(state.retryCount.value).toBe(0);
        expect(state.needsUserGesture.value).toBe(false);
        expect(audio.play).toHaveBeenCalledTimes(2);
    });

    it('does not unpause a room through the local unlock action', async () => {
        const { audio, player, state } = await setup(0);
        player.isPaused = true;
        state.retryLocally();
        expect(audio.play).toHaveBeenCalledTimes(1);
        expect(state.needsUserGesture.value).toBe(true);
    });
});
