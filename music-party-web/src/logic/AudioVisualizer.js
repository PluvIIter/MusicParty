export class AudioVisualizer {
    constructor() {
        this.canvas = null;
        this.ctx = null;
        this.width = 0;
        this.height = 0;
        this.center = 0;
        this.animationId = null;

        // 动画状态变量
        this.rippleTime = 0;
        this.breatheOffset = 0;

        // 平滑过渡变量 (Lerp)
        this.smoothAlpha = 0.05;
        this.smoothWidthScale = 0.3;

        // 状态标记
        this.isPlaying = false;

        // 设计分辨率与渲染缩放 (物理分辨率 = 逻辑 × PIXEL_RATIO)。
        // 降级只在 Firefox 上生效：其 canvas 光栅化是软件实现，半分辨率让面积降到 1/4，
        // 放大交给合成器 (GPU)；其他浏览器保持 1200 原生分辨率与原有观感。
        this.LOGICAL_SIZE = 1200;
        this.isFirefox = typeof navigator !== 'undefined' && /Firefox\//.test(navigator.userAgent);
        this.PIXEL_RATIO = this.isFirefox ? 0.5 : 1;
        this.lastFrameTime = 0;

        // 爆发控制变量
        this.speedMultiplier = 1.0;
        this.widthMultiplier = 1.0;
        this.roughnessMultiplier = 1.0;

        // 爆发平滑控制（点赞触发的波动应平滑爬升，不要瞬间跳变）
        this.impulseActive = false;
        this.speedTarget = 1.0;
        this.widthTarget = 1.0;
        this.roughnessTarget = 1.0;

        // 配置参数 (Performance Optimized)
        this.breatheBars = 60; // Reduced from 120
        this.breatheRadiusBase = 180;

        // 刻度环颜色跟随主题令牌（--mp-graphic），按主题缓存，避免每帧读计算样式
        this._themeKey = null;
        this._graphicColor = '#0a0f14';

        // 圆环定义
        this.rings = [
            { radius: 450, baseWidth: 5, maxWidth: 150, speed: -0.015, offset: 0, segments: 3 },
            { radius: 450, baseWidth: 10, maxWidth: 100, speed: 0.02, offset: 2, segments: 4 },
            { radius: 450, baseWidth: 8, maxWidth: 80, speed: 0.03, offset: 4, segments: 5 },
        ];
    }

    /**
     * 初始化 Canvas
     * @param {HTMLCanvasElement} canvasElement
     */
    mount(canvasElement) {
        this.canvas = canvasElement;
        const size = Math.round(this.LOGICAL_SIZE * this.PIXEL_RATIO);
        this.canvas.width = size;
        this.canvas.height = size;
        this.ctx = this.canvas.getContext('2d', { alpha: true }); // optimize
        // 逻辑尺寸 (所有几何均按设计分辨率计算, 绘制时统一缩放)
        this.width = this.LOGICAL_SIZE;
        this.height = this.LOGICAL_SIZE;
        this.center = this.LOGICAL_SIZE / 2;
        this.startLoop();
    }

    unmount() {
        if (this.animationId) {
            cancelAnimationFrame(this.animationId);
            this.animationId = null;
        }
        this.canvas = null;
        this.ctx = null;
    }

    /**
     * 更新播放状态
     * @param {boolean} isPlaying
     */
    setPlaying(isPlaying) {
        this.isPlaying = isPlaying;
    }

    /**
     * 获取当前引擎状态指标
     */
    getStatus() {
        return {
            intensity: this.speedMultiplier.toFixed(2),
            rings: this.rings.length,
            bars: this.breatheBars,
            active: this.isPlaying,
            alpha: this.smoothAlpha.toFixed(2),
        };
    }

    //触发爆发特效
    impulse() {
        // 设置峰值作为目标值 + 进入 attack：由 draw() 里的 lerp 平滑爬升，
        // 避免瞬间拉高导致点赞那一下"突然波动"不平滑
        this.speedTarget = 6.0; // 速度峰值 6 倍
        this.widthTarget = 2.0; // 宽度峰值 2 倍
        this.roughnessTarget = 1.5; // 波动幅度峰值 1.5 倍
        this.impulseActive = true;
    }

    startLoop() {
        let lastDraw = 0;
        const loop = (now) => {
            if (!this.canvas || !this.ctx) return;

            // 播放时 60fps, 暂停时 10fps (暂停状态下动画几乎静止, 无交互时无需满帧渲染)。
            // 动画速度在 draw 内按帧间隔换算, 与 60fps 基准完全一致
            // 暂停降帧同样只在 Firefox 上启用；其他浏览器保持 60fps 的原观感
            const minInterval = this.isPlaying || !this.isFirefox ? 16.67 : 100;
            if (now - lastDraw >= minInterval) {
                lastDraw = now;
                this.draw(now);
            }
            this.animationId = requestAnimationFrame(loop);
        };
        this.animationId = requestAnimationFrame(loop);
    }

    /** 当前是否深色主题（读 data-theme，开销可忽略） */
    isDarkTheme() {
        return document.documentElement?.dataset?.theme === 'dark';
    }
    /** 背景装饰色（刻度环）：读 --mp-ring，随 data-theme 变化，按主题缓存避免每帧读样式 */
    graphicColor() {
        const theme = document.documentElement?.dataset?.palette || document.documentElement?.dataset?.theme || 'light';
        if (theme !== this._themeKey) {
            this._themeKey = theme;
            const raw = getComputedStyle(document.documentElement).getPropertyValue('--mp-ring').trim();
            this._graphicColor = raw ? `rgb(${raw})` : '#D1D5DB';
            this._accentColor =
                getComputedStyle(document.documentElement).getPropertyValue('--mp-accent').trim() || '249 115 22';
        }
        return this._graphicColor;
    }

    draw(now = performance.now()) {
        const { ctx, center } = this;

        // 帧间隔换算到 60fps 基准, 保证降帧后动画速度与衰减速率不变
        const frameScale = this.lastFrameTime ? Math.min(3, (now - this.lastFrameTime) / 16.67) : 1;
        this.lastFrameTime = now;

        // 爆发平滑：attack 缓入到峰值，逼近后再缓慢回落（release）。
        // 相比原"瞬间拉高"，这里用 lerp 让点赞波动平滑展开而不是突然跳变。
        // followFactor 乘以 frameScale：暂停降帧 (10fps) 时平滑爬升/回落
        // 速率与 60fps 基准保持一致（半分辨率渲染提交引入的换算方式）
        const followFactor = (this.impulseActive ? 0.2 : 0.005) * frameScale;

        this.speedMultiplier += (this.speedTarget - this.speedMultiplier) * followFactor;
        this.widthMultiplier += (this.widthTarget - this.widthMultiplier) * followFactor;
        this.roughnessMultiplier += (this.roughnessTarget - this.roughnessMultiplier) * followFactor;

        // 三个倍率都逼近峰值后，目标复位为 1.0，进入缓慢回落（延续原长尾效果）
        if (
            this.impulseActive &&
            Math.abs(this.speedMultiplier - this.speedTarget) < 0.15 &&
            Math.abs(this.widthMultiplier - this.widthTarget) < 0.1 &&
            Math.abs(this.roughnessMultiplier - this.roughnessTarget) < 0.1
        ) {
            this.impulseActive = false;
            this.speedTarget = 1.0;
            this.widthTarget = 1.0;
            this.roughnessTarget = 1.0;
        }

        // --- 1. 状态计算与平滑过渡 (Lerp) ---
        // 时间流速
        const baseSpeed = this.isPlaying ? 0.5 : 0.1;
        this.rippleTime += baseSpeed * this.speedMultiplier * frameScale;

        // 目标透明度与宽度
        const targetAlpha = this.isPlaying ? 0.25 : 0.05;
        const targetWidthScale = this.isPlaying ? 1.0 : 0.3;

        // 线性插值
        this.smoothAlpha += (targetAlpha - this.smoothAlpha) * 0.03 * frameScale;
        this.smoothWidthScale += (targetWidthScale - this.smoothWidthScale) * 0.05 * frameScale;

        // 优化：透明度极低时不渲染复杂图形
        if (this.smoothAlpha < 0.01) return;

        // 物理分辨率清除 + 整体缩放到设计分辨率 (光栅化面积降至 1/4)
        ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        ctx.save();
        ctx.scale(this.PIXEL_RATIO, this.PIXEL_RATIO);

        // --- 2. 绘制橙色流体圆环 ---
        // 深色主题下隐藏橙色呼吸环，避免与暗色背景争抢注意力
        if (!this.isDarkTheme()) {
            ctx.save();
            ctx.globalCompositeOperation = 'screen';
            if (!this.isFirefox) {
                // shadowBlur 是 Firefox 的主要光栅化热点，故只在 Firefox 关闭；其他浏览器保留原光晕
                ctx.shadowBlur = 50;
                this.graphicColor();
                ctx.shadowColor = `rgb(${this._accentColor})`;
            }

            this.rings.forEach((ring) => {
                // 波形点数：Firefox 减半以降低光栅化成本，其他浏览器保持 120 点
                const count = this.isFirefox ? 60 : 120;
                const currentMaxWidth = ring.maxWidth * this.smoothWidthScale * this.roughnessMultiplier;

                // 预计算一圈的波形点，内外圈共享 (原来内外圈各算一遍三角函数)
                const pts = [];
                for (let i = 0; i <= count; i++) {
                    const angle = (i / count) * Math.PI * 2;
                    const wave = Math.sin(angle * ring.segments + this.rippleTime * ring.speed + ring.offset);
                    pts.push({
                        x: Math.cos(angle),
                        y: Math.sin(angle),
                        w: ring.baseWidth + ((wave + 1) / 2) * currentMaxWidth,
                    });
                }

                // 外圈 → 内圈回折成环带，一次 fill 完成
                // (不再用宽描边模拟光晕：该描边是 Firefox 的新渲染热点，且光晕在浅色背景上几乎不可见)
                ctx.beginPath();
                for (let i = 0; i <= count; i++) {
                    ctx.lineTo(
                        center + pts[i].x * (ring.radius + pts[i].w / 2),
                        center + pts[i].y * (ring.radius + pts[i].w / 2)
                    );
                }
                for (let i = count; i >= 0; i--) {
                    ctx.lineTo(
                        center + pts[i].x * (ring.radius - pts[i].w / 2),
                        center + pts[i].y * (ring.radius - pts[i].w / 2)
                    );
                }
                ctx.closePath();
                this.graphicColor();
                ctx.fillStyle = `rgb(${this._accentColor} / ${this.smoothAlpha})`;
                ctx.fill();
            });
            ctx.restore();
        }

        // --- 3. 绘制呼吸态频谱 (前景灰色) ---
        ctx.globalCompositeOperation = 'source-over';
        this.breatheOffset += 0.05 * frameScale;

        // 全部线段合并为一条路径、一次 stroke (原来 60 次 beginPath/stroke)
        ctx.beginPath();
        for (let i = 0; i < this.breatheBars; i++) {
            const angle = (Math.PI * 2 * i) / this.breatheBars;
            const h = Math.sin(i * 0.5 + now / 500) * 5 + 5;
            const r1 = this.breatheRadiusBase + 10;
            const r2 = r1 + h;

            ctx.moveTo(center + Math.cos(angle) * r1, center + Math.sin(angle) * r1);
            ctx.lineTo(center + Math.cos(angle) * r2, center + Math.sin(angle) * r2);
        }
        ctx.strokeStyle = this.graphicColor();
        ctx.lineWidth = 2;
        ctx.lineCap = 'round';
        ctx.stroke();

        ctx.restore();
    }
}
