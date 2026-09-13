package org.neuronavx.logger.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Waynova liquid-glass design system.
 *
 * A single source of truth for the surfaces, accents, spacing and type used across every
 * screen. The look is frosted translucent panels floating over a dark aurora backdrop:
 * each panel is a stack of a low-alpha fill, a diagonal sheen that fakes the light
 * refraction of real glass, and a hairline border that is brighter at the top edge.
 *
 * Android cannot cheaply blur what is *behind* an in-window view, so depth is built from
 * layered translucency, coloured elevation shadows (API 28+) and soft radial glows rather
 * than a true backdrop filter.
 */
object Glass {

    // ---- Base canvas -------------------------------------------------------------
    const val BG_DEEP = 0xFF04060A.toInt()
    const val BG_BASE = 0xFF070A10.toInt()
    const val BG_RAISED = 0xFF0B1017.toInt()

    // ---- Accents -----------------------------------------------------------------
    const val EMERALD = 0xFF34D399.toInt()
    const val EMERALD_DEEP = 0xFF10B981.toInt()
    const val MINT = 0xFF6EE7B7.toInt()
    const val CYAN = 0xFF22D3EE.toInt()
    const val TEAL = 0xFF2DD4BF.toInt()
    const val SKY = 0xFF60A5FA.toInt()
    const val AMBER = 0xFFFBBF24.toInt()
    const val CORAL = 0xFFFB7185.toInt()
    const val RED = 0xFFFF4D5E.toInt()

    // ---- Type --------------------------------------------------------------------
    const val TEXT_PRIMARY = 0xFFF2F6F5.toInt()
    const val TEXT_SECONDARY = 0xFFA7B4BC.toInt()
    const val TEXT_MUTED = 0xFF6B7A85.toInt()
    const val TEXT_ON_ACCENT = 0xFF04120C.toInt()

    // ---- Glass tokens ------------------------------------------------------------
    /** Fill of a standard frosted panel. */
    const val PANEL_FILL = 0x14FFFFFF
    /** Fill of a panel that sits on top of another panel. */
    const val PANEL_FILL_INNER = 0x0DFFFFFF
    /** Fill of a panel floating over the live map, which needs more opacity to stay legible. */
    const val PANEL_FILL_OVER_MAP = 0xD20B1017.toInt()
    const val BORDER = 0x24FFFFFF
    const val BORDER_BRIGHT = 0x3DFFFFFF
    const val SHEEN_TOP = 0x1FFFFFFF
    const val SHEEN_BOTTOM = 0x00FFFFFF

    // ---- Radii -------------------------------------------------------------------
    const val R_CARD = 26
    const val R_PANEL = 20
    const val R_INNER = 16
    const val R_CHIP = 999

    fun alpha(color: Int, fraction: Float): Int =
        Color.argb((255 * fraction).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

// =====================================================================================
// Drawables
// =====================================================================================

/** Converts dp to px for this context. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

/** Clamps a radius so [Glass.R_CHIP] behaves as a true pill for any view height. */
private fun Context.radiusPx(radiusDp: Int): Float =
    if (radiusDp >= Glass.R_CHIP) dpf(500f) else dpf(radiusDp.toFloat())

/**
 * The signature frosted panel: translucent fill, diagonal sheen, hairline border.
 *
 * @param fill base colour of the glass, normally a low-alpha white over the dark canvas
 * @param border hairline colour; pass an accent to make the panel read as "active"
 */
fun Context.glass(
    radiusDp: Int = Glass.R_PANEL,
    fill: Int = Glass.PANEL_FILL,
    border: Int = Glass.BORDER,
    sheen: Boolean = true,
): Drawable {
    val r = radiusPx(radiusDp)
    val base = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = r
        setColor(fill)
        setStroke(dp(1).coerceAtLeast(1), border)
    }
    if (!sheen) return base
    val gloss = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Glass.SHEEN_TOP, Glass.SHEEN_BOTTOM, 0x0AFFFFFF),
    ).apply { cornerRadius = r }
    return LayerDrawable(arrayOf(base, gloss)).apply {
        setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
    }
}

