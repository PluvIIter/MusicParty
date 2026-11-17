import { afterEach, describe, expect, it, vi } from 'vitest';
import { tagAudioBlob, stopAudioTagger } from '../../src/services/audioTagging';

const originalWorker = globalThis.Worker;

afterEach(() => {
    stopAudioTagger();
    if (originalWorker === undefined) delete globalThis.Worker;
    else globalThis.Worker = originalWorker;
    vi.restoreAllMocks();
});

describe('tagAudioBlob', () => {
    it('fetches album art only from an HTTPS host owned by the source', async () => {
        const postMessage = vi.fn();
        class FakeWorker {
            constructor() { globalThis.lastTagWorker = this; }
            postMessage(message) {
                postMessage(message);
                queueMicrotask(() => this.onmessage({ data: { id: message.id, buffer: new Uint8Array([1]).buffer, coverEmbedded: false } }));
            }
            terminate() {}
        }
        globalThis.Worker = FakeWorker;
        const fetchImpl = vi.fn().mockResolvedValue({
            ok: true,
            blob: async () => new Blob(['cover'], { type: 'image/jpeg' }),
        });
        const result = await tagAudioBlob({ arrayBuffer: async () => new Uint8Array([1, 2]).buffer }, {
            platform: 'netease', artists: ['Singer'], albumName: 'Record', coverUrl: 'https://p1.music.126.net/cover.jpg',
        }, fetchImpl);
        expect(fetchImpl).toHaveBeenCalledWith('https://p1.music.126.net/cover.jpg');
        expect(postMessage.mock.calls[0][0].metadata).toEqual({ artist: 'Singer', album: 'Record' });
        expect(result.coverAttempted).toBe(true);
        delete globalThis.lastTagWorker;
    });

    it('does not request cover art from an unrelated host', async () => {
        const postMessage = vi.fn();
        class FakeWorker {
            constructor() { globalThis.lastTagWorker = this; }
            postMessage(message) {
                postMessage(message);
                queueMicrotask(() => this.onmessage({ data: { id: message.id, buffer: new Uint8Array([1]).buffer, coverEmbedded: false } }));
            }
            terminate() {}
        }
        globalThis.Worker = FakeWorker;
        const fetchImpl = vi.fn();
        const result = await tagAudioBlob({ arrayBuffer: async () => new Uint8Array([1, 2]).buffer }, {
            platform: 'netease', artists: ['Singer'], albumName: 'Record', coverUrl: 'https://unrelated.example/cover.jpg',
        }, fetchImpl);
        expect(fetchImpl).not.toHaveBeenCalled();
        expect(postMessage.mock.calls[0][0].cover).toBeNull();
        expect(result.coverAttempted).toBe(false);
        delete globalThis.lastTagWorker;
    });
});
