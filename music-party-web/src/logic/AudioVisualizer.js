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

        // 帧计数 (用于暂停时的降频渲染)
        this.frameCount = 0;

        // 爆发控制变量
        this.speedMultiplier = 1.0;
        this.widthMultiplier = 1.0;
        this.roughnessMultiplier = 1.0;

        // 配置参数 (Performance Optimized)
        this.breatheBars = 60; // Reduced from 120
        this.breatheRadiusBase = 180;

        // 圆环定义
        this.rings = [
            { radius: 450, baseWidth: 5, maxWidth: 150, speed: -0.015, offset: 0, segments: 3 },
            { radius: 450, baseWidth: 10, maxWidth: 100, speed: 0.02, offset: 2, segments: 4 },
            { radius: 450, baseWidth: 8, maxWidth: 80, speed: 0.03, offset: 4, segments: 5 }
        ];
    }

    /**
     * 初始化 Canvas
     * @param {HTMLCanvasElement} canvasElement
     */
    mount(canvasElement) {
        this.canvas = canvasElement;
        this.ctx = this.canvas.getContext('2d', { alpha: true }); // optimize
        this.width = this.canvas.width;
        this.height = this.canvas.height;
        this.center = this.width / 2;
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
            alpha: this.smoothAlpha.toFixed(2)
        };
    }

    //触发爆发特效
    impulse() {
        // 瞬间拉高参数
        this.speedMultiplier = 6.0;   // 速度变快 8 倍
        this.widthMultiplier = 2.0;   // 宽度变粗 1.8 倍
        this.roughnessMultiplier = 1.5; // 波动幅度变大
    }

    startLoop() {
        const loop = (now) => {
            if (!this.canvas || !this.ctx) return;

            this.draw(now);
            this.animationId = requestAnimationFrame(loop);
        };
        this.animationId = requestAnimationFrame(loop);
    }

    draw(now = performance.now()) {
        const { ctx, width, height, center } = this;
        ctx.clearRect(0, 0, width, height);

        // 如果不播放且没有爆发，可以降低渲染频率或跳过部分渲染（为了简单起见，这里保持 loop 但降低计算量）
        
        const decayFactor = 0.005;

        this.speedMultiplier += (1.0 - this.speedMultiplier) * decayFactor;
        this.widthMultiplier += (1.0 - this.widthMultiplier) * decayFactor;
        this.roughnessMultiplier += (1.0 - this.roughnessMultiplier) * decayFactor;

        // --- 1. 状态计算与平滑过渡 (Lerp) ---
        // 时间流速
        const baseSpeed = this.isPlaying ? 0.5 : 0.1;
        this.rippleTime += baseSpeed * this.speedMultiplier;

        // 目标透明度与宽度
        const targetAlpha = this.isPlaying ? 0.25 : 0.05;
        const targetWidthScale = this.isPlaying ? 1.0 : 0.3;

        // 线性插值
        this.smoothAlpha += (targetAlpha - this.smoothAlpha) * 0.03;
        this.smoothWidthScale += (targetWidthScale - this.smoothWidthScale) * 0.05;

        // 优化：透明度极低时不渲染复杂图形
        if (this.smoothAlpha < 0.01) return;

        // --- 2. 绘制橙色流体圆环 ---
        // 暂停时圆环降频渲染 (约 10fps)，呼吸条保持 60fps
        this.frameCount++;
        const drawRings = this.isPlaying || this.frameCount % 6 === 0;

        if (drawRings) {
            ctx.save();
            ctx.globalCompositeOperation = 'screen';

            this.rings.forEach((ring) => {
                const count = 60; // 波形点数 (3~5 段波, 每段 12~20 点, 运动+半透明下无可见棱角)
                const currentMaxWidth = ring.maxWidth * this.smoothWidthScale * this.roughnessMultiplier;

                // 预计算一圈的波形点，内外圈共享 (原来内外圈各算一遍三角函数)
                const pts = [];
                for (let i = 0; i <= count; i++) {
                    const angle = (i / count) * Math.PI * 2;
                    const wave = Math.sin(angle * ring.segments + this.rippleTime * ring.speed + ring.offset);
                    pts.push({
                        x: Math.cos(angle),
                        y: Math.sin(angle),
                        w: ring.baseWidth + ((wave + 1) / 2) * currentMaxWidth
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
                ctx.fillStyle = `rgba(249, 115, 22, ${this.smoothAlpha})`;
                ctx.fill();
            });
            ctx.restore();
        }

        // --- 3. 绘制呼吸态频谱 (前景灰色) ---
        ctx.globalCompositeOperation = 'source-over';
        this.breatheOffset += 0.05;

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
        ctx.strokeStyle = '#D1D5DB';
        ctx.lineWidth = 2;
        ctx.lineCap = 'round';
        ctx.stroke();
    }
}