const assert = require('node:assert/strict');
const { chromium, serve, launchOptions } = require('./support.cjs');

(async () => {
    const server = await serve();
    const browser = await chromium.launch(launchOptions());
    try {
        for (const width of [320, 393, 767, 768, 1280]) {
            const page = await browser.newPage({ viewport: { width, height: 852 } });
            await page.addInitScript(() => {
                localStorage.setItem('mp_tutorial_done_v1', 'true');
                localStorage.setItem('mp_pwa_hint_shown', 'true');
                localStorage.setItem('mp_auto_lite_mode', 'false');
                localStorage.setItem('mp_username', 'Layout tester');
            });
            await page.route('**/api/**', (route) =>
                route.fulfill({
                    json: route.request().url().endsWith('/auth/status') ? { isSetup: true, hasProtection: false } : {},
                })
            );
            await page.routeWebSocket('**/ws', (socket) => socket.onMessage(() => {}));
            await page.goto(server.origin);
            await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
            await page.evaluate(() => {
                const stores = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s;
                Object.assign(stores.get('player'), {
                    connected: true,
                    playbackId: 'layout',
                    isPaused: true,
                    config: { allowSeek: true },
                    nowPlaying: {
                        music: {
                            id: 'layout',
                            platform: 'netease',
                            kind: 'SONG',
                            name: '一首非常非常长的歌曲名称 '.repeat(8),
                            artists: ['测试歌手'],
                            albumName: '上五楼的快活',
                            duration: 180000,
                        },
                    },
                });
                stores.get('user').showNameModal = false;
            });
            const geometry = await page.evaluate(() => {
                const cover = document.querySelector('#tutorial-source');
                const root = cover.parentElement;
                const title = root.querySelector('h2');
                const album = title.parentElement.querySelector('p:last-child');
                const progress = root.querySelector('[role="slider"]');
                const mobile = document.querySelector('#tutorial-pause-mobile').parentElement;
                const rect = (element) => {
                    const { x, y, width, height, bottom, right } = element.getBoundingClientRect();
                    return { x, y, width, height, bottom, right };
                };
                return {
                    root: rect(root),
                    cover: rect(cover),
                    title: rect(title),
                    album: rect(album),
                    progress: rect(progress),
                    mobile: rect(mobile),
                    viewport: window.innerWidth,
                    scrollWidth: document.documentElement.scrollWidth,
                };
            });
            assert.ok(geometry.title.y >= geometry.root.y, `title stays inside player at ${width}`);
            assert.ok(geometry.title.right <= width, `long title stays inside viewport at ${width}`);
            assert.ok(geometry.scrollWidth <= width, `no horizontal overflow at ${width}`);
            assert.ok(geometry.progress.bottom <= geometry.root.bottom, `progress stays inside player at ${width}`);
            if (width < 768) {
                assert.ok(geometry.mobile.y >= geometry.album.bottom, `controls follow metadata at ${width}`);
                assert.ok(geometry.mobile.bottom <= geometry.progress.y, `controls precede progress at ${width}`);
                assert.ok(geometry.progress.width > width - 40, `progress spans the player at ${width}`);
            }
            for (const albumName of [null, '', '   ', '另一张专辑']) {
                await page.evaluate((value) => {
                    document
                        .querySelector('#app')
                        .__vue_app__.config.globalProperties.$pinia._s.get('player').nowPlaying.music.albumName = value;
                }, albumName);
                const current = await page.locator('#tutorial-source').evaluate((cover) => {
                    const root = cover.parentElement;
                    return {
                        height: root.getBoundingClientRect().height,
                        titleY: root.querySelector('h2').getBoundingClientRect().y,
                        progressY: root.querySelector('[role="slider"]').getBoundingClientRect().y,
                    };
                });
                assert.equal(current.height, geometry.root.height, `stable player height at ${width}`);
                assert.equal(current.titleY, geometry.title.y, `stable title position at ${width}`);
                assert.equal(current.progressY, geometry.progress.y, `stable progress position at ${width}`);
            }
            await page.close();
            console.log(`Player layout verified at ${width}px`);
        }
    } finally {
        await browser.close();
        await server.close();
    }
})().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
