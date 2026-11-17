import { mediaExtension, downloadName } from './musicDownload';
import { isAllowedSourceHost, isServerAudioUrl, tagAudioBlob } from './audioTagging';

const MAX_TAGGED_DOWNLOAD_BYTES = 32 * 1024 * 1024;

export async function downloadTaggedAudio(music, {
    fetchImpl = fetch,
    origin = window.location.origin,
    tagAudio = tagAudioBlob,
    startDownload = (url, filename) => {
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        document.body.appendChild(link);
        link.click();
        link.remove();
    },
    objectUrl = (blob) => URL.createObjectURL(blob),
    revokeObjectUrl = (url) => URL.revokeObjectURL(url),
    openFallbackWindow = () => window.open('about:blank', '_blank'),
    openDirect = (url) => window.location.assign(url),
} = {}) {
    if (!['netease', 'qqmusic'].includes(music.platform)) return { status: 'unsupported-source' };
    if (isServerAudioUrl(music.url, origin)) return { status: 'server-audio-blocked' };
    const directUrl = new URL(music.url, origin);
    if (directUrl.protocol !== 'https:') return { status: 'invalid-url' };
    if (directUrl.username || directUrl.password) return { status: 'invalid-url' };
    if (!isAllowedSourceHost(music.platform, directUrl.hostname)) return { status: 'invalid-url' };
    const resolvedUrl = directUrl.href;
    const fallbackWindow = openFallbackWindow();
    const closeFallbackWindow = () => fallbackWindow?.close();
    const downloadOriginalDirect = () => {
        if (fallbackWindow) fallbackWindow.location.replace(resolvedUrl);
        else openDirect(resolvedUrl);
    };
    const downloadOriginalBlob = (blob, extension) => {
        const url = objectUrl(blob);
        startDownload(url, downloadName(music, extension));
        setTimeout(() => revokeObjectUrl(url), 1000);
    };

    let response;
    try {
        response = await fetchImpl(resolvedUrl);
    } catch {
        downloadOriginalDirect();
        return { status: 'original-direct' };
    }

    if (!response.ok) {
        downloadOriginalDirect();
        return { status: 'original-direct' };
    }

    const extension = mediaExtension(response.headers?.get?.('content-type'), music.url);
    const contentLength = Number(response.headers?.get?.('content-length'));
    if (Number.isFinite(contentLength) && contentLength > MAX_TAGGED_DOWNLOAD_BYTES) {
        downloadOriginalDirect();
        return { status: 'original-too-large' };
    }

    let sourceBlob;
    try {
        if (response.body?.getReader) {
            const reader = response.body.getReader();
            const chunks = [];
            let total = 0;
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                total += value.byteLength;
                if (total > MAX_TAGGED_DOWNLOAD_BYTES) {
                    await reader.cancel();
                    closeFallbackWindow();
                    downloadOriginalDirect();
                    return { status: 'original-too-large' };
                }
                chunks.push(value);
            }
            sourceBlob = new Blob(chunks, { type: response.headers?.get?.('content-type') || '' });
        } else {
            sourceBlob = await response.blob();
        }
    } catch {
        downloadOriginalDirect();
        return { status: 'original-direct' };
    }
    if (sourceBlob.size > MAX_TAGGED_DOWNLOAD_BYTES) {
        closeFallbackWindow();
        downloadOriginalBlob(sourceBlob, extension);
        return { status: 'original-too-large' };
    }
    let tagged;
    try {
        tagged = await tagAudio(sourceBlob, music, fetchImpl);
    } catch {
        closeFallbackWindow();
        downloadOriginalBlob(sourceBlob, extension);
        return { status: 'original-untagged' };
    }

    closeFallbackWindow();
    const outputUrl = objectUrl(new Blob([tagged.buffer], { type: sourceBlob.type || `audio/${extension}` }));
    try {
        startDownload(outputUrl, downloadName(music, extension));
    } finally {
        setTimeout(() => revokeObjectUrl(outputUrl), 1000);
    }
    return { status: tagged.coverAttempted && !tagged.coverEmbedded ? 'tagged-cover-missing' : 'tagged' };
}
