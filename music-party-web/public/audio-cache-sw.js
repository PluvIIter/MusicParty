/* Music-only, device-local range cache. Never cache application pages, APIs or credentials. */
const DATA_CACHE = 'mp-audio-chunks-v1';
const META_CACHE = 'mp-audio-metadata-v1';
const ROUTE = '/__mp_audio_cache/v1/';
const STORE = '/__mp_audio_store/v1/';
const CHUNK_SIZE = 512 * 1024;
const inFlight = new Map();
let epoch = 0;
let writes = Promise.resolve();

self.addEventListener('install', (event) => event.waitUntil(self.skipWaiting()));
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

const serial = (task) => {
    const result = writes.then(task, task);
    writes = result.catch(() => {});
    return result;
};
const chunkUrl = (key, index) => new URL(`${STORE}${key}/${index}`, self.location.origin).href;
const metaUrl = (key) => new URL(`${STORE}${key}/meta`, self.location.origin).href;

async function readMeta(key) {
    try {
        const response = await (await caches.open(META_CACHE)).match(metaUrl(key));
        return response ? await response.json() : null;
    } catch {
        return null;
    }
}

async function metadata() {
    const cache = await caches.open(META_CACHE);
    const keys = await cache.keys();
    const values = await Promise.all(
        keys.map(async (key) => {
            try {
                return await (await cache.match(key)).json();
            } catch {
                return null;
            }
        })
    );
    return values.filter(Boolean);
}

async function removeFile(item) {
    const data = await caches.open(DATA_CACHE);
    for (const index of Object.keys(item.chunks)) await data.delete(chunkUrl(item.key, index));
    await (await caches.open(META_CACHE)).delete(metaUrl(item.key));
}

async function evictOldFiles(exclude, minimumBytes) {
    const entries = (await metadata()).filter((item) => item.key !== exclude).sort((a, b) => a.usedAt - b.usedAt);
    let freed = 0;
    for (const item of entries) {
        await removeFile(item);
        freed += Object.values(item.chunks).reduce((sum, size) => sum + size, 0);
        if (freed >= minimumBytes) break;
    }
}

async function persistChunk(key, index, block, expectedEpoch) {
    return serial(async () => {
        if (epoch !== expectedEpoch) return;
        const data = await caches.open(DATA_CACHE);
        const response = () =>
            new Response(block.bytes, {
                headers: {
                    'Content-Type': block.type,
                    'Content-Length': String(block.bytes.byteLength),
                    'X-MP-Total': String(block.total),
                    'X-MP-Start': String(block.start),
                },
            });
        try {
            await data.put(chunkUrl(key, index), response());
        } catch (error) {
            if (error.name !== 'QuotaExceededError') return;
            // No app-imposed size cap. Reclaim older songs only when the browser quota is reached.
            await evictOldFiles(key, Math.max(CHUNK_SIZE * 8, block.bytes.byteLength));
            try {
                await data.put(chunkUrl(key, index), response());
            } catch {
                return;
            }
        }
        const previous = await readMeta(key);
        const item =
            previous && previous.total === block.total
                ? previous
                : { key, total: block.total, type: block.type, chunks: {} };
        item.usedAt = Date.now();
        item.chunks[index] = block.bytes.byteLength;
        try {
            await (
                await caches.open(META_CACHE)
            ).put(
                metaUrl(key),
                new Response(JSON.stringify(item), { headers: { 'Content-Type': 'application/json' } })
            );
        } catch (error) {
            if (error.name === 'QuotaExceededError') {
                await evictOldFiles(key, CHUNK_SIZE * 8);
                try {
                    await (
                        await caches.open(META_CACHE)
                    ).put(
                        metaUrl(key),
                        new Response(JSON.stringify(item), { headers: { 'Content-Type': 'application/json' } })
                    );
                    return;
                } catch {
                    /* Keep the cache index consistent if storage remains unavailable. */
                }
            }
            // Do not leave unindexed chunks that cannot be counted or evicted.
            await data.delete(chunkUrl(key, index));
        }
    }).catch(() => {}); // Storage failures must not stop music playback.
}

