import { describe, it, expect } from 'vitest';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { formatDuration, formatTimeNow } from '../../src/domain/time.js';
import { useFakeClock } from '../helpers/clock.js';

// 时区无关性说明（2026-09-23 更新）
//
// 曾存在的缺陷：`formatDuration` 早期实现是 `dayjs(ms).format('mm:ss')`，把「时长」
// 当成 epoch 瞬间，取的是**本地时区**的 mm/ss 字段。65000ms 作为 epoch 是
// 1970-01-01T00:01:05Z，在 +05:30 渲染成 05:31:05，于是 `formatDuration(65000)` 返回
// '31:05' 而非 '01:05'。这是**真实用户可见的显示缺陷**：该函数被
// `src/components/PlayerControl.vue` 用来渲染播放进度与曲目时长，所有非整小时时区
// （印度 +05:30、查塔姆 +12:45 等）的用户看到的时长都是错的。
//
// 现已在 `src/domain/time.js` 改为按时长整除取模，与宿主时区无关；在 UTC 下与原实现
// 逐字节等价（下面几条断言因此保持不变，无需改动任何期望值）。
//
// **下面这个 describe 本身锁不住这个性质**：套件时区被钉死在 UTC
// （`vitest.config.js` 的 `test.env.TZ`），两种实现在此都会通过——钉住时区恰恰让缺陷
// 在套件里不可见。真正恢复检测能力的是文件末尾那个跑在子进程里的 describe，
// 它把被测代码放进真实的非整小时偏移时区里跑。改动 `formatDuration` 时请一并跑它。
describe('formatDuration', () => {
    it('空值与零返回 00:00', () => {
        expect(formatDuration(null)).toBe('00:00');
        expect(formatDuration(undefined)).toBe('00:00');
        expect(formatDuration(0)).toBe('00:00');
    });

    it('负数返回 00:00', () => {
        expect(formatDuration(-1)).toBe('00:00');
    });

    it('按 mm:ss 格式化', () => {
        expect(formatDuration(65000)).toBe('01:05');
    });

    it('满 60 分钟时显示 00:00（现有实现的已知限制，本次不修）', () => {
        // mm 是「小时内的分钟」，1 小时整格式化为 00:00 而非 60:00。
        // 修复时区缺陷时**没有**改动这个截断语义——修复只去掉时区依赖，在 UTC 下与原
        // 实现逐字节等价，所以本断言无需改动。截断本身属 spec §6.3「本次不改」范畴；
        // 单曲时长极少到 60 分钟，实际影响极小。
        // 黄金用例的职责是钉住现状，不是修正它——此处故意断言这个怪行为。
        expect(formatDuration(3600000)).toBe('00:00');
    });

    it('59 分 59 秒仍未跨小时，正常显示', () => {
        expect(formatDuration(3599000)).toBe('59:59');
    });
});

describe('formatTimeNow', () => {
    // 冻结当前时刻；helper 在 describe 作用域内注册 beforeEach/afterEach。
    // 本文件不需要推进时钟，故不接收返回值（接收了会触发 no-unused-vars）。
    useFakeClock('2026-01-01T13:05:09.000Z');

    // 本用例的已知盲区（在构造上无法消除，故记录而不「修」）：
    // 时区被钉为 UTC 后（`vitest.config.js` 的 `test.env.TZ`），「本地墙钟」与「UTC 墙钟」
    // 是同一个函数，因此本用例**无法**发现「让 formatTimeNow 返回 UTC 时间」这类回归。
    // 实测：把实现换成 getUTCHours/Minutes/Seconds 的形式，钉住时区时本文件 6 passed，
    // 不钉时在本机（+08:00）失败——`expected '13:05:09' to be '21:05:09'`。
    // 即本机在加 pin 之前具备这一检测能力，是 pin 把它移除了。
    // 但无法靠改断言找回它：套件钉在 UTC 时，本地与 UTC 本就不可区分。
    //
    // 因此本用例真正钉住的是**格式契约**——若实现返回 12 小时制 `hh:mm:ss`，
    // 或去掉秒只给 `HH:mm`，它依然会失败；它不再对**时区语义**有约束力。
    //
    // 实际损失有限：formatTimeNow 在 `src/` 下**零调用点**（只存在 `src/domain/time.js:8`
    // 的定义），是一个没有消费者的遗留函数。
    it('返回当地时间 HH:mm:ss', () => {
        // 先钉住冻结时钟本身：下面的期望值由 Date.now() 推导，而 formatTimeNow 读的是
        // 同一个 Date，若不先断言冻结生效，这条用例就会自我满足——useFakeClock 变成
        // 空操作时它照样通过。有了这一行，冻结失效会在此处直接失败。
        expect(Date.now()).toBe(Date.parse('2026-01-01T13:05:09.000Z'));

        // 用 Date 自行推导期望值，避免把测试机的时区写死
        const d = new Date(Date.now());
        const pad = (n) => String(n).padStart(2, '0');
        expect(formatTimeNow()).toBe(`${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`);
    });
});

