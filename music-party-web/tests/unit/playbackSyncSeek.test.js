import { describe, it, expect } from 'vitest';
import { createRoomClock, createMediaSynchronizer } from '../../src/services/playbackSync.js';
import { createCompactReceiver } from '../../src/services/compactSync.js';

/**
 * 拖动进度条依赖服务端 seekNonce：它是「房间被主动跳转」的显式信号。
 * 没有它，向后拖会被 sample() 里的「迟到包不回退」规则当成旧状态包忽略。
 */
const state = (position, { id = 'a', duration = 180000, paused = false, seekNonce = 0, playbackId } = {}) => ({
    playbackId,
    nowPlaying: {
        music: { platform: 'netease', id, url: 'https://example.invalid/a.mp3', duration },
        currentPosition: position,
    },
    isPaused: paused,
    seekNonce,
});

describe('playback instance clock revisions', () => {
    it('reanchors a replay even when the previous pass never received an end sample', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(40000, { playbackId: 'first' }));
        const before = clock.status().revision;
        now = 2000;
        clock.sample(state(0, { playbackId: 'second' }));
        expect(clock.position()).toBe(0);
        expect(clock.status()).toMatchObject({ revision: before + 1, reason: 'repeat' });
        now = 3000;
        expect(clock.position()).toBe(1000);
    });

    it('reanchors a same-song paused instance but does not infer repeats within one instance', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(175000, { playbackId: 'first' }));
        now = 7000;
        clock.sample(state(0, { playbackId: 'first' }));
        expect(clock.position()).toBe(181000);
        clock.sample(state(0, { playbackId: 'second', paused: true }));
        now = 12000;
        expect(clock.position()).toBe(0);
        expect(clock.status().reason).toBe('repeat');
    });

    it('a new same-URL instance forces an ended media element to seek back', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        const media = createMediaSynchronizer();
        const input = (ended, mediaTime) => ({
            now,
            mediaTime,
            targetTime: clock.position() / 1000,
            ready: true,
            seeking: false,
            blocked: false,
            connected: true,
            buffering: false,
            paused: false,
            ended,
            ...clock.status(),
        });
        clock.sample(state(40000, { playbackId: 'first' }));
        media.tick(input(false, 40));
        now = 2000;
        clock.sample(state(0, { playbackId: 'second' }));
        expect(media.tick(input(true, 180))).toEqual({ rate: 1, seek: 0 });
    });

    it('reanchors after compact recovery coalesces an intermediate empty track', () => {
        let now = 0;
        const clock = createRoomClock(() => now);
        const receiver = createCompactReceiver({ apply: clock.sample, requestSnapshot: () => {} });
        const frame = (seq, base, full, patch) => ({ v: 1, epoch: 'server', seq, base, full, state: patch });
        receiver.receive(frame(1, 1, true, { ...state(30000, { playbackId: 'first' }), queue: [] }));
        now = 1000;
        receiver.receive(frame(3, 2, false, state(0, { playbackId: 'second' })));
        receiver.receive(frame(2, 1, false, { nowPlaying: null, playbackId: null, isLoading: true }));
        expect(clock.position()).toBe(0);
        expect(clock.status()).toMatchObject({ revision: 2, reason: 'repeat' });
    });

    it('reanchors short tracks on every pass even without a tail heartbeat', () => {
        let now = 0;
        const clock = createRoomClock(() => now);
        for (let pass = 0; pass < 10; pass++) {
            clock.sample(state(0, { playbackId: `pass-${pass}`, duration: 900 }));
            expect(clock.position()).toBe(0);
            expect(clock.status().revision).toBe(pass + 1);
            now += 900;
        }
    });

    it('pause, resume and seek preserve instance identity while changing clock revision', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(40000, { playbackId: 'first' }));
        clock.sample(state(41000, { playbackId: 'first', paused: true }));
        clock.sample(state(10000, { playbackId: 'first', paused: true, seekNonce: 1 }));
        now = 9000;
        expect(clock.position()).toBe(10000);
        clock.sample(state(10000, { playbackId: 'first', seekNonce: 1 }));
        now = 10000;
        expect(clock.position()).toBe(11000);
        expect(clock.status().revision).toBe(4);
    });
});

describe('房间时钟与拖动进度（seekNonce）', () => {
    it('首次采样只做锚定，不算跳转', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(30000));
        expect(clock.position()).toBe(30000);
        const { revision, reason } = clock.status();
        expect(reason).toBe('track');
        expect(revision).toBe(1);
    });

    it('nonce 不变时，向后跳的包被当成迟到包忽略（不重锚）', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(60000));
        now = 3000;
        clock.sample(state(20000)); // 同一个 nonce：判定为过期状态
        expect(clock.position()).toBe(62000); // 仍按原时钟推进（60000 + 2000）
    });

    it('nonce 自增时向后拖，立即重锚（这是拖动生效的关键）', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(60000, { seekNonce: 0 }));
        now = 3000;
        clock.sample(state(20000, { seekNonce: 1 }));
        expect(clock.position()).toBe(20000);
        expect(clock.status().reason).toBe('seek');
    });

    it('nonce 自增时向前拖，同样重锚并自增 revision', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(10000, { seekNonce: 0 }));
        const before = clock.status().revision;
        now = 2000;
        clock.sample(state(90000, { seekNonce: 1 }));
        expect(clock.position()).toBe(90000);
        expect(clock.status().revision).toBe(before + 1);
    });

    it('跳转后的时钟继续按流逝时间推进', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(60000, { seekNonce: 0 }));
        now = 2000;
        clock.sample(state(30000, { seekNonce: 1 }));
        now = 5000;
        expect(clock.position()).toBe(33000);
    });

    it('暂停状态下拖动也会重锚，且不叠加流逝时间', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample(state(60000, { paused: true, seekNonce: 0 }));
        now = 2000;
        clock.sample(state(40000, { paused: true, seekNonce: 1 }));
        expect(clock.position()).toBe(40000);
        now = 9000;
        expect(clock.position()).toBe(40000);
    });

    it('缺少 seekNonce 的旧服务端仍能工作（视为不变）', () => {
        let now = 1000;
        const clock = createRoomClock(() => now);
        clock.sample({ ...state(50000), seekNonce: undefined });
        expect(clock.position()).toBe(50000);
        expect(() => clock.sample({ ...state(51000), seekNonce: undefined })).not.toThrow();
    });
});