function touch(key) {
    serial(async () => {
        const item = await readMeta(key);
        if (!item || Date.now() - item.usedAt < 30000) return;
        item.usedAt = Date.now();
        await (
            await caches.open(META_CACHE)
        ).put(metaUrl(key), new Response(JSON.stringify(item), { headers: { 'Content-Type': 'application/json' } }));
    }).catch(() => {});
}

async function cachedChunk(key, index) {
    try {
        const cache = await caches.open(DATA_CACHE);
        const response = await cache.match(chunkUrl(key, index));
        if (!response) return null;
        const bytes = await response.arrayBuffer();
        const total = Number(response.headers.get('X-MP-Total'));
        const start = Number(response.headers.get('X-MP-Start'));
        if (
            !Number.isSafeInteger(total) ||
            total <= 0 ||
            start !== index * CHUNK_SIZE ||
            bytes.byteLength !== Math.min(CHUNK_SIZE, total - start)
        ) {
            await cache.delete(chunkUrl(key, index));
            return null;
        }
        touch(key);
        return { bytes, total, start, type: response.headers.get('Content-Type') || 'application/octet-stream' };
    } catch {
        return null;
    }
}

async function downloadBlock(source, index, signal, key, expectedEpoch) {
    const start = index * CHUNK_SIZE;
    const response = await fetch(source, {
        mode: 'cors',
        credentials: 'omit',
        referrerPolicy: 'no-referrer',
        redirect: 'error',
        headers: { Range: `bytes=${start}-${start + CHUNK_SIZE - 1}` },
        signal,
    });
    const range = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(response.headers.get('Content-Range') || '');
    if (response.status !== 206 || !range || Number(range[1]) !== start) {
        await response.body?.cancel();
        throw new Error('Audio range unavailable');
    }
    const end = Number(range[2]),
        total = Number(range[3]);
    if (!Number.isSafeInteger(total) || total <= start || end !== Math.min(start + CHUNK_SIZE, total) - 1) {
        await response.body?.cancel();
        throw new Error('Invalid audio range');
    }
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength !== end - start + 1) throw new Error('Incomplete audio range');
    const block = { bytes, total, start, type: response.headers.get('Content-Type') || 'application/octet-stream' };
    await persistChunk(key, index, block, expectedEpoch);
    return block;
}

async function getChunk(key, index, source, signal) {
    const cached = await cachedChunk(key, index);
    if (signal.aborted) throw new DOMException('Cancelled', 'AbortError');
    if (cached) return cached;
    const flightKey = `${epoch}:${key}:${index}`;
    let flight = inFlight.get(flightKey);
    if (!flight) {
        const controller = new AbortController();
        flight = { controller, users: new Set(), done: false };
        flight.promise = downloadBlock(source, index, controller.signal, key, epoch).finally(() => {
            flight.done = true;
            inFlight.delete(flightKey);
        });
        inFlight.set(flightKey, flight);
    }
    const user = {};
    flight.users.add(user);
    let abort;
    const cancelled = new Promise((resolve, reject) => {
        abort = () => reject(new DOMException('Cancelled', 'AbortError'));
        signal.addEventListener('abort', abort, { once: true });
    });
    try {
        return await Promise.race([flight.promise, cancelled]);
    } finally {
        signal.removeEventListener('abort', abort);
        flight.users.delete(user);
        if (!flight.done && flight.users.size === 0) flight.controller.abort();
    }
}

async function fileIdentity(url) {
    const id = decodeURIComponent(url.pathname.slice(ROUTE.length));
    const source = new URL(url.searchParams.get('source'));
    const name = source.pathname.split('/').pop();
    if (
        !/^[\w-]{1,100}$/.test(id) ||
        source.protocol !== 'https:' ||
        source.username ||
        source.password ||
        source.port ||
        !(source.hostname.endsWith('.music.126.net') || source.hostname.endsWith('.music.163.com')) ||
        !/^[\w.-]{8,160}\.(flac|mp3|m4a|aac|wav|ogg)$/i.test(name)
    )
        throw new Error('Unsupported audio source');
    // CDN directory, host and signing query may rotate. The file name identifies encoded content/quality.
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`netease:${id}:${name}`));
    const key = Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('');
    return { key, source: source.href };
}

