/**
 * 只有非点歌者能投票。
 * @returns {boolean}
 */
export const canVote = ({ hasTrack, enqueuedById, userToken }) => !!hasTrack && enqueuedById !== userToken;

/**
 * 投票切歌的剩余等待秒数，下限为 0。
 * @returns {number} 秒
 */
export const waitTimeLeft = ({ isVoteSkipEnabled, hasTrack, localProgressMs, voteSkipWaitTime }) => {
    if (!isVoteSkipEnabled || !hasTrack) return 0;
    const elapsedSec = Math.floor(localProgressMs / 1000);
    return Math.max(0, voteSkipWaitTime - elapsedSec);
};

/**
 * 切歌按钮是否禁用。
 * 锁定的优先级最高；投票模式下点歌者可直接切歌，其他人需等过等待期。
 * 注意 canVote 与 waitTimeLeft 参数是已求值的结果，不是函数。
 * @returns {boolean}
 */
export const isSkipDisabled = ({ isSkipLocked, isVoteSkipEnabled, canVote, waitTimeLeft }) => {
    if (isSkipLocked) return true;
    if (isVoteSkipEnabled) {
        if (!canVote) return false;
        return waitTimeLeft > 0;
    }
    return false;
};
