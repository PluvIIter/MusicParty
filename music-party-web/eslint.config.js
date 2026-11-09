import js from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import prettier from 'eslint-config-prettier';
import globals from 'globals';

export default [
    { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },
    js.configs.recommended,
    ...pluginVue.configs['flat/recommended'],
    prettier,
    {
        files: ['**/*.{js,vue}'],
        languageOptions: {
            ecmaVersion: 2023,
            sourceType: 'module',
            // 浏览器/WebView 全局一律取自 `globals` 包，不再手工维护清单：
            // src/ 实际用到的 MediaMetadata、matchMedia、fetch、URL、performance、
            // Blob、CustomEvent 等全部已被 globals.browser 覆盖，漏一个就误报的
            // 那个负担并不存在。（本仓库无 Node 全局用法，故未引入 globals.node。）
            globals: {
                ...globals.browser,
            },
        },
        rules: {
            // no-undef 是**承重的**，但它只覆盖 script 代码，**不覆盖模板**。
            //
            // 曾经这里写着「Vite 在构建期已经能拦住真正的未定义标识符」——这是错的，
            // 已实测：Rollup/Vite 只把无法解析的 **import** 当错误；无法解析的**裸标识符**
            // 会被当作全局变量原样发射进产物。探针 `const s = () => notDefinedAnywhere(1);`
            // 经 `vite build` 后 exit 0，产物里就是这行原文。
            //
            // 覆盖范围（已实测）：本规则是本项目里唯一能拦住「重构把代码搬走后，script 里
            // 留下未定义引用」的自动化手段（no-unused-vars 只管「声明未使用」，不管「使用
            // 未声明」）。spec §10 正是把「ESLint 捕获未定义引用」列为阶段 4 store 拆分的主要
            // 缓解之一。
            //
            // **模板不在覆盖范围内，这一条很容易被高估**：`<template>` 里的标识符不受本规则
            // 检查。模板中引用一个不存在的变量（`{{ onlineViews.length }}`）或一个不存在的
            // 组件（`<FooBar />`），实测都是 exit 0 / 零诊断。这类漏改（阶段 4 搬 store 后
            // 模板残留旧字段名就是典型）lint、单测、`npm run build` 三者**都**拦不住，
            // 只有人眼或运行时白屏能发现。
            'no-undef': 'error',
            // 死代码清理是阶段 6 的任务（spec §6.1）。在阶段 1 先降为 warn，
            // 这样 lint 能通过、问题可见，但不强迫本阶段改动生产代码。
            // 阶段 6 完成删除后改为 'error'。
            'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
            // 模板里未使用的 v-for 索引（ChatOverlay.vue 的 index、QueueList.vue 的 idx）
            // 同属死代码，与上面 no-unused-vars 一起留给阶段 6 清理。
            'vue/no-unused-vars': 'warn',
            'vue/multi-word-component-names': 'off',
            'vue/no-v-html': 'off',
        },
    },
    {
        // 测试脚本与构建配置跑在 Node 里（#28/#29/#30 引入的 tests/*.cjs、
        // playwright.config.js、vitest.config.js 都用到 require/process/Buffer 等）。
        files: ['**/*.cjs', 'tests/**/*.js', '*.config.js'],
        languageOptions: {
            globals: {
                ...globals.node,
                // 浏览器测试脚本里内嵌了在页面上下文执行的函数（page.evaluate），
                // 里面会直接引用 window / document。
                ...globals.browser,
            },
        },
    },
    {
        files: ['src/components/CenterConsole.vue'],
        rules: {
            // 第 89 行伪系统日志末尾有一个历史遗留的游离 `<`（`{{ log }} <</div>`）。
            // 浏览器与 Vue 都按文本恢复渲染，实际显示正常；它属于既有代码，
            // 修掉它会改变那一行的显示文本，故用文件级豁免而不做全局降级。
            'vue/no-parsing-error': 'warn',
        },
    },
];
