package com.example.LineFollowerRobot

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class RoutePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintPath = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintStart = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 200, 0)
    }

    private val paintEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }

    private var route: List<String> = emptyList()

    private val path = Path()
    private var startPoint = PointF()
    private var endPoint = PointF()

    private var startEndDistancePx = 0f
    fun getStartEndDistance(): Float = startEndDistancePx

    // ---------------- Modelo para seguidor de línea ----------------
    private val step = 9.5f
    private val turnRatio = 0.93f
    private val wheelBase = 38f

    // ---------------- Cerrado de circulos (solo visual) ----------------
    private val enableLoopClosure = true

    // 0.25 significa “si el error es menor al 25% de lo recorrido”
    private val loopClosureThreshold = 0.25f

    fun setRoute(cmds: List<String>) {
        route = cmds
        rebuildPath()
        invalidate()
    }

    private fun rebuildPath() {
        path.reset()

        if (route.isEmpty() || width <= 0 || height <= 0) {
            startPoint = PointF()
            endPoint = PointF()
            startEndDistancePx = 0f
            return
        }

        var x = 0.0
        var y = 0.0
        var theta = 0.0

        val pts = ArrayList<PointF>(route.size + 1)
        pts.add(PointF(0f, 0f))

        fun stepDiff(left: Float, right: Float) {
            val v = (left + right) / 2f
            val omega = (right - left) / wheelBase

            if (abs(omega) < 1e-6) {
                x += (v * cos(theta))
                y += (v * sin(theta))
            } else {
                val r = v / omega
                val newTheta = theta + omega
                x += r * (sin(newTheta) - sin(theta))
                y += -r * (cos(newTheta) - cos(theta))
                theta = newTheta
            }

            pts.add(PointF(x.toFloat(), y.toFloat()))
        }

        for (cmd in route) {
            when (cmd.first()) {
                'W' -> stepDiff(step, step)
                'D' -> stepDiff(step * turnRatio, step)
                'A' -> stepDiff(step, step * turnRatio)
                'S' -> stepDiff(-step, -step)
            }
        }

        // ----------- Cerrado de círculos -----------
        if (enableLoopClosure && pts.size >= 3) {
            // longitud total recorrida
            var totalLen = 0f
            for (i in 1 until pts.size) {
                totalLen += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            }

            val start = pts.first()
            val end = pts.last()
            val errX = end.x - start.x
            val errY = end.y - start.y
            val err = hypot(errX, errY)

            // si error < 25% se cierra
            if (totalLen > 1e-3f && (err / totalLen) < loopClosureThreshold) {
                val n = pts.size - 1
                for (i in 0..n) {
                    val t = i.toFloat() / n.toFloat() // 0..1

                    val c = cos(Math.PI * t).toFloat()
                    val w = (c * c).let { it * it }  // cos^2(pi*t)

                    val shiftX = errX * (0.5f - t) * w
                    val shiftY = errY * (0.5f - t) * w

                    pts[i] = PointF(
                        pts[i].x + shiftX,
                        pts[i].y + shiftY
                    )
                }
            }
        }

        // --- ESCALADO ---
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }

        val wModel = max(1f, maxX - minX)
        val hModel = max(1f, maxY - minY)

        val pad = 24f
        val scale = min(
            (width - 2 * pad) / wModel,
            (height - 2 * pad) / hModel
        )

        fun map(p: PointF): PointF =
            PointF(
                (p.x - minX) * scale + pad,
                (p.y - minY) * scale + pad
            )

        val p0 = map(pts.first())
        path.moveTo(p0.x, p0.y)

        for (i in 1 until pts.size) {
            val pi = map(pts[i])
            path.lineTo(pi.x, pi.y)
        }

        startPoint = p0
        endPoint = map(pts.last())
        startEndDistancePx = hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(245, 245, 245))

        if (route.isEmpty()) return

        canvas.drawPath(path, paintPath)
        canvas.drawCircle(startPoint.x, startPoint.y, 12f, paintStart)
        canvas.drawCircle(endPoint.x, endPoint.y, 12f, paintEnd)
    }
}
