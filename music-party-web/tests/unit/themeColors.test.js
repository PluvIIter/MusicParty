import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(resolve('src/style.css'), 'utf8');
const tokens = (selector) => {
    const block = css.slice(css.indexOf(selector)).split('}')[0];
    return Object.fromEntries(
        [...block.matchAll(/--mp-([\w-]+):\s*(\d+)\s+(\d+)\s+(\d+)\s*;/g)].map(([, name, ...rgb]) => [
            name,
            rgb.map(Number),
        ])
    );
};
const luminance = (rgb) =>
    rgb
        .map((value) => value / 255)
        .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4))
        .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
const contrast = (foreground, background) => {
    const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
    return (values[0] + 0.05) / (values[1] + 0.05);
};

const light = tokens(':root {');
const dark = tokens("[data-theme='dark'] {");

describe('theme colors', () => {
    it('keeps the light palette and orange identity unchanged', () => {
        expect(light.surface).toEqual([249, 250, 251]);
        expect(light['surface-raised']).toEqual([255, 255, 255]);
        expect(light.ink).toEqual([17, 24, 39]);
        expect(light.accent).toEqual([249, 115, 22]);
        expect(light['accent-hover']).toEqual([234, 88, 12]);
        expect(light['on-accent']).toEqual(light['ink-inverse']);
        expect(dark.accent[0]).toBeGreaterThan(dark.accent[1]);
        expect(dark.accent[1]).toBeGreaterThan(dark.accent[2]);
    });

    it.each(['ink', 'ink-muted', 'ink-subtle', 'accent'])('%s remains readable on dark surfaces', (name) => {
        for (const surface of ['surface', 'surface-raised', 'surface-sunken', 'brand']) {
            expect(contrast(dark[name], dark[surface]), `${name} on ${surface}`).toBeGreaterThanOrEqual(4.5);
        }
    });

    it('keeps dark action labels readable at rest and on hover', () => {
        expect(contrast(dark['on-accent'], dark.accent)).toBeGreaterThanOrEqual(4.5);
        expect(contrast(dark['on-accent'], dark['accent-hover'])).toBeGreaterThanOrEqual(4.5);
    });

    it('uses luminance rather than blue saturation to separate dark surfaces', () => {
        expect(luminance(dark['surface-sunken'])).toBeLessThan(luminance(dark.surface));
        expect(luminance(dark.surface)).toBeLessThan(luminance(dark['surface-raised']));
        for (const name of ['surface', 'surface-raised', 'surface-sunken']) {
            expect(Math.max(...dark[name]) - Math.min(...dark[name])).toBeLessThanOrEqual(8);
        }
    });
});
