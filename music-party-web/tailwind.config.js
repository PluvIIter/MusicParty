/** @type {import('tailwindcss').Config} */
export default {
    content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
    // 主题由 <html data-theme="dark"> 决定（ui store 写入）。
    // 语义令牌已覆盖绝大多数场景，这里保留 dark: 变体用于个别例外。
    darkMode: ['class', '[data-theme="dark"]'],
    theme: {
        extend: {
            colors: {
                // 语义令牌：具体值在 src/style.css 的 :root / [data-theme='dark'] 里定义。
                // 用 <alpha-value> 让 bg-surface/50 这类透明度变体照常工作。
                surface: {
                    DEFAULT: 'rgb(var(--mp-surface) / <alpha-value>)',
                    raised: 'rgb(var(--mp-surface-raised) / <alpha-value>)',
                    sunken: 'rgb(var(--mp-surface-sunken) / <alpha-value>)',
                },
                ink: {
                    DEFAULT: 'rgb(var(--mp-ink) / <alpha-value>)',
                    muted: 'rgb(var(--mp-ink-muted) / <alpha-value>)',
                    subtle: 'rgb(var(--mp-ink-subtle) / <alpha-value>)',
                    inverse: 'rgb(var(--mp-ink-inverse) / <alpha-value>)',
                },
                line: {
                    DEFAULT: 'rgb(var(--mp-line) / <alpha-value>)',
                    strong: 'rgb(var(--mp-line-strong) / <alpha-value>)',
                },
                brand: {
                    DEFAULT: 'rgb(var(--mp-brand) / <alpha-value>)',
                    soft: 'rgb(var(--mp-brand-soft) / <alpha-value>)',
                },
                // 背景装饰图形（大字水印、频谱刻度环）专用：浅色下是近黑，深色下是纯黑
                graphic: 'rgb(var(--mp-graphic) / <alpha-value>)',
                accent: {
                    DEFAULT: 'rgb(var(--mp-accent) / <alpha-value>)',
                    hover: 'rgb(var(--mp-accent-hover) / <alpha-value>)',
                },
            },
            fontFamily: {
                mono: [
                    '"JetBrains Mono"',
                    'ui-monospace',
                    'SFMono-Regular',
                    'Menlo',
                    'Monaco',
                    'Consolas',
                    '"Liberation Mono"',
                    '"Courier New"',
                    'monospace',
                    '"PingFang SC"',
                    '"Microsoft YaHei"',
                ],
                sans: [
                    '"Noto Sans SC"',
                    '"PingFang SC"',
                    '"Hiragino Sans GB"',
                    '"Microsoft YaHei"',
                    '"微软雅黑"',
                    'system-ui',
                    '-apple-system',
                    'BlinkMacSystemFont',
                    '"Segoe UI"',
                    'Roboto',
                    '"Helvetica Neue"',
                    'Arial',
                    'sans-serif',
                ],
            },
        },
    },
    plugins: [],
};
