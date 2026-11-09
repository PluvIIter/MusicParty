import { describe, it, expect } from 'vitest';
import { canVote, waitTimeLeft, isSkipDisabled } from '../../src/domain/voteRules.js';

describe('canVote', () => {
    it('无曲目时不可投票', () => {
        expect(canVote({ hasTrack: false, enqueuedById: 'a', userToken: 'b' })).toBe(false);
    });

    it('点歌者本人不可投票', () => {
        expect(canVote({ hasTrack: true, enqueuedById: 'tok', userToken: 'tok' })).toBe(false);
    });

    it('非点歌者可投票', () => {
        expect(canVote({ hasTrack: true, enqueuedById: 'a', userToken: 'b' })).toBe(true);
    });

    it('双方 token 均为 undefined 时不可投票（严格不等号把两个 undefined 视作同一个人）', () => {
        // 有曲目、但既拿不到点歌者 token 也拿不到自己的 token 时，`undefined !== undefined`
        // 为 false，于是被判成「点歌者本人」而拒绝投票。这是重构前代码原样继承来的行为，
        // 不是本次重构引入的，也不是要修的东西——钉住它是为了说明：
        // 「没有 token」与「同一个人」在这个函数里无法区分，改动断言前请先确认这是有意的。
        //
        // 注意别把这个结论外推：全等号是严格比较，只有**两侧同为 undefined** 才会被判成
        // 同一人。`null !== undefined` 为 true，所以「一方 null、一方 undefined」会被判成
        // 两个人而**允许**投票（实测：两种混搭都返回 true，而 null/null 返回 false）。
        expect(canVote({ hasTrack: true, enqueuedById: undefined, userToken: undefined })).toBe(false);
    });
});

describe('waitTimeLeft', () => {
    it('投票未开启时恒为 0', () => {
        expect(
            waitTimeLeft({ isVoteSkipEnabled: false, hasTrack: true, localProgressMs: 0, voteSkipWaitTime: 15 })
        ).toBe(0);
    });

    it('无曲目时为 0', () => {
        expect(
            waitTimeLeft({ isVoteSkipEnabled: true, hasTrack: false, localProgressMs: 0, voteSkipWaitTime: 15 })
        ).toBe(0);
    });

    it('按已播放秒数递减', () => {
        expect(
            waitTimeLeft({ isVoteSkipEnabled: true, hasTrack: true, localProgressMs: 5000, voteSkipWaitTime: 15 })
        ).toBe(10);
    });

    it('已播放秒数取下取整', () => {
        expect(
            waitTimeLeft({ isVoteSkipEnabled: true, hasTrack: true, localProgressMs: 5900, voteSkipWaitTime: 15 })
        ).toBe(10);
    });

    it('超过等待时间后钳为 0，不出现负数', () => {
        expect(
            waitTimeLeft({ isVoteSkipEnabled: true, hasTrack: true, localProgressMs: 999000, voteSkipWaitTime: 15 })
        ).toBe(0);
    });
});

describe('isSkipDisabled', () => {
    const open = { isSkipLocked: false, isVoteSkipEnabled: false, canVote: true, waitTimeLeft: 0 };

    it('切歌被锁定时恒为禁用', () => {
        expect(isSkipDisabled({ ...open, isSkipLocked: true })).toBe(true);
    });

    it('非投票模式下不禁用', () => {
        expect(isSkipDisabled(open)).toBe(false);
    });

    it('投票模式下点歌者可直接切歌，不受等待时间限制', () => {
        expect(isSkipDisabled({ isSkipLocked: false, isVoteSkipEnabled: true, canVote: false, waitTimeLeft: 12 })).toBe(
            false
        );
    });

    it('投票模式下等待期内禁用', () => {
        expect(isSkipDisabled({ isSkipLocked: false, isVoteSkipEnabled: true, canVote: true, waitTimeLeft: 3 })).toBe(
            true
        );
    });

    it('投票模式下等待期结束后可点', () => {
        expect(isSkipDisabled({ isSkipLocked: false, isVoteSkipEnabled: true, canVote: true, waitTimeLeft: 0 })).toBe(
            false
        );
    });

    it('锁定的优先级高于投票模式', () => {
        expect(isSkipDisabled({ isSkipLocked: true, isVoteSkipEnabled: true, canVote: false, waitTimeLeft: 0 })).toBe(
            true
        );
    });
});
