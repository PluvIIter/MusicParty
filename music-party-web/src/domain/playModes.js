export const PLAY_MODES = [
    { value: 'SEQUENTIAL', label: '顺序播放', description: '按队列顺序播放' },
    { value: 'SHUFFLE', label: '完全随机', description: '从所有可播放歌曲中随机选曲' },
    { value: 'ROUND_ROBIN', label: '轮询播放', description: '依次轮到每位成员，按个人偏好选曲' },
    { value: 'REPEAT_ONE', label: '单曲循环', description: '重复播放当前歌曲' },
];

export const isPlayMode = (mode) => PLAY_MODES.some(({ value }) => value === mode);
