package org.neuronavx.logger.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The common screen frame: an aurora backdrop, a scrolling column of glass panels, and
 * an optional floating pill navigation bar.
 *
 * The bar overlays the scroll content, so the column's bottom padding is derived from the
 * bar's measured height rather than a guessed constant. Guessing is what leaves the last
 * card hidden behind the pill on tall-gesture-inset devices.
 */
class GlassScaffold(
    val shell: FrameLayout,
    val content: LinearLayout,
)

fun Activity.glassScaffold(
    topGlow: Int = Glass.EMERALD,
    bottomGlow: Int = Glass.CYAN,
    navBar: GlassNavBar? = null,
): GlassScaffold {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }

    val shell = FrameLayout(this).apply { background = auroraBackground(topGlow, bottomGlow) }
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(40)) }
    scroll.addView(content)
    shell.addView(scroll, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))

    var systemTop = 0
    var systemBottom = 0

    fun applyPadding() {
        val barSpace = navBar?.let { bar ->
            val height = if (bar.height > 0) bar.height else dp(64)
            height + dp(24)
        } ?: 0
        content.setPadding(dp(18), systemTop + dp(14), dp(18), systemBottom + dp(28) + barSpace)
    }

    if (navBar != null) {
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ).apply { marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(16) }
        shell.addView(navBar, params)
        navBar.addOnLayoutChangeListener { _, _, _, _, _, _, oldTop, _, oldBottom ->
            if (navBar.height != oldBottom - oldTop) applyPadding()
        }
    }

    ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        systemTop = bars.top
        systemBottom = bars.bottom
        navBar?.let { bar ->
            (bar.layoutParams as FrameLayout.LayoutParams).also {
                it.bottomMargin = bars.bottom + dp(12)
                bar.layoutParams = it
            }
        }
        applyPadding()
        insets
    }
    applyPadding()

    return GlassScaffold(shell, content)
}
