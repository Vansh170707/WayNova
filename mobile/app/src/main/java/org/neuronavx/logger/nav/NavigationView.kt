package org.neuronavx.logger.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Live trajectory, drift and confidence (blueprint section 15).
 *
 * Two decisions here are not cosmetic.
 *
 * **Two tracks are drawn, not one.** The filter's estimate and the rendered position are
 * different quantities during a recovery: the estimate is allowed to snap to a returning
 * fix because that is the correct thing for it to do, while the display slews. Drawing only
 * the smoothed track would hide the correction entirely; drawing only the estimate brings
 * back the 245 m teleport section 9.2 forbids. Both are shown, faintly and brightly.
 *
 * **The confidence circle is the filter's own sigma**, not a fixed radius. During a long
 * outage it grows to hundreds of metres, which is the honest statement of what the system
 * knows -- and it shrinking sharply on re-acquisition is the clearest visual signal that
 * aiding is back.
 */
class NavigationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val estimatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#664C9BFF")
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val displayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.parseColor("#58A6FF")
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.parseColor("#FF9F4A")
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val trackGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 17f; color = Color.parseColor("#254C9BFF")
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val confidencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#2E4C9BFF")
    }
    private val confidenceOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#884C9BFF")
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#4C9BFF")
    }
    private val markerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.WHITE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.parseColor("#24344A")
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#304761")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7F90A7")
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#070B12")
    }

    private var east = DoubleArray(0)
    private var north = DoubleArray(0)
    private var dispEast = DoubleArray(0)
    private var dispNorth = DoubleArray(0)
    /** Per point: was aiding absent? A single split index cannot express a drive with
     *  more than one outage, and left everything after the first one coloured as denied. */
    private var dark = BooleanArray(0)
    private var markerE = Double.NaN
    private var markerN = Double.NaN
    private var heading = 0.0
    private var sigmaM = 0.0
    private var inOutage = false

    private var minE = 0.0; private var maxE = 0.0
    private var minN = 0.0; private var maxN = 0.0
    private var metresPerPixel = 1.0

    /**
     * Replace the rendered state. Arrays are referenced, not copied, so the caller must
     * hand over snapshots rather than live buffers being mutated on another thread.
     */
    fun submit(east: DoubleArray, north: DoubleArray,
               dispEast: DoubleArray, dispNorth: DoubleArray,
               dark: BooleanArray, markerE: Double, markerN: Double,
               heading: Double, sigmaM: Double, inOutage: Boolean) {
        this.east = east; this.north = north
        this.dispEast = dispEast; this.dispNorth = dispNorth
        this.dark = dark
        this.markerE = markerE; this.markerN = markerN
        this.heading = heading; this.sigmaM = sigmaM; this.inOutage = inOutage
        recomputeBounds()
        postInvalidateOnAnimation()
    }

    fun clear() {
        east = DoubleArray(0); north = DoubleArray(0)
        dispEast = DoubleArray(0); dispNorth = DoubleArray(0)
        dark = BooleanArray(0); markerE = Double.NaN; markerN = Double.NaN
        postInvalidateOnAnimation()
    }

    private fun recomputeBounds() {
        if (east.isEmpty()) return
        var lo0 = Double.MAX_VALUE; var hi0 = -Double.MAX_VALUE
        var lo1 = Double.MAX_VALUE; var hi1 = -Double.MAX_VALUE
        for (i in east.indices) {
            val e = east[i]; val n = north[i]
            if (!e.isFinite() || !n.isFinite()) continue
            lo0 = min(lo0, e); hi0 = max(hi0, e)
            lo1 = min(lo1, n); hi1 = max(hi1, n)
        }
        if (lo0 > hi0) return
        // include the confidence circle so a large one is never clipped off-screen
        val pad = max(sigmaM, 20.0)
        minE = lo0 - pad; maxE = hi0 + pad
        minN = lo1 - pad; maxN = hi1 + pad
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#101B2A"), Color.parseColor("#070B12"),
            Shader.TileMode.CLAMP,
        )
    }

    private fun sx(e: Double): Float {
        val w = (maxE - minE).coerceAtLeast(1.0)
        return (paddingLeft + (e - minE) / w * (width - paddingLeft - paddingRight)).toFloat()
    }

    private fun sy(n: Double): Float {
        val h = (maxN - minN).coerceAtLeast(1.0)
        // north is up, screen y grows downward
        return (height - paddingBottom -
            (n - minN) / h * (height - paddingTop - paddingBottom)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawAmbientGrid(canvas)
        if (east.isEmpty()) {
            drawEmptyState(canvas)
            return
        }
        // keep the projection square so a trajectory is not silently stretched
        val spanE = (maxE - minE).coerceAtLeast(1.0)
        val spanN = (maxN - minN).coerceAtLeast(1.0)
        val usableW = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val usableH = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val scale = min(usableW / spanE, usableH / spanN)
        metresPerPixel = 1.0 / scale
        val cx = (minE + maxE) / 2.0
        val cy = (minN + maxN) / 2.0
        minE = cx - usableW / scale / 2.0; maxE = cx + usableW / scale / 2.0
        minN = cy - usableH / scale / 2.0; maxN = cy + usableH / scale / 2.0

        drawScaleBar(canvas)

        // faint: what the filter believes; bright: what a user is shown
        canvas.drawPath(pathOf(dispEast, dispNorth, 0, dispEast.size), trackGlowPaint)
        canvas.drawPath(pathOf(east, north, 0, east.size), estimatePaint)
        // one run per contiguous stretch of the same aiding state, so a drive with several
        // outages is coloured correctly rather than everything after the first one
        var start = 0
        while (start < dispEast.size) {
            val flag = start < dark.size && dark[start]
            var end = start + 1
            while (end < dispEast.size && (end < dark.size && dark[end]) == flag) end++
            canvas.drawPath(pathOf(dispEast, dispNorth, max(start - 1, 0), end),
                if (flag) darkPaint else displayPaint)
            start = end
        }

        if (markerE.isFinite() && markerN.isFinite()) {
            val px = sx(markerE); val py = sy(markerN)
            val r = (sigmaM / metresPerPixel).toFloat()
            confidencePaint.color = if (inOutage) Color.parseColor("#30FF9F4A")
                else Color.parseColor("#2E4C9BFF")
            confidenceOutlinePaint.color = if (inOutage) Color.parseColor("#99FF9F4A")
                else Color.parseColor("#884C9BFF")
            if (r > 1f) {
                canvas.drawCircle(px, py, r, confidencePaint)
                canvas.drawCircle(px, py, r, confidenceOutlinePaint)
            }
            drawMarker(canvas, px, py)
        }
    }

    private fun drawAmbientGrid(canvas: Canvas) {
        val step = 48f * resources.displayMetrics.density
        var x = (paddingLeft % step.toInt()).toFloat()
        while (x < width) {
            canvas.drawLine(x, paddingTop.toFloat(), x, (height - paddingBottom).toFloat(), gridPaint)
            x += step
        }
        var y = paddingTop.toFloat()
        while (y < height - paddingBottom) {
            canvas.drawLine(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y, gridPaint)
            y += step
        }
        val cx = width / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        canvas.drawLine(cx - 18f, cy, cx + 18f, cy, axisPaint)
        canvas.drawLine(cx, cy - 18f, cx, cy + 18f, axisPaint)
        canvas.drawCircle(cx, cy, 8f, axisPaint)
    }

    private fun drawEmptyState(canvas: Canvas) {
        val cx = width / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) * 0.48f
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("LOCAL POSITIONING CANVAS", cx, cy + 46f, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private fun pathOf(e: DoubleArray, n: DoubleArray, from: Int, to: Int): Path {
        val path = Path()
        var started = false
        for (i in from until min(to, e.size)) {
            if (!e[i].isFinite() || !n[i].isFinite()) continue
            val x = sx(e[i]); val y = sy(n[i])
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        return path
    }

    /** A triangle pointing along the estimated heading, so orientation is visible. */
    private fun drawMarker(canvas: Canvas, px: Float, py: Float) {
        val size = 22f
        // heading is clockwise from North; screen y is inverted
        val hx = sin(heading).toFloat()
        val hy = -cos(heading).toFloat()
        val path = Path().apply {
            moveTo(px + hx * size, py + hy * size)
            lineTo(px - hy * size * 0.55f - hx * size * 0.5f,
                   py + hx * size * 0.55f - hy * size * 0.5f)
            lineTo(px + hy * size * 0.55f - hx * size * 0.5f,
                   py - hx * size * 0.55f - hy * size * 0.5f)
            close()
        }
        markerPaint.color = if (inOutage) Color.parseColor("#FF9F4A")
                            else Color.parseColor("#4C9BFF")
        canvas.drawPath(path, markerOutlinePaint)
        canvas.drawPath(path, markerPaint)
        canvas.drawCircle(px, py, 4f, markerOutlinePaint)
    }

    private fun drawScaleBar(canvas: Canvas) {
        val targetPx = width * 0.25
        val rawMetres = targetPx * metresPerPixel
        val metres = niceRound(rawMetres)
        val px = (metres / metresPerPixel).toFloat()
        val startX = paddingLeft.toFloat()
        val y = height - paddingBottom - 20f
        canvas.drawLine(startX, y, startX + px, y, axisPaint)
        canvas.drawLine(startX, y - 8f, startX, y + 8f, axisPaint)
        canvas.drawLine(startX + px, y - 8f, startX + px, y + 8f, axisPaint)
        canvas.drawText(if (metres >= 1000) "${(metres / 1000).toInt()} km"
                        else "${metres.toInt()} m", startX + 8f, y - 14f, labelPaint)
    }

    private fun niceRound(v: Double): Double {
        if (v <= 0 || !v.isFinite()) return 1.0
        val exp = Math.floor(Math.log10(v))
        val base = Math.pow(10.0, exp)
        val mult = v / base
        return base * when {
            mult < 1.5 -> 1.0
            mult < 3.5 -> 2.0
            mult < 7.5 -> 5.0
            else -> 10.0
        }
    }
}
