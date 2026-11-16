import { THEMES } from '../constants/themes';
import { STORAGE_KEYS } from '../constants/storage';

export function resolveThemeId(stored, prefersDark = false) {
    if (THEMES.some((theme) => theme.id === stored)) return stored;
    return prefersDark ? 'dark-platinum' : 'light-original';
}

export function readThemePreference() {
    let stored = null;
    let prefersDark = false;
    try {
        stored = localStorage.getItem(STORAGE_KEYS.THEME);
    } catch {
        /* Theme selection remains usable when storage is unavailable. */
    }
    try {
        prefersDark = !!window.matchMedia?.('(prefers-color-scheme: dark)').matches;
    } catch {
        /* Older webviews can use the default light palette. */
    }
    return resolveThemeId(stored, prefersDark);
}

export function applyTheme(id, root = document.documentElement) {
    const theme = THEMES.find((item) => item.id === id);
    if (!theme) return;
    root.dataset.theme = theme.mode;
    root.dataset.palette = theme.id;
    root.style.colorScheme = theme.mode;
    for (const [name, hex] of Object.entries(theme.colors)) {
        const rgb = hex
            .slice(1)
            .match(/../g)
            .map((value) => parseInt(value, 16));
        root.style.setProperty(`--mp-${name}`, rgb.join(' '));
    }
    const rgb = theme.colors['ink-muted']
        .slice(1)
        .match(/../g)
        .map((value) => parseInt(value, 16));
    root.style.setProperty('--mp-grid-line', `rgba(${rgb.join(', ')}, ${theme.mode === 'dark' ? 0.035 : 0.03})`);
}