/** Solid rounded rectangle, optionally stroked. Used for chips, bars and dividers. */
fun Context.solid(color: Int, radiusDp: Int, border: Int? = null, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx(radiusDp)
        setColor(color)
        if (border != null) setStroke(dp(strokeDp).coerceAtLeast(1), border)
    }

/** Tinted glass chip: accent at low alpha with a matching hairline. */
fun Context.accentChip(accent: Int, radiusDp: Int = Glass.R_CHIP): Drawable =
    glass(radiusDp, fill = Glass.alpha(accent, 0.14f), border = Glass.alpha(accent, 0.42f), sheen = false)

/** Filled accent surface with a vertical gradient, for the one primary action per screen. */
fun Context.accentFill(accent: Int, radiusDp: Int = Glass.R_INNER): Drawable =
    GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Glass.alpha(accent, 1f), Glass.alpha(accent, 0.82f)),
    ).apply {
        cornerRadius = radiusPx(radiusDp)
        setStroke(dp(1), Glass.alpha(Color.WHITE, 0.22f))
    }

/** Wraps a drawable in a ripple so glass surfaces still feel tactile. */
fun Context.pressable(content: Drawable, ripple: Int = Glass.alpha(Color.WHITE, 0.16f)): Drawable =
    RippleDrawable(ColorStateList.valueOf(ripple), content, null)

/**
 * The aurora backdrop: two soft radial glows over the deep canvas, so the frosted panels
 * have coloured light to refract instead of floating on flat black.
 */
fun Context.auroraBackground(
    topGlow: Int = Glass.EMERALD,
    bottomGlow: Int = Glass.CYAN,
): Drawable {
    val canvas = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Glass.BG_BASE, Glass.BG_DEEP, Glass.BG_BASE),
    )
    val top = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(Glass.alpha(topGlow, 0.22f), Glass.alpha(topGlow, 0.06f), Color.TRANSPARENT)
        gradientRadius = dpf(320f)
        setGradientCenter(0.12f, 0.06f)
    }
    val bottom = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(Glass.alpha(bottomGlow, 0.16f), Glass.alpha(bottomGlow, 0.04f), Color.TRANSPARENT)
        gradientRadius = dpf(400f)
        setGradientCenter(0.95f, 0.72f)
    }
    return LayerDrawable(arrayOf(canvas, top, bottom))
}

// =====================================================================================
// View helpers
// =====================================================================================

/**
 * Gives a view a coloured drop shadow. Android only honours the tint from API 28, so on
 * older devices this degrades to the default black elevation shadow.
 */
fun View.glow(color: Int, elevationDp: Float = 14f) {
    elevation = context.dpf(elevationDp)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        outlineSpotShadowColor = Glass.alpha(color, 0.95f)
        outlineAmbientShadowColor = Glass.alpha(color, 0.65f)
    }
}

fun Context.textView(
    value: CharSequence,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
    tracking: Float = 0f,
    lineMultiplier: Float = 1f,
): TextView = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(color)
    typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    includeFontPadding = false
    if (tracking != 0f) letterSpacing = tracking
    if (lineMultiplier != 1f) setLineSpacing(0f, lineMultiplier)
}

/** Small all-caps label used above values and as section eyebrows. */
fun Context.eyebrow(value: String, color: Int = Glass.TEXT_MUTED, sizeSp: Float = 9.5f): TextView =
    textView(value.uppercase(), sizeSp, color, bold = true, tracking = 0.14f)

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** A frosted card with standard padding, ready to receive children. */
fun Context.glassCard(
    radiusDp: Int = Glass.R_CARD,
    padH: Int = 18,
    padV: Int = 18,
    fill: Int = Glass.PANEL_FILL,
    border: Int = Glass.BORDER,
): LinearLayout = column().apply {
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    background = glass(radiusDp, fill, border)
}

fun matchWrap(topMarginDp: Int = 0, context: Context): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(topMarginDp)
    }

/** Equal-width child of a horizontal row. */
fun weightParams(weight: Float = 1f, endMarginPx: Int = 0, startMarginPx: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
        marginEnd = endMarginPx
        marginStart = startMarginPx
    }