async function audioResponse(request) {
    let file;
    try {
        file = await fileIdentity(new URL(request.url));
    } catch {
        return new Response(null, { status: 400 });
    }
    const header = request.headers.get('Range');
    const range = header === null ? null : /^bytes=(\d*)-(\d*)$/.exec(header);
    if (header !== null && (!range || (!range[1] && !range[2]))) return new Response(null, { status: 400 });
    if (range && [range[1], range[2]].filter(Boolean).some((value) => !Number.isSafeInteger(Number(value))))
        return new Response(null, { status: 400 });
    const controller = new AbortController();
    const cancel = () => controller.abort();
    request.signal.addEventListener('abort', cancel, { once: true });
    if (request.signal.aborted) controller.abort();
    let first, start, end;
    try {
        const meta = await readMeta(file.key);
        let total = meta?.total;
        start = range?.[1] ? Number(range[1]) : 0;
        if (!total) {
            first = await getChunk(file.key, 0, file.source, controller.signal);
            total = first.total;
        }
        if (range && !range[1]) start = Math.max(0, total - Number(range[2]));
        end = range?.[1] && range[2] ? Math.min(Number(range[2]), total - 1) : total - 1;
        if (start >= total || start > end || (range && !range[1] && Number(range[2]) === 0)) {
            request.signal.removeEventListener('abort', cancel);
            return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${total}` } });
        }
        const index = Math.floor(start / CHUNK_SIZE);
        if (!first || first.start !== index * CHUNK_SIZE)
            first = await getChunk(file.key, index, file.source, controller.signal);
        if (first.total !== total) throw new Error('Audio content changed');
        const headers = {
            'Content-Type': first.type,
            'Content-Length': String(end - start + 1),
            'Accept-Ranges': 'bytes',
            'Cache-Control': 'no-store',
        };
        if (range) headers['Content-Range'] = `bytes ${start}-${end}/${total}`;
        let position = start;
        const stream = new ReadableStream(
            {
                async pull(streamController) {
                    try {
                        if (position > end) {
                            streamController.close();
                            request.signal.removeEventListener('abort', cancel);
                            return;
                        }
                        const next = Math.floor(position / CHUNK_SIZE);
                        const block =
                            first?.start === next * CHUNK_SIZE
                                ? first
                                : await getChunk(file.key, next, file.source, controller.signal);
                        first = null;
                        if (controller.signal.aborted) return;
                        if (block.total !== total) throw new Error('Audio content changed');
                        const offset = position - block.start;
                        const count = Math.min(block.bytes.byteLength - offset, end - position + 1);
                        streamController.enqueue(new Uint8Array(block.bytes, offset, count));
                        position += count;
                    } catch (error) {
                        request.signal.removeEventListener('abort', cancel);
                        streamController.error(error);
                    }
                },
                cancel() {
                    controller.abort();
                    request.signal.removeEventListener('abort', cancel);
                },
            },
            { highWaterMark: 0 }
        );
        return new Response(stream, { status: range ? 206 : 200, headers });
    } catch {
        request.signal.removeEventListener('abort', cancel);
        // Unsupported CORS/range/storage must retain the original native playback path.
        return Response.redirect(file.source, 307);
    }
}

self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);
    if (url.origin === self.location.origin && url.pathname.startsWith(ROUTE) && event.request.method === 'GET') {
        event.respondWith(audioResponse(event.request));
    }
});

self.addEventListener('message', (event) => {
    if (event.data?.type !== 'mp-audio-cache' || !event.ports[0]) return;
    event.waitUntil(
        (async () => {
            try {
                if (event.data.action === 'clear') {
                    epoch++;
                    await serial(async () => {
                        await caches.delete(DATA_CACHE);
                        await caches.delete(META_CACHE);
                    });
                }
                await writes;
                const entries = await metadata();
                event.ports[0].postMessage({
                    available: true,
                    files: entries.length,
                    bytes: entries.reduce(
                        (sum, item) => sum + Object.values(item.chunks).reduce((n, size) => n + size, 0),
                        0
                    ),
                });
            } catch {
                event.ports[0].postMessage({ available: false, error: '浏览器暂时无法保存音乐缓存' });
            }
        })()
    );
});
