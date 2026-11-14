// 下载文件名里的扩展名解析：优先看响应头，其次看 URL 文件名，最后回退 mp3。
// 覆盖音频（mp3/flac/m4a/aac/ogg/wav）与视频（mp4/webm，例如 B 站 HTML5 降级表示）。

const MIME_EXTENSIONS = {
    'audio/mpeg': 'mp3',
    'audio/mp3': 'mp3',
    'audio/flac': 'flac',
    'audio/x-flac': 'flac',
    'audio/mp4': 'm4a',
    'audio/x-m4a': 'm4a',
    'audio/aac': 'aac',
    'audio/ogg': 'ogg',
    'audio/wav': 'wav',
    'audio/x-wav': 'wav',
    'audio/webm': 'webm',
    'video/mp4': 'mp4',
    'video/webm': 'webm',
};

const EXTENSIONS = new Set(['mp3', 'flac', 'm4a', 'aac', 'ogg', 'wav', 'mp4', 'm4v', 'webm', 'mov', 'mkv']);
const UNSAFE_FILENAME_CHARS = new Set(['\\', '/', ':', '*', '?', '"', '<', '>', '|']);
const MAX_TITLE_LENGTH = 120;

const isPlayableExtension = (extension) => EXTENSIONS.has(extension);

const sanitizeFilename = (value) =>
    Array.from(String(value), (character) => {
        const code = character.codePointAt(0);
        return UNSAFE_FILENAME_CHARS.has(character) || code < 32 || code === 127 ? '_' : character;
    }).join('');

const extensionFromMime = (contentType) => {
    const mime = String(contentType || '')
        .split(';')[0]
        .trim()
        .toLowerCase();
    return MIME_EXTENSIONS[mime] || '';
};

const extensionFromUrl = (url) => {
    try {
        const name = new URL(String(url || ''), 'http://localhost').pathname.split('/').pop() || '';
        const match = /\.([a-z0-9]{2,5})$/i.exec(name);
        const extension = match ? match[1].toLowerCase() : '';
        return isPlayableExtension(extension) ? extension : '';
    } catch {
        return '';
    }
};

export const mediaExtension = (contentType, url) => {
    const extension = extensionFromMime(contentType) || extensionFromUrl(url);
    return isPlayableExtension(extension) ? extension : 'mp3';
};

export const downloadName = (music, extension) => {
    const artist = music?.artists?.[0] || 'unknown';
    const title = sanitizeFilename(`${music?.name || 'music'} - ${artist}`).trim().slice(0, MAX_TITLE_LENGTH);
    return `${title || 'music'}.${isPlayableExtension(extension) ? extension : 'mp3'}`;
};
