package dev.astriavr.player

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Shared visual language for playback, library, settings and controller help. */
object UiStyle {
    // KINO VR 黑橙拼色：中性黑底 + 单一橙强调，实色块而非描边。
    // 界面里橙只用于品牌块、焦点态、主操作、进度指示（约 8~12% 面积），
    // 避免头显暗场内大面积高亮橙破坏暗适应。改主题只需改这一组 token。
    const val BACKGROUND = 0xFF0B0C0E.toInt()
    const val SURFACE = 0xD2191B1F.toInt()
    const val RAISED = 0xDC24272C.toInt()
    const val MODAL = 0xEE111214.toInt()
    const val LINE = 0xFF33363B.toInt()
    const val TEXT = 0xFFF5F6F8.toInt()
    const val MUTED = 0xFF8E929A.toInt()
    const val ACCENT = 0xFFFF7A18.toInt()
    const val DANGER = 0xFFFF4D4F.toInt()
    const val BRAND_INK = 0xFF111214.toInt()
    const val BRAND_DARK = 0xFF191B1F.toInt()
    val easing = PathInterpolator(.2f, 0f, 0f, 1f)
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value + .5f).toInt()
    /** Static translucent surfaces for text-heavy pages; no video sampling behind every card. */
    fun glass(context: Context, color: Int = SURFACE, radius: Int = 16, border: Int = 0x5433363B) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Color.argb(Color.alpha(color), (Color.red(color) + 8).coerceAtMost(255),
                (Color.green(color) + 8).coerceAtMost(255), (Color.blue(color) + 8).coerceAtMost(255)), color)).apply {
            cornerRadius = dp(context, radius).toFloat()
            if (border != 0) setStroke(dp(context, 1), border)
        }
    fun shape(context: Context, color: Int = SURFACE, radius: Int = 16, border: Int = 0): GradientDrawable =
        if (color == SURFACE || color == RAISED) glass(context, color, radius, if (border != 0) border else 0x5433363B)
        else GradientDrawable().apply {
            setColor(color); cornerRadius = dp(context, radius).toFloat()
            if (border != 0) setStroke(dp(context, 1), border)
        }
    fun ripple(context: Context, color: Int = RAISED, radius: Int = 14) = RippleDrawable(
        ColorStateList.valueOf(0x30FF7A18), shape(context, color, radius), shape(context, -1, radius))
    /**
     * 选中态：整块橙实心底色，而不是橙色描边。
     * 头显里 1dp 描边经透镜畸变后容易糊掉，实心块才是可靠的焦点信号。
     * 调用方必须把块内文字切成 [BRAND_INK]，否则白/灰字在橙底上对比度不足。
     */
    fun selected(context: Context, radius: Int = 10) = GradientDrawable().apply {
        setColor(ACCENT); cornerRadius = dp(context, radius).toFloat()
    }
    /** 拼色 wordmark：KINO 深块 + VR 橙块，两段硬切。首页顶栏与播放页左上共用。 */
    fun brand(context: Context, textSizeDp: Float = 12f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val radius = dp(context, 7).toFloat()
        background = GradientDrawable().apply {
            setColor(BRAND_DARK); cornerRadius = radius
            setStroke(dp(context, 1), 0xFF3A3D42.toInt())
        }
        val padV = dp(context, (textSizeDp * 0.38f).toInt().coerceAtLeast(3))
        val padH = dp(context, (textSizeDp * 0.72f).toInt().coerceAtLeast(6))
        fun cell(value: String, color: Int) = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp)
            setTextColor(color)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(padH, padV, padH, padV)
        }
        addView(cell("KINO", TEXT), LinearLayout.LayoutParams(-2, -2))
        addView(cell("VR", BRAND_INK).apply {
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
        }, LinearLayout.LayoutParams(-2, -2))
    }
    fun stars(context: Context, kind: StarfieldDrawable.Kind = StarfieldDrawable.Kind.PAGE, radius: Int = 0) =
        StarfieldDrawable(context, kind, radius)
    fun label(context: Context, value: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(context).apply {
        text = value; setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size); setTextColor(color); includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    fun icon(context: Context, symbol: String, label: String, backgroundColor: Int = RAISED, click: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(PlayerIcon(symbol)); contentDescription = label; tooltipText = label
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        background = ripple(context, backgroundColor)
        setOnClickListener { click() }
    }
    fun duration(ms: Long) = if (ms >= 3600000) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)
        else String.format(java.util.Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60)
    fun motionDuration(ms: Long) = if (ValueAnimator.areAnimatorsEnabled()) ms else 0L
    fun appear(view: View, distance: Float = 10f) {
        view.animate().cancel(); view.alpha = 0f; view.translationY = distance
        view.animate().alpha(1f).translationY(0f).setDuration(motionDuration(220)).setInterpolator(easing).start()
    }
}

