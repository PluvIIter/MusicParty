/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // 定义“医疗白”风格色板 - 修复版，补全了所有色阶
        'medical': {
          50: '#F9FAFB',  // 背景
          100: '#F3F4F6', // 次级背景
          200: '#E5E7EB', // 边框
          300: '#D1D5DB', // 补充：深一点的边框
          400: '#9CA3AF', // 补充：滚动条悬停色
          500: '#6B7280', // 补充：次级文字
          600: '#4B5563', // 补充
          700: '#374151', // 补充
          800: '#1F2937', // 主要文字
          900: '#111827', // 标题/深色背景
        },
        'accent': {
          DEFAULT: '#F97316', // 警示橙 (Orange-500)
          hover: '#EA580C',
        }
      },
      fontFamily: {
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', '"Liberation Mono"', '"Courier New"', 'monospace', '"PingFang SC"', '"Microsoft YaHei"'],
        sans: [
          '"PingFang SC"',
          '"Hiragino Sans GB"',
          '"Microsoft YaHei"',
          '"Noto Sans SC"',
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'Roboto',
          '"Helvetica Neue"',
          'Arial',
          'sans-serif'
        ],
      }
    },
  },
  plugins: [],
}