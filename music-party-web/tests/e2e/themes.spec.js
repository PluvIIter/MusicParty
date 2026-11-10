import { test, expect } from '@playwright/test';
import { THEMES } from '../../src/constants/themes';

async function openRoom(page, preference) {
    await page.addInitScript((preference) => {
        if (preference !== undefined) localStorage.setItem('mp_theme', preference);
        localStorage.setItem('mp_tutorial_done_v1', 'true');
        localStorage.setItem('mp_pwa_hint_shown', 'true');
        localStorage.setItem('mp_auto_lite_mode', 'false');
    }, preference);
    await page.route('https://**', (route) => route.abort());
    await page.route('**/api/**', (route) => {
        const path = new URL(route.request().url()).pathname;
        const body =
            path === '/api/auth/status'
                ? { isSetup: true, hasProtection: false }
                : path === '/api/config'
                  ? { authorName: 'Fixture', backWords: 'MUSIC PARTY' }
                  : '';
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    });
    await page.routeWebSocket('**/ws', (socket) => {
        socket.onMessage((raw) => {
            if (String(raw).startsWith('CONNECT'))
                socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:theme-fixture\n\n\0');
        });
    });
    await page.goto('/');
    if (preference === 'dark') await expect(page.locator('html')).toHaveAttribute('data-palette', 'dark-platinum');
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
}

for (const width of [1280, 320]) {
    test(`all eight themes apply, remain usable and persist at ${width}px`, async ({ page }, testInfo) => {
        await page.setViewportSize({ width, height: 900 });
        await openRoom(page);
        const trigger = page.getByRole('button', { name: '切换主题', exact: true });
        for (const button of await page.locator('header button').all()) {
            if (!(await button.isVisible())) continue;
            const box = await button.boundingBox();
            expect(box.x).toBeGreaterThanOrEqual(0);
            expect(box.x + box.width).toBeLessThanOrEqual(width);
        }
        await trigger.click();
        const panel = page.getByRole('region', { name: '选择主题', exact: true });
        await expect(panel.getByRole('button')).toHaveCount(8);
        for (const theme of THEMES) {
            const option = panel.getByRole('button', { name: theme.name, exact: true });
            await option.click();
            await expect(option).toHaveAttribute('aria-pressed', 'true');
            await expect(panel).toBeVisible();
            expect(await page.evaluate(() => document.documentElement.dataset.palette)).toBe(theme.id);
            expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe(theme.mode);
            const rgb = theme.colors.accent
                .slice(1)
                .match(/../g)
                .map((v) => parseInt(v, 16));
            await expect
                .poll(() => page.locator('#tutorial-search').evaluate((el) => getComputedStyle(el).backgroundColor))
                .toBe(`rgb(${rgb.join(', ')})`);
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
            const box = await panel.boundingBox();
            expect(box.x).toBeGreaterThanOrEqual(0);
            expect(box.x + box.width).toBeLessThanOrEqual(width);
            const cover = page.locator('#tutorial-like');
            const border = await cover.evaluate((el) => getComputedStyle(el).borderTopColor);
            if (theme.mode === 'dark') expect(border).not.toBe('rgb(255, 255, 255)');
            await page.screenshot({ path: testInfo.outputPath(`${theme.id}-${width}.png`), animations: 'disabled' });
        }
        await page.keyboard.press('Escape');
        await expect(panel).toHaveCount(0);
        await expect(trigger).toBeFocused();
        await page.reload();
        expect(await page.evaluate(() => document.documentElement.dataset.palette)).toBe('dark-jade');
        await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
        await trigger.click();
        await expect(panel.getByRole('button', { name: '海玻璃青', exact: true })).toBeFocused();
        await page.getByRole('button', { name: '精简模式', exact: true }).focus();
        await expect(panel).toHaveCount(0);
        await trigger.click();
        await page.keyboard.press('Tab');
        await expect(panel).toHaveCount(0);
        await expect(page.getByRole('button', { name: '精简模式', exact: true })).toBeFocused();
        await trigger.click();
        await page.locator('#tutorial-search').click();
        await expect(panel).toHaveCount(0);
        await expect(trigger).toHaveAttribute('aria-expanded', 'false');
    });
}

test('legacy dark setting migrates before connect and selection supports keyboard', async ({ page }) => {
    await openRoom(page, 'dark');
    await expect(page.locator('html')).toHaveAttribute('data-palette', 'dark-platinum');
    const trigger = page.getByRole('button', { name: '切换主题', exact: true });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const panel = page.getByRole('region', { name: '选择主题', exact: true });
    await expect(panel.getByRole('button', { name: '铂银', exact: true })).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Space');
    await expect(panel.getByRole('button', { name: '鸢尾雾紫', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await page.keyboard.press('Escape');
    await expect(trigger).toBeFocused();
});
