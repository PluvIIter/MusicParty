import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadTaggedAudio } from '../../src/services/taggedDownload';

const music = {
    url: 'https://m10.music.126.net/song.mp3?token=private',
    name: 'Song',
    artists: ['Artist'],
    albumName: 'Album',
    coverUrl: 'https://p1.music.126.net/album.jpg',
    platform: 'netease',
};

const response = (body = new Blob(['audio'], { type: 'audio/mpeg' })) => ({
    ok: true,
    headers: { get: () => 'audio/mpeg' },
    blob: async () => body,
});

afterEach(() => vi.restoreAllMocks());

describe('downloadTaggedAudio', () => {
    it('tags readable audio and downloads a new browser-local file', async () => {
        const tagAudio = vi.fn().mockResolvedValue({ buffer: new Uint8Array([1, 2]).buffer, coverEmbedded: true });
        const startDownload = vi.fn();
        const objectUrl = vi.fn().mockReturnValue('blob:tagged');
        const result = await downloadTaggedAudio(music, {
            fetchImpl: vi.fn().mockResolvedValue(response()),
            tagAudio,
            startDownload,
            objectUrl,
            revokeObjectUrl: vi.fn(),
            origin: 'https://party.example.test',
            openFallbackWindow: vi.fn().mockReturnValue(null),
        });
        expect(tagAudio).toHaveBeenCalledWith(expect.any(Blob), music, expect.any(Function));
        expect(startDownload).toHaveBeenCalledWith('blob:tagged', 'Song - Artist.mp3');
        expect(objectUrl).toHaveBeenCalledOnce();
        expect(result.status).toBe('tagged');
    });

    it('falls back to opening the original direct URL when CORS blocks reading', async () => {
        const fetchImpl = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
        const fallbackWindow = { location: { replace: vi.fn() }, close: vi.fn() };
        const tagAudio = vi.fn();
        const result = await downloadTaggedAudio(music, {
            fetchImpl,
            tagAudio,
            openFallbackWindow: vi.fn().mockReturnValue(fallbackWindow),
            origin: 'https://party.example.test',
        });
        expect(fallbackWindow.location.replace).toHaveBeenCalledWith(music.url);
        expect(tagAudio).not.toHaveBeenCalled();
        expect(fetchImpl).toHaveBeenCalledTimes(1);
        expect(result.status).toBe('original-direct');
    });

    it('rejects non-HTTPS and unrelated CDN hosts without requesting them', async () => {
        const fetchImpl = vi.fn();
        const result = await downloadTaggedAudio({ ...music, url: 'http://cdn.example.test/song.mp3' }, {
            fetchImpl,
            openFallbackWindow: vi.fn(),
            origin: 'https://party.example.test',
        });
        expect(fetchImpl).not.toHaveBeenCalled();
        expect(result.status).toBe('invalid-url');
    });

    it('navigates directly when the browser blocks a fallback window', async () => {
        const openDirect = vi.fn();
        const result = await downloadTaggedAudio(music, {
            fetchImpl: vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
            openFallbackWindow: vi.fn().mockReturnValue(null),
            openDirect,
            origin: 'https://party.example.test',
        });
        expect(openDirect).toHaveBeenCalledWith(music.url);
        expect(result.status).toBe('original-direct');
    });

    it('never fetches server audio URLs', async () => {
        const fetchImpl = vi.fn();
        const result = await downloadTaggedAudio({ ...music, url: '/media/short-lived-ticket' }, {
            fetchImpl,
            origin: 'https://party.example.test',
        });
        expect(fetchImpl).not.toHaveBeenCalled();
        expect(result.status).toBe('server-audio-blocked');
    });

    it('downloads the fetched original Blob when the format cannot be tagged', async () => {
        const startDownload = vi.fn();
        const objectUrl = vi.fn().mockReturnValue('blob:original');
        const revokeObjectUrl = vi.fn();
        const fallbackWindow = { location: { replace: vi.fn() }, close: vi.fn() };
        const result = await downloadTaggedAudio(music, {
            fetchImpl: vi.fn().mockResolvedValue(response()),
            tagAudio: vi.fn().mockRejectedValue(new Error('Unsupported format')),
            startDownload,
            objectUrl,
            revokeObjectUrl,
            openFallbackWindow: vi.fn().mockReturnValue(fallbackWindow),
            origin: 'https://party.example.test',
        });
        expect(startDownload).toHaveBeenCalledWith('blob:original', 'Song - Artist.mp3');
        expect(fallbackWindow.location.replace).not.toHaveBeenCalled();
        expect(result.status).toBe('original-untagged');
    });

    it('downloads the fetched original Blob without tagging oversized audio', async () => {
        const startDownload = vi.fn();
        const originalBlob = { size: 100 * 1024 * 1024 + 1, type: 'audio/mpeg' };
        const tagAudio = vi.fn();
        const result = await downloadTaggedAudio(music, {
            fetchImpl: vi.fn().mockResolvedValue(response(originalBlob)),
            tagAudio,
            startDownload,
            objectUrl: vi.fn().mockReturnValue('blob:original'),
            revokeObjectUrl: vi.fn(),
            openFallbackWindow: vi.fn().mockReturnValue(null),
            origin: 'https://party.example.test',
        });
        expect(tagAudio).not.toHaveBeenCalled();
        expect(startDownload).toHaveBeenCalledWith('blob:original', 'Song - Artist.mp3');
        expect(result.status).toBe('original-too-large');
    });

    it('cancels a tagged fetch as soon as it exceeds the memory limit', async () => {
        const cancel = vi.fn();
        const read = vi
            .fn()
            .mockResolvedValueOnce({ done: false, value: new Uint8Array(16 * 1024 * 1024) })
            .mockResolvedValueOnce({ done: false, value: new Uint8Array(16 * 1024 * 1024) })
            .mockResolvedValueOnce({ done: false, value: new Uint8Array(1) });
        const fetchImpl = vi.fn().mockResolvedValue({
            ok: true,
            headers: { get: (key) => (key === 'content-type' ? 'audio/mpeg' : null) },
            body: { getReader: () => ({ read, cancel }) },
        });
        const fallbackWindow = { location: { replace: vi.fn() }, close: vi.fn() };
        const tagAudio = vi.fn();
        const result = await downloadTaggedAudio(music, {
            fetchImpl,
            tagAudio,
            openFallbackWindow: vi.fn().mockReturnValue(fallbackWindow),
            origin: 'https://party.example.test',
        });
        expect(cancel).toHaveBeenCalledOnce();
        expect(read).toHaveBeenCalledTimes(3);
        expect(tagAudio).not.toHaveBeenCalled();
        expect(fallbackWindow.location.replace).toHaveBeenCalledWith(music.url);
        expect(result.status).toBe('original-too-large');
    });

    it('writes tags without a cover when cover reading fails', async () => {
        const fetchImpl = vi.fn().mockResolvedValueOnce(response()).mockRejectedValueOnce(new TypeError('CORS'));
        const tagAudio = vi.fn().mockResolvedValue({ buffer: new Uint8Array([1]).buffer, coverEmbedded: false, coverAttempted: true });
        const result = await downloadTaggedAudio(music, {
            fetchImpl,
            tagAudio,
            startDownload: vi.fn(),
            objectUrl: vi.fn().mockReturnValue('blob:tagged'),
            revokeObjectUrl: vi.fn(),
            origin: 'https://party.example.test',
            openFallbackWindow: vi.fn().mockReturnValue(null),
        });
        expect(tagAudio).toHaveBeenCalledOnce();
        expect(result.status).toBe('tagged-cover-missing');
    });
});
