import { defineConfig } from 'vitest/config';

export default defineConfig({
    test: {
        environment: 'jsdom',
        include: ['tests/**/*.test.js'],
        restoreMocks: true,
        // 时区固定为 UTC —— 让门禁在任何机器上都得到同一结果。
        //
        // 这一行最初是为一处**已修复**的缺陷加的：src/domain/time.js 的 formatDuration
        // 当时把「时长」当成 epoch 毫秒交给 `dayjs(ms).format('mm:ss')`，于是格式化结果取决
        // 于宿主时区在小时内的偏移量。整小时偏移（UTC、+08:00、-05:00）只移动 HH，mm/ss 不变，
        // 渲染与 UTC 逐字节相同；非整小时偏移（+05:30 / +05:45 / +12:45）会移动 mm——
        // 65000ms 在 +05:30 渲染为 05:31:05，mm 由 01 变 31。于是
        // tests/unit/time.test.js 的 3 条 formatDuration 用例在那些时区必然失败，
        // 而那 3 条失败与任何重构无关，只是把测试机的时区暴露了出来。
        //
        // 2026-09-23：该缺陷已修（formatDuration 改为按时长整除取模，不再读本地时钟字段，
        // 在 UTC 下与原实现逐字节等价），所以这一行不再是「掩盖缺陷」的补丁。
        // 保留它是为了门禁的可复现性——套件结果不该随测试机的时区变化。
        //
        // 注意：**时区无关性本身并不由这一行保证**。钉住 UTC 恰恰让「实现是否依赖宿主时区」
        // 在套件内不可观测（旧实现下 time.test.js 的 6 条用例照样全绿，已实测）。真正锁住它的是
        // tests/unit/time.test.js 末尾那个跑在子进程里的 describe。
        env: {
            TZ: 'UTC',
        },
    },
});
