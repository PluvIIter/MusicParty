const assert = require('node:assert/strict');
const { serve, tone, chromium, launchOptions } = require('./support.cjs');

(async () => {
    const fixture = await serve();
    const browser = await chromium.launch(launchOptions());
    try {
        const page = await browser.newPage();
        const errors = [];
        page.on('pageerror', (error) => errors.push(error.message));
        await page.addInitScript(() => {
            for (const [key, value] of Object.entries({
                mp_username: 'SeekTester',
                mp_tutorial_done_v1: 'true',
                mp_pwa_hint_shown: 'true',
                mp_volume: '0',
            }))
                localStorage.setItem(key, value);
            window.player = () =>
                document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
        });
        await page.route('https://**', (route) => route.abort());
        await page.route('**/api/**', (route) =>
            route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(
                    route.request().url().endsWith('/api/auth/status') ? { isSetup: true, hasProtection: false } : {}
                ),
            })
        );
        const audio = tone();
        await page.route('**/tone.wav', (route) => {
            const match = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range || '');
            const start = match ? Number(match[1]) : 0;
            const end = match?.[2] ? Math.min(Number(match[2]), audio.length - 1) : audio.length - 1;
            return route.fulfill({
                status: match ? 206 : 200,
                headers: {
                    'Content-Type': 'audio/wav',
                    'Accept-Ranges': 'bytes',
                    ...(match ? { 'Content-Range': `bytes ${start}-${end}/${audio.length}` } : {}),
                },
                body: audio.subarray(start, end + 1),
            });
        });
        let hub;
        const requests = [];
        let nonce = 0,
            position = 10000,
            playbackId = 'first',
            paused = true;
        await page.routeWebSocket('**/ws', (socket) => {
            const subscriptions = new Map();
            let sequence = 0;
            const send = (destination, data) => {
                for (const [id, topic] of subscriptions) {
                    if (topic === destination)
                        socket.send(
                            `MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(data)}\0`
                        );
                }
            };
            hub = {
                state: () =>
                    send('/topic/player/compact', {
                        v: 1,
                        epoch: 'seek-fixture',
                        seq: ++sequence,
                        base: sequence,
                        full: true,
                        state: {
                            playbackId,
                            seekNonce: nonce,
                            isPaused: paused,
                            queue: [],
                            onlineUsers: [],
                            playMode: 'SEQUENTIAL',
                            nowPlaying: {
                                currentPosition: position,
                                music: {
                                    id: playbackId,
                                    platform: 'netease',
                                    name: 'Seek Fixture',
                                    artists: ['Fixture'],
                                    duration: 180000,
                                    coverUrl: '',
                                    url: `${fixture.origin}/tone.wav`,
                                },
                            },
                            config: { allowSeek: true, sources: {}, privateDj: { mode: 'OFF' } },
                        },
                    }),
                ack: (request, accepted, seekNonce) =>
                    send('/user/queue/player/seek', { ...request, accepted, seekNonce }),
            };
            socket.onMessage((raw) => {
                for (const frame of String(raw).split('\0')) {
                    const [head, body] = frame.split('\n\n');
                    const lines = head.trim().split('\n');
                    const command = lines.shift();
                    const headers = Object.fromEntries(
                        lines.map((line) => {
                            const index = line.indexOf(':');
                            return [line.slice(0, index), line.slice(index + 1)];
                        })
                    );
                    if (command === 'CONNECT')
                        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:fixture\n\n\0');
                    if (command === 'SUBSCRIBE') subscriptions.set(headers.id, headers.destination);
                    if (command === 'SEND' && headers.destination === '/app/player/compact-resync') hub.state();
                    if (command === 'SEND' && headers.destination === '/app/control/seek')
                        requests.push(JSON.parse(body));
                }
            });
        });
        await page.goto(fixture.origin);
        await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
        const slider = page.getByRole('slider', { name: '播放进度' });
        await slider.waitFor();
        await page.waitForFunction(() => document.querySelector('audio').readyState >= 2);
        const seek = async (ratio) => {
            const box = await slider.boundingBox();
            await page.mouse.click(box.x + box.width * ratio, box.y + box.height / 2);
        };
        const preview = () =>
            page.evaluate(() => ({
                progress: window.player().displayProgress,
                pending: !!window.player().seekPreview,
                confirmed: !!window.player().seekPreview?.confirmed,
                error: window.player().seekError,
            }));
        const initialMediaTime = await page.locator('audio').evaluate((element) => element.currentTime);
        await seek(0.5);
        assert.equal((await preview()).progress, 90000);
        await page.waitForTimeout(1500);
        hub.state();
        await page.waitForTimeout(200);
        assert.equal((await preview()).progress, 90000);
        assert.equal(await page.locator('audio').evaluate((element) => element.currentTime), initialMediaTime);
        assert.equal(await slider.getAttribute('aria-valuenow'), '90');
        assert.equal(await slider.getAttribute('aria-valuemax'), '180');
        const first = requests.at(-1);
        hub.ack(first, true, 1);
        await page.waitForTimeout(200);
        assert.equal((await preview()).confirmed, false);
        nonce = 1;
        position = 90000;
        hub.state();
        await page.waitForFunction(() => !window.player().seekPreview && window.player().localProgress > 89000);
        console.log(
            'PASS: delayed seek feedback stays at click target; only real state moves audio and settles preview'
        );

        await seek(0.25);
        const old = requests.at(-1);
        await page.waitForTimeout(600);
        await seek(0.75);
        const latest = requests.at(-1);
        hub.ack(old, true, 2);
        nonce = 2;
        position = 45000;
        hub.state();
        await page.waitForTimeout(300);
        assert.equal((await preview()).progress, 135000);
        hub.ack(latest, false, 2);
        await page.waitForFunction(() => !window.player().seekPreview);
        assert((await preview()).error.includes('未生效'));
        console.log('PASS: old response/state cannot clear newer preview; explicit rejection rolls back');

        await page.waitForTimeout(600);
        await seek(0.5);
        const expired = requests.at(-1);
        await page.waitForFunction(() => window.player().seekError.includes('超时'), null, { timeout: 10000 });
        hub.ack(expired, true, 3);
        await page.waitForTimeout(100);
        assert.equal((await preview()).pending, false);
        await seek(0.6);
        playbackId = 'second';
        position = 0;
        nonce = 0;
        hub.state();
        await page.waitForFunction(() => window.player().playbackId === 'second' && !window.player().seekPreview);
        console.log(
            'PASS: timeout requests resync and ignores late acknowledgment; track change clears pending feedback'
        );
        paused = false;
        position = 10000;
        hub.state();
        await page.waitForFunction(
            () => !document.querySelector('audio').paused && document.querySelector('audio').currentTime > 9
        );
        await page.waitForTimeout(600);
        await seek(0.5);
        const playingRequest = requests.at(-1);
        await page.waitForTimeout(1200);
        assert.equal((await preview()).progress, 90000);
        const playingTime = await page.locator('audio').evaluate((element) => element.currentTime);
        assert(playingTime > 9 && playingTime < 30);
        nonce = 1;
        position = 90000;
        hub.state();
        await page.waitForTimeout(300);
        assert.equal((await preview()).confirmed, false);
        hub.ack(playingRequest, true, 1);
        await page.waitForFunction(() => !window.player().seekPreview && window.player().localProgress > 89000);
        await page.waitForTimeout(600);
        await slider.press('ArrowRight');
        assert((await preview()).progress > 94000);
        assert((await preview()).progress < 97000);
        assert(Number(await slider.getAttribute('aria-valuenow')) >= 95);
        assert.deepEqual(errors, []);
        console.log(
            'PASS: playing audio stays authoritative during latency, state-before-ack settles, keyboard and ARIA use seconds'
        );
    } finally {
        await browser.close();
        await fixture.close();
    }
})().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
