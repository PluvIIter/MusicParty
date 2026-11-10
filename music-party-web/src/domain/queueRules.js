/**
 * 组内排序：个人置顶（USER_TOP）提到最前，其余保持原有相对顺序。
 * 返回新数组，不修改入参。
 */
export const sortGroupItems = (items) =>
    [...items].sort((a, b) => {
        const aIsUserTop = a.priority === 'USER_TOP';
        const bIsUserTop = b.priority === 'USER_TOP';
        if (aIsUserTop && !bIsUserTop) return -1;
        if (!aIsUserTop && bIsUserTop) return 1;
        return 0;
    });

/**
 * 轮询模式下的队列视图切分：
 * - topItems：全局置顶（GLOBAL_TOP）单独置顶展示，不进入分组
 * - userGroups：其余项按点歌者 token 分组，组的顺序按 token 首次出现；
 *   组内应用 USER_TOP 排序
 *
 * 组上的 isGuest 语义（与展示逻辑一致）：
 *   只要组内的点歌者本身是游客，或组内出现私人 FM 合成项，整组就按游客样式展示；
 *   组的 isGuest 初值取该 token 第一次出现时的 isGuest。
 *
 * totalCount 统计该 token 在队列中的**全部**条目（含全局置顶），
 * 用于成员条目上的数量徽标；items 只收集非全局置顶的条目。
 *
 * @param {Array} queue 队列条目
 * @param {(group: object) => boolean} [keepGroup] 过滤组的谓词。默认保留「不在分组内的全局置顶之外还有条目」的组；
 *        调用方可以传入自己的规则（例如「还可被认领的离线组也要展示」）。
 * @returns {{ topItems: Array, userGroups: Array<{ token: string, name: string, isGuest: boolean, totalCount: number, items: Array }> }}
 */
export const splitQueue = (queue, keepGroup = (group) => group.items.length > 0) => {
    const topItems = queue.filter((item) => item.priority === 'GLOBAL_TOP');
    const groupsMap = new Map();

    queue.forEach((item) => {
        const token = item.enqueuedBy.token;
        if (!groupsMap.has(token)) {
            groupsMap.set(token, {
                token,
                name: item.enqueuedBy.name,
                isGuest: item.enqueuedBy.isGuest,
                totalCount: 0,
                items: [],
            });
        }
        const group = groupsMap.get(token);
        group.totalCount++;
        if (item.enqueuedBy.isGuest || item.music?.platform === 'netease-fm') group.isGuest = true;
        if (item.priority !== 'GLOBAL_TOP') group.items.push(item);
    });

    const userGroups = Array.from(groupsMap.values())
        .filter(keepGroup)
        .map((group) => ({ ...group, items: sortGroupItems(group.items) }));

    return { topItems, userGroups };
};

const isMemberToken = (token) =>
    typeof token === 'string' && !!token.trim() && !['SYSTEM', 'ADMIN'].includes(token) && !token.startsWith('__');

export const canReorderItem = (item, token, mode) =>
    isMemberToken(token) &&
    isMemberToken(item?.enqueuedBy?.token) &&
    (mode === 'SEQUENTIAL' || item.enqueuedBy.token === token) &&
    item.priority === 'REGULAR' &&
    item.music?.platform !== 'netease-fm';

export const reorderableItems = (queue, token, mode) => queue.filter((item) => canReorderItem(item, token, mode));

export const isValidReorder = (queue, token, queueId, beforeQueueId, mode) => {
    const items = reorderableItems(queue, token, mode);
    const from = items.findIndex((item) => item.queueId === queueId);
    if (from < 0 || queueId === beforeQueueId) return false;
    const to = beforeQueueId === null ? items.length : items.findIndex((item) => item.queueId === beforeQueueId);
    return to >= 0 && to !== from + 1;
};
