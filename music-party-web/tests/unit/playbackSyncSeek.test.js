import { describe, it, expect } from 'vitest';
import { createRoomClock } from '../../src/services/playbackSync.js';

/**
 * 拖动进度条依赖服务端 seekNonce：它是「房间被主动跳转」的显式信号。
 * 没有它，向后拖会被 sample() 里的「迟到包不回退」规则当成旧状态包忽略。
 */
const state = (position, { id = 'a', duration = 180000, paused = false, seekNonce = 0 } = {}) => ({
    nowPlaying: {
        music: { platform: 'netease', id, url: 'https://example.invalid/a.mp3', duration },
        currentPosition: position,
    },
    isPaused: paused,
    seekNonce,
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