// 时区无关性的锁定。
//
// 上面的 describe 整体跑在钉死为 UTC 的套件进程里，对「实现是否依赖宿主时区」这件事
// 没有鉴别力——两种实现都会通过。这里换个思路：把被测模块丢进一个真的跑在非整小时偏移
// 时区里的子进程，直接复现原始症状。
//
// 子进程通过 TZ 环境变量指定时区；Windows 上 Node 同样遵守该变量（本机实测），
// 所以这个锁定在开发机与 CI 上都成立。
describe('formatDuration 的时区无关性（子进程内复现）', () => {
    // 子进程用裸 import() 加载本模块，故依赖 package.json 的 "type": "module"。
    //
    // 路径不能用 `new URL(..., import.meta.url)` 推导：本套件跑在 jsdom 环境里，该环境下的
    // URL 解析以 http://localhost:3000/ 为基，会把源码路径解析成 http URL，子进程无法 import
    // （实测报 ERR_UNSUPPORTED_ESM_URL_SCHEME）。改用 cwd 拼绝对路径再转 file:// ——
    // 这与套件其余部分一样，假定 vitest 从包根目录启动。
    const timeModulePath = path.resolve(process.cwd(), 'src/domain/time.js');
    const timeModuleUrl = pathToFileURL(timeModulePath).href;

    /**
     * 在指定时区的子进程里对 ms 算出三者，一次性带回：
     *   实际生效的时区偏移 | 新实现结果 | 旧实现（把时长当 epoch 取本地字段）结果
     * 便于先钉前置条件、再用负控证明本用例确实有鉴别力。
     */
    const probeIn = (tz, ms) => {
        // 前置：路径推导错了要在这里当场失败，而不是让子进程丢一个难懂的 ERR_MODULE_NOT_FOUND。
        expect(existsSync(timeModulePath)).toBe(true);

        const code = `
            const ms = ${ms};
            const pad = (n) => String(n).padStart(2, '0');
            const legacy = pad(new Date(ms).getMinutes()) + ':' + pad(new Date(ms).getSeconds());
            import(${JSON.stringify(timeModuleUrl)}).then((m) => {
                process.stdout.write([new Date(ms).getTimezoneOffset(), m.formatDuration(ms), legacy].join('|'));
            });
        `;
        return execFileSync(process.execPath, ['-e', code], {
            env: { ...process.env, TZ: tz },
        })
            .toString()
            .split('|');
    };

    // 两个非整小时偏移，分别移动 mm 30 分与 45 分。
    // 尼泊尔 +05:45 未列入：Windows 的时区数据库把 Asia/Kathmandu 错映射成 +05:30
    // （本机实测），该偏移在此平台复现不出来，列进来只会多一条自我满足的用例。
    it.each([
        { tz: 'Asia/Kolkata', offsetMin: -330, legacy: '31:05' },
        { tz: 'Pacific/Chatham', offsetMin: -765, legacy: '46:05' },
    ])('$tz 下 65000ms 仍为 01:05（旧实现在此返回 $legacy）', ({ tz, offsetMin, legacy }) => {
        const [actualOffset, actual, legacyActual] = probeIn(tz, 65000);

        // 先钉住前置条件：子进程必须真的落在目标时区。少了这行，时区没生效时子进程
        // 退回 UTC，下面的断言会以 UTC 的正确值照样通过，整条用例退化成空操作——
        // 正是本文件反复警惕的那种自我满足。
        expect(Number(actualOffset)).toBe(offsetMin);
        // 负控：钉住「旧实现在该时区确实会算错」，证明本用例有鉴别力。
        // 若哪天断言被改成恒真，这一行会先失败。
        expect(legacyActual).toBe(legacy);
        // 正题。
        expect(actual).toBe('01:05');
    });
});
