const TIME_EXP = /\[(\d{2,}):(\d{2})(?:\.(\d{2,3}))?\]/;

export const parseLyrics = (lrcString) => {
    if (!lrcString) return [];
    const lines = lrcString.split('\n');
    const result = [];

    for (let line of lines) {
        const match = TIME_EXP.exec(line);
        if (match) {
            const min = parseInt(match[1]);
            const sec = parseInt(match[2]);
            const ms = match[3] ? parseInt(match[3].padEnd(3, '0')) : 0;
            const time = min * 60 * 1000 + sec * 1000 + ms;
            const text = line.replace(TIME_EXP, '').trim();
            if (text) {
                result.push({ time, text });
            }
        }
    }
    return result;
};