import { beforeEach, afterEach, vi } from 'vitest';

/**
 * 冻结 Date.now() 到指定时刻。
 * 必须在测试文件的顶层调用（内部使用 beforeEach/afterEach）。
 * 返回的 advance 需在单个测试内使用。
 */
export function useFakeClock(isoString = '2026-01-01T00:00:00.000Z') {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date(isoString));
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    return {
        advance(ms) {
            vi.setSystemTime(new Date(Date.now() + ms));
        },
    };
}
