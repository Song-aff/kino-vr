package dev.astriavr.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Runs before ordinary playback input. A held selection key cannot leak into video controls. */
class PlaylistInput(private val onMenu: () -> Unit, private val onNavigate: (Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val states = mutableMapOf<Int, PlaylistNavigation>()
    private val menuHeld = mutableSetOf<Pair<Int, Int>>()
    private var suppressUntil = 0L
    private var repeatScheduled = false
    var open = false
        private set
    private val repeat = object : Runnable {
        override fun run() {
            repeatScheduled = false
            if (!open) return
            val now = SystemClock.uptimeMillis()
            for (state in states.values.toList()) fire(state.repeat(now))
            scheduleRepeat()
        }
    }
    fun setOpen(value: Boolean) {
        open = value
        if (!value) suppressUntil = SystemClock.uptimeMillis() + 250
        handler.removeCallbacks(repeat)
        repeatScheduled = false
        // Keep held directions until their releases arrive, even after selecting a video.
        states.entries.removeAll { it.value.isNeutral }
    }
    fun clear() { handler.removeCallbacks(repeat); repeatScheduled = false; states.clear(); menuHeld.clear(); suppressUntil = 0 }
    fun handleKey(event: KeyEvent, allowMenu: Boolean): Boolean {
        val key = event.keyCode
        // 与 2D 首页 / VR 菜单共用 NavKeyRouter：同一物理键在三个菜单场景语义一致。
        // 4.12：换片列表只认 PLAYLIST（Start 键）；MENU（菜单键）交给宿主呼出 VR 菜单，
        // 不再由这里打开视频列表 —— 旧版一个键两用正是「按菜单弹出列表」的根源。
        val nav = NavKeyRouter.resolve(key)
        val menu = nav == NavAction.PLAYLIST
        if (menu && allowMenu) {
            val held = event.deviceId to event.keyCode
            if (event.action == KeyEvent.ACTION_UP || event.isCanceled) menuHeld.remove(held)
            else if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && menuHeld.add(held)) onMenu()
            return true
        }
        val blocked = states[event.deviceId]?.isNeutral == false
        if (!open && !blocked && SystemClock.uptimeMillis() >= suppressUntil) return false
        // 4.14：上下 = 整页翻、左右 = 逐条，确认只认 CONFIRM。
        // 4.9 之前这里把「上」硬编码成确认；4.13 上下做成 ±1 逐条，被用户否掉（「那我不会左右吗」）。
        val action = nav?.let { NavKeyRouter.toPlaylistAction(it) } ?: 0
        if (action != 0) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                val state = states.getOrPut(event.deviceId) { PlaylistNavigation() }
                fire(state.key(action, event.action == KeyEvent.ACTION_DOWN && !event.isCanceled, event.repeatCount > 0, event.eventTime))
                if (!open && state.isNeutral) states.remove(event.deviceId)
                scheduleRepeat()
            }
            return true
        }
        return open && (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.device?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true)
    }
    fun handleMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        if (!open && states[event.deviceId]?.isNeutral != false && SystemClock.uptimeMillis() >= suppressUntil) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { states.remove(event.deviceId); return true }
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true
        val state = states.getOrPut(event.deviceId) { PlaylistNavigation() }
        // After confirmation, keep consuming the rest of this batched motion event.
        // 4.13：十字键 HAT 优先、左摇杆兜底，与 MenuAxisRouter 同规则；
        // 旧版只读 HAT，廉价手柄没有十字键轴时换片列表完全转不动。
        for (i in 0 until event.historySize) fire(state.hat(
            axis(event, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X, i),
            axis(event, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y, i),
            event.getHistoricalEventTime(i)))
        fire(state.hat(axis(event, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X, -1),
            axis(event, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y, -1), event.eventTime))
        if (!open && state.isNeutral) states.remove(event.deviceId)
        scheduleRepeat()
        return true
    }

    /** 单轴采样：HAT 在死区外优先，否则退回左摇杆；history = -1 取当前值。 */
    private fun axis(event: MotionEvent, hatAxis: Int, stickAxis: Int, history: Int): Float {
        val hat = if (history < 0) event.getAxisValue(hatAxis) else event.getHistoricalAxisValue(hatAxis, history)
        if (kotlin.math.abs(hat) >= 0.5f) return hat
        return if (history < 0) event.getAxisValue(stickAxis) else event.getHistoricalAxisValue(stickAxis, history)
    }
    private fun fire(action: Int) { if (open && action != 0) onNavigate(action) }
    private fun scheduleRepeat() {
        if (!open || states.values.none { it.hasRepeat() }) {
            handler.removeCallbacks(repeat); repeatScheduled = false
        } else if (!repeatScheduled) {
            repeatScheduled = true
            handler.postDelayed(repeat, 40)
        }
    }
}
