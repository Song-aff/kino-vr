package dev.astriavr.player

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/** Isolated double-click target: never shares a click sequence with the video underneath. */
class StopLogoView(context: Context) : FrameLayout(context) {
    var onStop: () -> Unit = {}
    private val config = ViewConfiguration.get(context)
    private val taps = PlaybackTapSequence(PlaybackTapSequence.DOUBLE_TAP_MS, config.scaledDoubleTapSlop.toFloat())
    private var candidate = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    init {
        // 播放页左上角的品牌标与首页共用同一个拼色 wordmark（黑 KINO / 橙 VR）。
        addView(UiStyle.brand(context, 16f), FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        contentDescription = AppText.ASTRIAVR_DOUBLE_TAP_TO_STOP_THE.text()
        isFocusable = true; isClickable = true
    }
    fun cancelPending() { candidate = false; taps.cancel() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                candidate = true; downX = event.x; downY = event.y; downAt = now
                taps.down(now, width / 2f, 0f, width.toFloat())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX; val dy = event.y - downY
                if (dx * dx + dy * dy > config.scaledTouchSlop.toFloat() * config.scaledTouchSlop) cancelPending()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX; val dy = event.y - downY
                if (candidate && now - downAt <= ViewConfiguration.getLongPressTimeout() &&
                    dx * dx + dy * dy <= config.scaledTouchSlop.toFloat() * config.scaledTouchSlop &&
                    event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    if (taps.up(now) == PlaybackTapSequence.Action.TOGGLE) performClick()
                } else cancelPending()
                candidate = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelPending()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); onStop(); return true }
    override fun onDetachedFromWindow() { cancelPending(); super.onDetachedFromWindow() }
}
