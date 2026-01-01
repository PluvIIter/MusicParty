import dayjs from 'dayjs';

export const formatDuration = (ms) => {
    if (!ms || ms < 0) return "00:00";
    return dayjs(ms).format('mm:ss');
};

export const formatTimeNow = () => {
    return dayjs().format('HH:mm:ss');
};