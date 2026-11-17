const SOURCE_HOSTS = {
    netease: ['music.126.net', 'music.163.com'],
    qqmusic: ['qq.com', 'gtimg.cn'],
};

export function isAllowedSourceHost(platform, hostname) {
    return SOURCE_HOSTS[platform]?.some((host) => hostname === host || hostname.endsWith(`.${host}`)) || false;
}

let worker;
let nextId = 0;
const pending = new Map();

function getWorker() {
    if (worker) return worker;
    worker = new Worker(new URL('../workers/audioTagger.worker.js', import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => {
        const request = pending.get(data.id);
        if (!request) return;
        pending.delete(data.id);
        if (data.error) request.reject(new Error(data.error));
        else request.resolve({ buffer: data.buffer, coverEmbedded: data.coverEmbedded });
    };
    worker.onerror = () => {
        for (const request of pending.values()) request.reject(new Error('音频标签处理器启动失败'));
        pending.clear();
        worker?.terminate();
        worker = null;
    };
    return worker;
}

function requestWorker(audio, metadata, cover, coverType) {
    return new Promise((resolve, reject) => {
        const id = ++nextId;
        pending.set(id, { resolve, reject });
        getWorker().postMessage({ id, audio, metadata, cover, coverType }, [audio, ...(cover ? [cover] : [])]);
    });
}

function isAllowedImageUrl(platform, value) {
    try {
        const url = new URL(value);
        return url.protocol === 'https:' && isAllowedSourceHost(platform, url.hostname);
    } catch {
        return false;
    }
}

export async function tagAudioBlob(blob, music, fetchImpl = fetch) {
    const [audio, coverResult] = await Promise.all([
        blob.arrayBuffer(),
        music.coverUrl && isAllowedImageUrl(music.platform, music.coverUrl)
            ? fetchImpl(music.coverUrl)
                  .then(async (response) => {
                      if (!response.ok) throw new Error('封面下载失败');
                      const image = await response.blob();
                      if (!image.type.startsWith('image/')) throw new Error('专辑封面格式无效');
                      return { buffer: await image.arrayBuffer(), type: image.type };
                  })
                  .catch(() => null)
            : Promise.resolve(null),
    ]);
    const artist = music.artists?.filter(Boolean).join(' / ');
    const album = music.albumName?.trim();
    const result = await requestWorker(audio, {
        ...(artist ? { artist } : {}),
        ...(album ? { album } : {}),
    }, coverResult?.buffer || null, coverResult?.type || null);
    return {
        ...result,
        coverAttempted: Boolean(music.coverUrl && isAllowedImageUrl(music.platform, music.coverUrl)),
    };
}

export function isServerAudioUrl(value, origin) {
    try {
        const url = new URL(value, origin);
        return url.origin === origin;
    } catch {
        return true;
    }
}

export function stopAudioTagger() {
    worker?.terminate();
    worker = null;
    for (const request of pending.values()) request.reject(new Error('音频标签处理已取消'));
    pending.clear();
}
