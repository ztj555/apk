package com.autodial.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import java.util.Random

/**
 * 拨号成功后的视觉反馈动画悬浮窗
 *
 * 支持三种模式：
 * - MODE_FIREWORK (1): 烟花绽放 - 文字从中心放大弹出，伴随粒子火花四溅，淡出
 * - MODE_BOUNCE (2): 弹性弹跳 - 文字从左侧弹性飞入，跳动两下后稳定居中
 * - MODE_COMBINE (3): 结合 - 弹性飞入 + 烟花绽放
 *
 * 使用 WindowManager 全屏悬浮窗，可在任何界面显示（通话界面/桌面/其他APP）
 */
object DialAnimationOverlay {

    private const val TAG = "DialAnimation"
    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismissInternal() }

    const val MODE_OFF = 0
    const val MODE_FIREWORK = 1
    const val MODE_BOUNCE = 2
    const val MODE_COMBINE = 3

    /** 从 SharedPreferences 读取动画模式 */
    fun loadMode(context: Context): Int {
        return context.getSharedPreferences("autodial", Context.MODE_PRIVATE)
            .getInt("dial_animation_mode", MODE_OFF)
    }

    /** 从 SharedPreferences 读取自定义文字 */
    fun loadText(context: Context): String {
        return context.getSharedPreferences("autodial", Context.MODE_PRIVATE)
            .getString("dial_animation_text", "财运+1") ?: "财运+1"
    }

    /**
     * 显示动画（仅当模式不为 OFF 时）
     * @param context Context
     */
    fun show(context: Context) {
        val mode = loadMode(context)
        if (mode == MODE_OFF) return

        val text = loadText(context)
        show(context, text, mode)
    }

    /**
     * 显示动画
     * @param context Context
     * @param text 显示文字
     * @param mode 动画模式 (MODE_FIREWORK / MODE_BOUNCE / MODE_COMBINE)
     */
    fun show(context: Context, text: String, mode: Int) {
        handler.post {
            try {
                // 先移除已有的
                dismissInternal()
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val params = WindowManager.LayoutParams().apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    gravity = Gravity.CENTER
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    format = PixelFormat.TRANSLUCENT

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                }

                overlayView = OverlayView(context, text, mode)
                windowManager?.addView(overlayView, params)

                // 1.8秒后自动消失
                handler.postDelayed(dismissRunnable, 1800)

                Log.d(TAG, "动画已显示 (mode=$mode, text=$text)")
            } catch (e: Exception) {
                Log.e(TAG, "显示动画失败: ${e.message}")
            }
        }
    }

    /**
     * 移除动画
     */
    fun dismiss() {
        handler.post { dismissInternal() }
    }

    private fun dismissInternal() {
        handler.removeCallbacks(dismissRunnable)
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {}
        overlayView = null
    }

    // ==================== 自定义绘制 View ====================

    private class OverlayView(
        context: Context,
        private val text: String,
        private val mode: Int
    ) : android.view.View(context) {

        private val dp = context.resources.displayMetrics.density
        private val random = Random()
        private val startTime = System.currentTimeMillis()
        private val totalDuration = 1500L // 1.5秒总时长

        // 渐变着色器
        private val textShader: LinearGradient = LinearGradient(
            0f, 0f, 200 * dp, 0f,
            intArrayOf(Color.parseColor("#FFD700"), Color.parseColor("#FF8C00"), Color.parseColor("#FF4500")),
            null, Shader.TileMode.CLAMP
        )

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = textShader
            textSize = 42 * dp
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            // 轻微阴影增加光泽感
            setShadowLayer(6 * dp, 0f, 2 * dp, Color.parseColor("#55330000"))
        }

        // 粒子系统
        private val particles = mutableListOf<Particle>()
        private var particlesInitialized = false

        init {
            // 生成粒子
            initParticles()
        }

        private fun initParticles() {
            particles.clear()
            if (mode == MODE_FIREWORK || mode == MODE_COMBINE) {
                // 生成 40 个粒子
                for (i in 0 until 40) {
                    val angle = random.nextFloat() * Math.PI * 2
                    val speed = (2f + random.nextFloat() * 4f) * dp
                    particles.add(Particle(
                        x = 0f,
                        y = 0f,
                        vx = (Math.cos(angle) * speed).toFloat(),
                        vy = (Math.sin(angle) * speed).toFloat(),
                        size = (2 + random.nextFloat() * 4) * dp,
                        life = 0.4f + random.nextFloat() * 0.4f, // 生命周期 40%~80%
                        delay = random.nextFloat() * 0.1f, // 延迟 0~10%
                        color = when (random.nextInt(5)) {
                            0 -> Color.parseColor("#FFD700")
                            1 -> Color.parseColor("#FFA500")
                            2 -> Color.parseColor("#FF6347")
                            3 -> Color.parseColor("#FFEC8B")
                            else -> Color.parseColor("#FF4500")
                        }
                    ))
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val elapsed = (System.currentTimeMillis() - startTime).toFloat()
            val progress = (elapsed / totalDuration).coerceIn(0f, 1f)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            canvas.save()

            when (mode) {
                MODE_FIREWORK -> drawFirework(canvas, cx, cy, progress)
                MODE_BOUNCE -> drawBounce(canvas, cx, cy, w, progress)
                MODE_COMBINE -> drawCombined(canvas, cx, cy, w, progress)
            }

            canvas.restore()

            // 继续动画
            if (progress < 1f) {
                postInvalidateOnAnimation()
            }
        }

        // ==================== 烟花绽放效果 ====================
        private fun drawFirework(canvas: Canvas, cx: Float, cy: Float, progress: Float) {
            // 阶段划分：
            // 0~20%: 文字从 0 缩放到 1.2 (overshoot)
            // 20~35%: 文字从 1.2 回弹到 1.0
            // 35~75%: 文字保持，粒子扩散
            // 75~100%: 全体淡出

            val textAlpha: Int
            val textScale: Float

            when {
                progress < 0.2f -> {
                    val t = progress / 0.2f
                    val eased = easeOutBack(t)
                    textScale = eased * 1.2f
                    textAlpha = 255
                }
                progress < 0.35f -> {
                    val t = (progress - 0.2f) / 0.15f
                    textScale = 1.2f - 0.2f * easeOutQuad(t)
                    textAlpha = 255
                }
                progress < 0.75f -> {
                    textScale = 1.0f
                    textAlpha = 255
                }
                else -> {
                    val t = (progress - 0.75f) / 0.25f
                    textScale = 1.0f
                    textAlpha = (255 * (1f - easeInQuad(t))).toInt()
                }
            }

            // 绘制文字
            textPaint.alpha = textAlpha.coerceIn(0, 255)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(textScale, textScale, 0f, 0f)
            val textY = -(textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(text, 0f, textY, textPaint)
            canvas.restore()

            // 绘制粒子
            drawParticles(canvas, cx, cy, progress)
        }

        // ==================== 弹性弹跳效果 ====================
        private fun drawBounce(canvas: Canvas, cx: Float, cy: Float, w: Float, progress: Float) {
            // 阶段划分：
            // 0~25%: 从左侧 -w 飞到 cx
            // 25~40%: 第一次跳动 (向上)
            // 40~55%: 第二次跳动 (向上，幅度小)
            // 55~85%: 稳定居中
            // 85~100%: 淡出

            val textX: Float
            val textY: Float
            val textAlpha: Int

            when {
                progress < 0.25f -> {
                    val t = progress / 0.25f
                    val eased = easeOutCubic(t)
                    textX = -w / 2f + (w) * eased
                    textY = cy
                    textAlpha = 255
                }
                progress < 0.40f -> {
                    val t = (progress - 0.25f) / 0.15f
                    textX = cx
                    textY = cy - 30 * dp * easeOutQuad(t) * (1f - t)
                    textAlpha = 255
                }
                progress < 0.55f -> {
                    val t = (progress - 0.40f) / 0.15f
                    textX = cx
                    textY = cy - 15 * dp * easeOutQuad(t) * (1f - t)
                    textAlpha = 255
                }
                progress < 0.85f -> {
                    textX = cx
                    textY = cy
                    textAlpha = 255
                }
                else -> {
                    val t = (progress - 0.85f) / 0.15f
                    textX = cx
                    textY = cy
                    textAlpha = (255 * (1f - easeInQuad(t))).toInt()
                }
            }

            // 绘制文字
            textPaint.alpha = textAlpha.coerceIn(0, 255)
            val baselineY = textY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(text, textX, baselineY, textPaint)
        }

        // ==================== 结合效果 ====================
        private fun drawCombined(canvas: Canvas, cx: Float, cy: Float, w: Float, progress: Float) {
            // 阶段划分：
            // 0~25%: 弹性飞入 (从左到中)
            // 25~40%: 弹跳一次
            // 40~55%: 烟花绽放 (放大 + 粒子)
            // 55~75%: 文字保持，粒子扩散
            // 75~100%: 淡出

            val textX: Float
            val textY: Float
            val textScale: Float
            val textAlpha: Int

            when {
                progress < 0.25f -> {
                    val t = progress / 0.25f
                    val eased = easeOutCubic(t)
                    textX = -w / 2f + w * eased
                    textY = cy
                    textScale = 0.8f + 0.2f * eased
                    textAlpha = 255
                }
                progress < 0.40f -> {
                    val t = (progress - 0.25f) / 0.15f
                    textX = cx
                    textY = cy - 25 * dp * easeOutQuad(t) * (1f - t)
                    textScale = 1.0f
                    textAlpha = 255
                }
                progress < 0.55f -> {
                    val t = (progress - 0.40f) / 0.15f
                    val eased = easeOutBack(t)
                    textX = cx
                    textY = cy
                    textScale = 1.0f + 0.3f * eased
                    textAlpha = 255
                }
                progress < 0.75f -> {
                    val t = (progress - 0.55f) / 0.20f
                    textX = cx
                    textY = cy
                    textScale = 1.3f - 0.3f * easeOutQuad(t)
                    textAlpha = 255
                }
                else -> {
                    val t = (progress - 0.75f) / 0.25f
                    textX = cx
                    textY = cy
                    textScale = 1.0f
                    textAlpha = (255 * (1f - easeInQuad(t))).toInt()
                }
            }

            // 绘制文字
            textPaint.alpha = textAlpha.coerceIn(0, 255)
            canvas.save()
            canvas.translate(textX, textY)
            canvas.scale(textScale, textScale, 0f, 0f)
            val textBaseline = -(textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(text, 0f, textBaseline, textPaint)
            canvas.restore()

            // 粒子（延迟到 40% 后才出现）
            if (progress > 0.40f) {
                drawParticles(canvas, cx, cy, progress, delayOffset = 0.40f)
            }
        }

        // ==================== 粒子绘制 ====================
        private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, progress: Float, delayOffset: Float = 0f) {
            val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

            for (p in particles) {
                val adjustedProgress = ((progress - delayOffset - p.delay) / (1f - delayOffset))
                if (adjustedProgress <= 0f) continue
                if (adjustedProgress > p.life) continue

                val t = adjustedProgress / p.life
                val alpha = (255 * (1f - easeInQuad(t))).toInt()
                if (alpha <= 0) continue

                // 粒子位置 = 起点 + 速度 * 时间 * 减速因子
                val damping = 1f - 0.3f * t
                val px = cx + p.vx * adjustedProgress * totalDuration / 1000f * damping
                val py = cy + p.vy * adjustedProgress * totalDuration / 1000f * damping + 50 * dp * t * t // 轻微重力

                // 粒子大小随生命衰减
                val size = p.size * (1f - 0.6f * t)

                particlePaint.color = p.color
                particlePaint.alpha = alpha.coerceIn(0, 255)
                canvas.drawCircle(px, py, size, particlePaint)
            }
        }

        // ==================== 缓动函数 ====================

        private fun easeOutBack(t: Float): Float {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            return 1f + c3 * (t - 1f).let { it * it * it } + c1 * (t - 1f).let { it * it }
        }

        private fun easeOutCubic(t: Float): Float {
            return 1f - (1f - t).let { it * it * it }
        }

        private fun easeOutQuad(t: Float): Float {
            return 1f - (1f - t) * (1f - t)
        }

        private fun easeInQuad(t: Float): Float {
            return t * t
        }

        // ==================== 粒子数据类 ====================
        private data class Particle(
            val x: Float,
            val y: Float,
            val vx: Float,
            val vy: Float,
            val size: Float,
            val life: Float,
            val delay: Float,
            val color: Int
        )
    }
}
