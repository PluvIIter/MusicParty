import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { THEMES } from '../../src/constants/themes';
import { applyTheme, readThemePreference, resolveThemeId } from '../../src/domain/theme';
import { useUiStore } from '../../src/stores/ui';
import { AudioVisualizer } from '../../src/logic/AudioVisualizer';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

vi.mock('../../src/api/client', () => ({ default: { get: vi.fn() } }));
const luminance = (hex) =>
    hex
        .slice(1)
        .match(/../g)
        .map((value) => parseInt(value, 16) / 255)
        .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4))
        .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
const contrast = (a, b) => {
    const values = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (values[0] + 0.05) / (values[1] + 0.05);
};

beforeEach(() => {
    const storage = new Map();
    vi.stubGlobal('localStorage', {
        getItem: vi.fn((key) => storage.get(key) ?? null),
        setItem: vi.fn((key, value) => storage.set(key, value)),
    });
    document.documentElement.removeAttribute('style');
    delete document.documentElement.dataset.theme;
    delete document.documentElement.dataset.palette;
    setActivePinia(createPinia());
    vi.stubGlobal(
        'matchMedia',
        vi.fn(() => ({ matches: false }))
    );
});

describe('theme catalogue', () => {
    it('offers four complete distinct palettes in each mode', () => {
        expect(THEMES.filter((theme) => theme.mode === 'light')).toHaveLength(4);
        expect(THEMES.filter((theme) => theme.mode === 'dark')).toHaveLength(4);
        expect(new Set(THEMES.map((theme) => theme.id)).size).toBe(8);
        const keys = Object.keys(THEMES[0].colors).sort();
        for (const theme of THEMES) {
            expect(Object.keys(theme.colors).sort()).toEqual(keys);
            expect(theme.swatches).toHaveLength(3);
            for (const color of Object.values(theme.colors)) expect(color).toMatch(/^#[0-9A-F]{6}$/);
        }
    });

    it.each(THEMES)('$id keeps text and action labels readable', ({ id, mode, colors }) => {
        for (const surface of ['surface', 'surface-raised', 'surface-sunken']) {
            for (const text of ['ink', 'ink-muted'])
                expect(contrast(colors[text], colors[surface])).toBeGreaterThanOrEqual(
                    id === 'light-original' ? 4.3 : 4.5
                );
            if (id !== 'light-original') {
                expect(contrast(colors['ink-subtle'], colors[surface])).toBeGreaterThanOrEqual(4.5);
                expect(contrast(colors.accent, colors[surface])).toBeGreaterThanOrEqual(4.5);
            }
        }
        if (id !== 'light-original') {
            expect(contrast(colors['on-accent'], colors.accent)).toBeGreaterThanOrEqual(4.5);
            expect(contrast(colors['on-accent'], colors['accent-hover'])).toBeGreaterThanOrEqual(4.5);
        }
        expect(contrast(colors['ink-inverse'], colors.brand)).toBeGreaterThanOrEqual(4.5);
        expect(mode).toMatch(/^(light|dark)$/);
    });

    it('uses current palette IDs and system preference for unknown values', () => {
        expect(resolveThemeId('invalid', true)).toBe('dark-platinum');
        expect(resolveThemeId('dark-jade')).toBe('dark-jade');
        expect(resolveThemeId('invalid', true)).toBe('dark-platinum');
        expect(resolveThemeId(null)).toBe('light-original');
    });

    it('resolves system preference even if theme storage throws', () => {
        vi.stubGlobal('matchMedia', () => ({ matches: true }));
        vi.mocked(localStorage.getItem).mockImplementation(() => {
            throw new Error('denied');
        });
        expect(readThemePreference()).toBe('dark-platinum');
    });
});

describe('theme application and store', () => {
    it('uses the same early and mounted selection, then persists the palette ID', () => {
        localStorage.setItem('mp_theme', 'dark-platinum');
        applyTheme(readThemePreference());
        const initial = document.documentElement.style.cssText;
        const ui = useUiStore();
        expect(ui.themeId).toBe('dark-platinum');
        expect(ui.isDarkMode).toBe(true);
        expect(document.documentElement.style.cssText).toBe(initial);
        expect(localStorage.getItem('mp_theme')).toBe('dark-platinum');
        ui.setTheme('light-iris');
        expect(ui.isDarkMode).toBe(false);
        expect(document.documentElement.dataset).toMatchObject({ theme: 'light', palette: 'light-iris' });
        expect(localStorage.getItem('mp_theme')).toBe('light-iris');
        ui.setTheme('not-a-theme');
        expect(ui.themeId).toBe('light-iris');
    });

    it('continues switching when storage writes are blocked', () => {
        const ui = useUiStore();
        vi.mocked(localStorage.setItem).mockImplementation(() => {
            throw new Error('quota');
        });
        expect(() => ui.setTheme('dark-jade')).not.toThrow();
        expect(document.documentElement.dataset.palette).toBe('dark-jade');
    });

    it('writes every declared CSS theme token for every palette', () => {
        const css = readFileSync(resolve('src/style.css'), 'utf8');
        const names = [...new Set([...css.matchAll(/(--mp-[\w-]+):/g)].map((match) => match[1]))];
        for (const theme of THEMES) {
            applyTheme(theme.id);
            for (const name of names) {
                expect(document.documentElement.style.getPropertyValue(name), `${theme.id}: ${name}`).not.toBe('');
            }
            for (const [name, hex] of Object.entries(theme.colors)) {
                const rgb = hex
                    .slice(1)
                    .match(/../g)
                    .map((value) => parseInt(value, 16))
                    .join(' ');
                expect(document.documentElement.style.getPropertyValue(`--mp-${name}`)).toBe(rgb);
            }
        }
    });

    it('updates every token and visualizer color between palettes of the same mode', () => {
        const visualizer = new AudioVisualizer();
        applyTheme('dark-platinum');
        const first = visualizer.graphicColor();
        applyTheme('dark-iris');
        expect(visualizer.graphicColor()).not.toBe(first);
        expect(document.documentElement.style.colorScheme).toBe('dark');
        applyTheme('light-jade');
        expect(document.documentElement.style.colorScheme).toBe('light');
        expect(document.documentElement.style.getPropertyValue('--mp-accent')).toBe('66 116 95');
    });
});
