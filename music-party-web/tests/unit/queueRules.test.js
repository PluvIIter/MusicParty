import { describe, it, expect } from 'vitest';
import { splitQueue, sortGroupItems } from '../../src/domain/queueRules.js';

const item = (id, token, name, priority = 'NORMAL') => ({
    queueId: id,
    priority,
    enqueuedBy: { token, name },
});

describe('sortGroupItems', () => {
    it('把 USER_TOP 提到最前，其余保持相对顺序', () => {
        const items = [item('a', 't1', 'A'), item('b', 't1', 'A', 'USER_TOP'), item('c', 't1', 'A')];
        expect(sortGroupItems(items).map((i) => i.queueId)).toEqual(['b', 'a', 'c']);
    });

    it('多个 USER_TOP 之间保持相对顺序', () => {
        const items = [item('a', 't1', 'A', 'USER_TOP'), item('b', 't1', 'A'), item('c', 't1', 'A', 'USER_TOP')];
        expect(sortGroupItems(items).map((i) => i.queueId)).toEqual(['a', 'c', 'b']);
    });

    it('不修改传入数组', () => {
        const items = [item('a', 't1', 'A'), item('b', 't1', 'A', 'USER_TOP')];
        const snapshot = items.map((i) => i.queueId);
        sortGroupItems(items);
        expect(items.map((i) => i.queueId)).toEqual(snapshot);
    });
});

describe('splitQueue', () => {
    it('挑出 GLOBAL_TOP 且不进入任何分组', () => {
        const q = [item('g', 't1', 'A', 'GLOBAL_TOP'), item('n', 't1', 'A')];
        const { topItems, userGroups } = splitQueue(q);
        expect(topItems.map((i) => i.queueId)).toEqual(['g']);
        expect(userGroups.flatMap((g) => g.items.map((i) => i.queueId))).toEqual(['n']);
    });

    it('置顶项出现在普通项之后时仍被挑出，且保持队列中的原始顺序', () => {
        // 关键在「GLOBAL_TOP 不在队首」：既有用例里置顶项要么是唯一项、要么全队列都是
        // GLOBAL_TOP，两者都无法发现「topItems 被重排」——优先级比较在它们内部恒为 0。
        // 这里的 token/名字刻意做成逆序（t9/Z 在前，t2/B 在后），
        // 于是任何按 token 或名字对 topItems 再排序的实现都会翻成 ['g2','g1']。
        const q = [
            item('n1', 't1', 'A'),
            item('g1', 't9', 'Z', 'GLOBAL_TOP'),
            item('n2', 't1', 'A'),
            item('g2', 't2', 'B', 'GLOBAL_TOP'),
        ];
        const { topItems, userGroups } = splitQueue(q);
        expect(topItems.map((i) => i.queueId)).toEqual(['g1', 'g2']);
        // 普通项仍然只按分组路径输出，不会被顺带带进 topItems
        expect(userGroups.flatMap((g) => g.items.map((i) => i.queueId))).toEqual(['n1', 'n2']);
    });

    it('按点歌者分组，组的顺序按首次出现', () => {
        const q = [item('a', 't2', 'Bob'), item('b', 't1', 'Alice'), item('c', 't2', 'Bob')];
        const { userGroups } = splitQueue(q);
        expect(userGroups.map((g) => g.token)).toEqual(['t2', 't1']);
        expect(userGroups[0].items.map((i) => i.queueId)).toEqual(['a', 'c']);
    });

    it('组上带点歌者名字', () => {
        const { userGroups } = splitQueue([item('a', 't1', 'Alice')]);
        expect(userGroups[0].name).toBe('Alice');
    });

    it('组内应用 USER_TOP 排序', () => {
        const q = [item('a', 't1', 'A'), item('b', 't1', 'A', 'USER_TOP')];
        const { userGroups } = splitQueue(q);
        expect(userGroups[0].items.map((i) => i.queueId)).toEqual(['b', 'a']);
    });

    it('空队列返回空结果', () => {
        expect(splitQueue([])).toEqual({ topItems: [], userGroups: [] });
    });

    it('全是 GLOBAL_TOP 时没有分组', () => {
        const q = [item('g1', 't1', 'A', 'GLOBAL_TOP'), item('g2', 't2', 'B', 'GLOBAL_TOP')];
        const { topItems, userGroups } = splitQueue(q);
        expect(topItems.map((i) => i.queueId)).toEqual(['g1', 'g2']);
        expect(userGroups).toEqual([]);
    });
});
