package com.kirufetch.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 被动交互背景：细网格 + 粒子游走 + 近邻连线 + 漂浮多边形。
 *
 * 形态参考「科技交互背景」，配色换成极简留白的墨色低透明度。
 * 性能上做了三件事：网格与连线批量 drawLines、多边形复用 Path、每帧不分配对象，
 * 以 60fps 为目标（vsync 驱动），不可见时自动停机。
 */
class MinimalFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Dot(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val alpha: Float,
        var phase: Float
    )

    private class Poly(
        var x: Float,
        var y: Float,
        val size: Float,
        var rotation: Float,
        val spin: Float,
        val sides: Int,
        val alpha: Int,
        var vx: Float,
        var vy: Float
    )

    private val density = resources.displayMetrics.density
    private val dots = ArrayList<Dot>(64)
    private val polys = ArrayList<Poly>(5)
    private val random = Random(20261120)

    private val ink = 0x1A1A1A

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
        color = ink
    }
    private val polyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        color = ink
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f * density
        color = ink
    }

    /** 复用缓冲：网格与连线都塞进 FloatArray 用一次 drawLines 提交。 */
    private var gridBuffer = FloatArray(0)
    private var linkBuffer = FloatArray(0)
    private val polyPath = Path()

    private var elapsed = 0f
    private var lastFrame = 0L
    private var running = false

    private var pointerX = -1f
    private var pointerY = -1f
    private var pointerActive = false

    private val linkDistance = 108f * density
    private val pointerRadius = 160f * density
    private val gridStep = 78f * density

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    /** 由 Activity 转发触摸，本 View 不消费事件，保持"被动"。 */
    fun updatePointer(x: Float, y: Float, active: Boolean) {
        pointerX = x
        pointerY = y
        pointerActive = active
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        seed(w.toFloat(), h.toFloat())
    }

    private fun seed(w: Float, h: Float) {
        dots.clear()
        polys.clear()
        if (w <= 0f || h <= 0f) return

        val count = ((w * h) / (44000f * density * density)).toInt().coerceIn(30, 64)
        repeat(count) {
            dots += Dot(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                vx = (random.nextFloat() - 0.5f) * 0.22f * density,
                vy = (random.nextFloat() - 0.5f) * 0.22f * density,
                radius = (0.9f + random.nextFloat() * 1.4f) * density,
                alpha = 5f + random.nextFloat() * 8f,
                phase = random.nextFloat() * 6.28f
            )
        }

        repeat(5) {
            polys += Poly(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                size = (26f + random.nextFloat() * 44f) * density,
                rotation = random.nextFloat() * 360f,
                spin = (random.nextFloat() - 0.5f) * 0.06f,
                sides = 3 + random.nextInt(3),
                alpha = 3 + random.nextInt(4),
                vx = (random.nextFloat() - 0.5f) * 0.05f * density,
                vy = (random.nextFloat() - 0.5f) * 0.05f * density
            )
        }

        val pairs = dots.size * (dots.size - 1) / 2
        linkBuffer = FloatArray(pairs * 4)
        rebuildGridBuffer()
    }

    private fun rebuildGridBuffer() {
        val cols = (width / gridStep).toInt() + 2
        val rows = (height / gridStep).toInt() + 2
        val need = (cols + rows) * 4
        if (gridBuffer.size < need) gridBuffer = FloatArray(need)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncRunning()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private fun syncRunning() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE &&
                windowVisibility == VISIBLE
        if (shouldRun && !running) {
            running = true
            lastFrame = 0L
            postInvalidateOnAnimation()
        } else if (!shouldRun) {
            running = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceAtMost(48L).toFloat()
        lastFrame = now
        if (running) advance(dt)

        drawGrid(canvas)
        drawPolys(canvas)
        drawDots(canvas)

        if (running) postInvalidateOnAnimation()
    }

    private fun advance(dt: Float) {
        elapsed += dt
        val w = width.toFloat()
        val h = height.toFloat()
        val step = dt / 16f

        for (i in dots.indices) {
            val dot = dots[i]
            if (pointerActive) {
                val dx = pointerX - dot.x
                val dy = pointerY - dot.y
                val dist = hypot(dx, dy)
                if (dist < pointerRadius && dist > 1f) {
                    val force = (pointerRadius - dist) / pointerRadius
                    dot.vx += dx / dist * force * 0.05f * density
                    dot.vy += dy / dist * force * 0.05f * density
                }
            }

            if (hypot(dot.vx, dot.vy) > 0.5f * density) {
                dot.vx *= 0.96f
                dot.vy *= 0.96f
            }

            dot.x += dot.vx * step
            dot.y += dot.vy * step
            if (dot.x < 0f) { dot.x = 0f; dot.vx = -dot.vx }
            if (dot.x > w) { dot.x = w; dot.vx = -dot.vx }
            if (dot.y < 0f) { dot.y = 0f; dot.vy = -dot.vy }
            if (dot.y > h) { dot.y = h; dot.vy = -dot.vy }

            dot.phase += 0.006f * step
        }

        for (i in polys.indices) {
            val poly = polys[i]
            poly.x += poly.vx * step * 8f
            poly.y += poly.vy * step * 8f
            poly.rotation += poly.spin * dt
            if (poly.x < -poly.size) poly.x = w + poly.size
            if (poly.x > w + poly.size) poly.x = -poly.size
            if (poly.y < -poly.size) poly.y = h + poly.size
            if (poly.y > h + poly.size) poly.y = -poly.size
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val offset = (elapsed * 0.012f) % gridStep
        var idx = 0
        var x = -offset
        while (x < width) {
            gridBuffer[idx++] = x; gridBuffer[idx++] = 0f
            gridBuffer[idx++] = x; gridBuffer[idx++] = height.toFloat()
            x += gridStep
        }
        var y = -offset
        while (y < height) {
            gridBuffer[idx++] = 0f; gridBuffer[idx++] = y
            gridBuffer[idx++] = width.toFloat(); gridBuffer[idx++] = y
            y += gridStep
        }
        gridPaint.alpha = 6
        canvas.drawLines(gridBuffer, 0, idx, gridPaint)
    }

    private fun drawPolys(canvas: Canvas) {
        for (i in polys.indices) {
            val poly = polys[i]
            polyPaint.alpha = poly.alpha
            polyPath.reset()
            for (k in 0..poly.sides) {
                val angle = k.toFloat() / poly.sides * 6.2831855f
                val px = cos(angle) * poly.size
                val py = sin(angle) * poly.size
                if (k == 0) polyPath.moveTo(px, py) else polyPath.lineTo(px, py)
            }
            polyPath.close()
            canvas.save()
            canvas.rotate(poly.rotation, poly.x, poly.y)
            canvas.translate(poly.x, poly.y)
            canvas.drawPath(polyPath, polyPaint)
            canvas.restore()
        }
    }

    private fun drawDots(canvas: Canvas) {
        var idx = 0
        val count = dots.size
        for (i in 0 until count) {
            val a = dots[i]
            for (j in i + 1 until count) {
                val b = dots[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                if (dx > linkDistance || dx < -linkDistance) continue
                if (dy > linkDistance || dy < -linkDistance) continue
                val dist = hypot(dx, dy)
                if (dist < linkDistance && idx + 4 <= linkBuffer.size) {
                    linkBuffer[idx++] = a.x
                    linkBuffer[idx++] = a.y
                    linkBuffer[idx++] = b.x
                    linkBuffer[idx++] = b.y
                }
            }
        }
        if (idx > 0) {
            linePaint.alpha = 7
            canvas.drawLines(linkBuffer, 0, idx, linePaint)
        }

        if (pointerActive) {
            var pIdx = 0
            for (i in dots.indices) {
                val dot = dots[i]
                val dx = pointerX - dot.x
                val dy = pointerY - dot.y
                val dist = hypot(dx, dy)
                if (dist < pointerRadius && pIdx + 4 <= linkBuffer.size) {
                    linkBuffer[pIdx++] = dot.x
                    linkBuffer[pIdx++] = dot.y
                    linkBuffer[pIdx++] = pointerX
                    linkBuffer[pIdx++] = pointerY
                }
            }
            if (pIdx > 0) {
                linePaint.alpha = 20
                canvas.drawLines(linkBuffer, 0, pIdx, linePaint)
            }
        }

        for (i in dots.indices) {
            val dot = dots[i]
            val pulse = 0.6f + sin(dot.phase) * 0.4f
            dotPaint.alpha = (dot.alpha * pulse).toInt().coerceIn(3, 26)
            canvas.drawCircle(dot.x, dot.y, dot.radius, dotPaint)
        }
    }
}
