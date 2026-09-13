package org.neuronavx.logger.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// =====================================================================================
// Sparkline
// =====================================================================================

/**
 * A glowing series line with a fading fill underneath, used for the speed and heading
 * traces on the analytics screen. Values are plotted as-is; the view autoscales to the
 * observed range unless [fixedRange] is supplied.
 */
class Sparkline(context: Context) : View(context) {

    var accent: Int = Glass.EMERALD
        set(value) {
            field = value
            invalidate()
        }

    var fixedRange: Pair<Float, Float>? = null

    private var values: FloatArray = FloatArray(0)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(context.dpf(6f), BlurMaskFilter.Blur.NORMAL)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(0.8f)
        color = Glass.alpha(Color.WHITE, 0.07f)
    }

    private val linePath = Path()
    private val fillPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // BlurMaskFilter is not hardware accelerated
    }

    fun setValues(series: FloatArray) {
        values = series
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (values.size < 2) return

        val (lo, hi) = fixedRange ?: run {
            val min = values.min()
            val max = values.max()
            val pad = ((max - min) * 0.15f).coerceAtLeast(0.5f)
            (min - pad) to (max + pad)
        }
        val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        val inset = context.dpf(3f)
        val usable = (h - inset * 2).coerceAtLeast(1f)

        linePath.reset()
        fillPath.reset()
        values.forEachIndexed { index, value ->
            val x = w * index / (values.size - 1).toFloat()
            val y = inset + usable * (1f - ((value - lo) / span)).coerceIn(0f, 1f)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Glass.alpha(accent, 0.32f), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(fillPath, fillPaint)

        glowPaint.color = Glass.alpha(accent, 0.45f)
        canvas.drawPath(linePath, glowPaint)
        linePaint.color = accent
        canvas.drawPath(linePath, linePaint)
    }
}

// =====================================================================================
// GNSS availability timeline
// =====================================================================================

/**
 * The satellite-availability strip: one tick per time slice, coloured by what the
 * estimator was doing. Ticks are drawn as rounded bars so the strip reads as a signal
 * meter rather than a progress bar.
 */
class AvailabilityBar(context: Context) : View(context) {

    enum class Slice { AVAILABLE, BLACKOUT, REACQUIRING }

    private var slices: List<Slice> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(context.dpf(4f), BlurMaskFilter.Blur.NORMAL)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setSlices(value: List<Slice>) {
        slices = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (slices.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = context.dpf(2f)
        val barWidth = ((w + gap) / slices.size) - gap
        if (barWidth <= 0f) return
        val radius = (barWidth / 2f).coerceAtMost(context.dpf(2f))

        slices.forEachIndexed { index, slice ->
            val color = when (slice) {
                Slice.AVAILABLE -> Glass.EMERALD
                Slice.BLACKOUT -> Glass.RED
                Slice.REACQUIRING -> Glass.SKY
            }
            val left = index * (barWidth + gap)
            glowPaint.color = Glass.alpha(color, 0.5f)
            canvas.drawRoundRect(left, 0f, left + barWidth, h, radius, radius, glowPaint)
            paint.color = color
            canvas.drawRoundRect(left, 0f, left + barWidth, h, radius, radius, paint)
        }
    }
}

// =====================================================================================
// Composite helpers
// =====================================================================================

/** One "● LABEL: VALUE" readiness line, as used in the home resilience card. */
fun Context.statusLine(label: String, value: String, accent: Int): LinearLayout = row().apply {
    val dot = View(this@statusLine).apply {
        background = solid(accent, Glass.R_CHIP)
        glow(accent, 6f)
    }
    addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
    addView(textView("$label:", 12f, Glass.TEXT_SECONDARY))
    addView(textView(value, 12f, accent, bold = true), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginStart = dp(5) })
}

/** A labelled metric tile: small caps label above a large value. */
fun Context.metricTile(
    label: String,
    value: String,
    accent: Int = Glass.TEXT_PRIMARY,
    icon: String? = null,
): LinearLayout = column().apply {
    setPadding(dp(14), dp(13), dp(14), dp(13))
    background = glass(Glass.R_INNER, Glass.PANEL_FILL_INNER)
    val head = row()
    if (icon != null) {
        head.addView(textView(icon, 13f, accent), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) })
    }
    head.addView(eyebrow(label, Glass.TEXT_MUTED, 9f))
    addView(head)
    addView(textView(value, 18f, accent, bold = true), matchWrap(6, this@metricTile))
}

/** The estimator pipeline strip: IMU, Calibration, TCN, ES-EKF, Recovery. */
fun Context.pipelineStrip(stages: List<String>, activeIndex: Int = stages.lastIndex): LinearLayout =
    row().apply {
        stages.forEachIndexed { index, stage ->
            val active = index == activeIndex
            val accent = if (active) Glass.EMERALD else Glass.TEAL
            val chip = textView(stage, 9.5f, if (active) accent else Glass.TEXT_SECONDARY, bold = true, tracking = 0.04f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = accentChip(accent, Glass.R_CHIP)
                if (active) glow(accent, 8f)
            }
            addView(chip)
            if (index != stages.lastIndex) {
                addView(View(this@pipelineStrip).apply {
                    background = solid(Glass.alpha(Glass.TEAL, 0.45f), Glass.R_CHIP)
                }, LinearLayout.LayoutParams(dp(9), dp(2)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
        }
    }

// =====================================================================================
// Floating pill navigation bar
// =====================================================================================

/**
 * The floating bottom tab bar. It is a frosted pill that sits above the gesture inset;
 * the selected tab gets a filled emerald lozenge and a soft glow.
 */
class GlassNavBar(context: Context) : FrameLayout(context) {

    data class Tab(val icon: String, val label: String, val onSelect: () -> Unit)

    private val strip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(7), context.dp(7), context.dp(7), context.dp(7))
    }

    init {
        background = context.glass(Glass.R_CHIP, fill = 0xE60C1118.toInt(), border = Glass.BORDER_BRIGHT)
        glow(Glass.EMERALD, 18f)
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTabs(tabs: List<Tab>, selectedIndex: Int) {
        strip.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(8))
                background = context.pressable(
                    if (selected) context.accentChip(Glass.EMERALD, Glass.R_CHIP)
                    else context.solid(Color.TRANSPARENT, Glass.R_CHIP),
                )
                isClickable = true
                isFocusable = true
                contentDescription = tab.label
                val tint = if (selected) Glass.EMERALD else Glass.TEXT_MUTED
                // Children of a vertical LinearLayout default to MATCH_PARENT width, so
                // each label must centre its own text; the parent gravity is not enough.
                addView(context.textView(tab.icon, 15f, tint).apply { gravity = Gravity.CENTER })
                addView(context.textView(tab.label, 9.5f, tint, bold = selected).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, context.dp(3), 0, 0)
                })
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (!selected) tab.onSelect()
                }
            }
            if (selected) item.glow(Glass.EMERALD, 10f)
            strip.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
