import dayjs from 'dayjs';

/**
 * 把毫秒时长格式化为 mm:ss。
 *
 * mm 是「小时内的分钟」：满 60 分钟显示 00:00 而非 60:00。这是既有行为，本次不改
 * （见 tests/unit/time.test.js 的对应用例）。
 *
 * 实现刻意不走 `dayjs(ms)`：那会把「时长」当成 epoch 瞬间，取到**本地时区**的 mm/ss
 * 字段，于是非整小时偏移的时区（印度 +05:30、查塔姆 +12:45 等）渲染出错误的时长
 * （65000ms 显示成 31:05）。这里按时长直接整除取模，结果与宿主时区无关，
 * 且在 UTC 下与原实现逐字节等价。
 */
export const formatDuration = (ms) => {
    if (!ms || ms < 0) return '00:00';
    const totalSeconds = Math.floor(ms / 1000);
    const mm = Math.floor(totalSeconds / 60) % 60;
    const ss = totalSeconds % 60;
    return `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
};

export const formatTimeNow = () => {
    return dayjs().format('HH:mm:ss');
};
