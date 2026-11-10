import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick, reactive, shallowRef } from 'vue';
import { useAudio } from '../../src/composables/useAudio';
import { createRoomClock } from '../../src/services/playbackSync';

vi.mock('../../src/services/socket', () => ({ socketService: { send: vi.fn() } }));

const state = (playbackId, currentPosition) => ({
    playbackId,
    nowPlaying: {
        music: { platform: 'netease', id: 'a', url: 'a.mp3', duration: 180000, artists: [] },
        currentPosition,
    },
    isPaused: false,
    seekNonce: 0,
});

describe('same-URL replay reaches the audio playback chain', () => {
    let app;
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => {
        app?.unmount();
        vi.useRealTimers();
    });

    it('reanchors, seeks an ended element, and resumes through seeked/canplay', async () => {
        let now = 0;
        const clock = createRoomClock(() => now);
        clock.sample(state('first', 40000));
        const player = reactive({
            nowPlaying: state('first', 40000).nowPlaying,
            connected: true,
            isPaused: false,
            localProgress: 40000,
            getCurrentProgress: () => clock.position(),
            getSyncStatus: () => clock.status(),
            tryReconnect: vi.fn(),
        });
        let currentTime = 40;
        let controls;
        const audio = {
            duration: 180,
            readyState: 4,
            seeking: false,
            ended: false,
            paused: false,
            playbackRate: 1,
            buffered: { length: 1, start: () => 0, end: () => 180 },
            play: vi.fn(async () => {
                audio.paused = false;
            }),
            pause: vi.fn(),
            get currentTime() {
                return currentTime;
            },
            set currentTime(value) {
                currentTime = value;
                audio.ended = false;
                audio.seeking = true;
                setTimeout(() => {
                    audio.seeking = false;
                    controls.checkAutoPlay();
                }, 0);
            },
        };
        app = createApp({
            setup() {
                controls = useAudio(shallowRef(audio), player);
                return () => null;
            },
        });
        app.mount(document.createElement('div'));
        await vi.advanceTimersByTimeAsync(200);
        audio.ended = true;
        audio.paused = true;
        currentTime = 180;
        controls.handleEnded();

        now = 1000;
        clock.sample(state('second', 0));
        player.nowPlaying = state('second', 0).nowPlaying;
        await nextTick();
        await vi.advanceTimersByTimeAsync(201);

        expect(currentTime).toBe(0);
        expect(audio.play).toHaveBeenCalledOnce();
        expect(audio.paused).toBe(false);
        await vi.advanceTimersByTimeAsync(3000);
        expect(player.tryReconnect).not.toHaveBeenCalled();
    });
});
