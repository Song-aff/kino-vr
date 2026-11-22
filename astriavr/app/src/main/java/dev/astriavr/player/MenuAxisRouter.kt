package dev.astriavr.player

import kotlin.math.abs

/**
 * 手柄「轴」输入的菜单导航路由，与 [NavKeyRouter] 平级、互补：
 *
 * - [NavKeyRouter] 负责 KeyEvent（DPAD / A / B / Menu / 参数键…）
 * - [MenuAxisRouter] 负责 MotionEvent（AXIS_HAT_X/Y 十字键、AXIS_X/Y 左摇杆）
 *
 * 两者最终都产出 [NavAction]，交给宿主翻译成 HomeInput / VrMenuInput，
 * 所以 2D 首页与 VR 菜单在「按键」和「摇杆」两条链路上仍然逐项一致。
 *
 * 存在的理由：廉价蓝牙手柄的十字键常常**不发 KEYCODE_DPAD_*，只发 HAT 轴**，
 * 左摇杆更是纯轴输入。4.11 之前 VR 菜单只处理 KeyEvent，轴事件全部漏进
 * [GamepadInput] 变成「转视角 / 快进 / FOV」，于是戴镜后按方向键菜单不动、
 * 视频反而被快进 —— 观感就是「方向键被 VR 功能拦截」。
 *
 * 行为约定：
 * 1. 只在**方向进入**的边沿触发一次，回到中位才重新武装，避免一个
 *    ACTION_MOVE 的 history 让焦点连跳好几格。
 * 2. 持续按住由宿主定时调用 [repeat] 产生长按连发（首延迟 + 固定间隔），
 *    与 KeyEvent 的 repeatCount 手感一致。
 * 3. 斜向拨动取偏转更大的单轴；十字键 HAT 优先于左摇杆，避免两套输入打架。
 *
 * 纯 Kotlin、零 android.* 依赖，可直接跑 instrumentation 逻辑断言。
 */
class MenuAxisRouter(
    private val deadZone: Float = DEAD_ZONE,
    private val firstRepeatMs: Long = FIRST_REPEAT_MS,
    private val repeatIntervalMs: Long = REPEAT_INTERVAL_MS,
) {
    companion object {
        /** HAT 是离散 -1/0/1；摇杆用 0.5 死区，避开手柄回中漂移。 */
        const val DEAD_ZONE = 0.5f
        /** 长按多久开始连发：略长于系统按键首次重复，避免轻拨误连跳。 */
        const val FIRST_REPEAT_MS = 400L
        /** 连发间隔：菜单列表跳格比播放页快进慢，保证能停住。 */
        const val REPEAT_INTERVAL_MS = 150L
    }

    private var held: NavAction? = null
    private var pressedAt = 0L
    private var lastRepeatAt = 0L

    /** 当前按住的方向；null 表示摇杆/十字键已回中。 */
    fun held(): NavAction? = held

    /**
     * 喂入一次轴采样。返回「方向刚变化」时应触发的边沿动作；
     * 方向未变或回到中位返回 null（中位会重新武装下一次按下）。
     * [nowMs] 用 SystemClock.uptimeMillis() / MotionEvent.eventTime 同一基准。
     */
    fun feed(hatX: Float, hatY: Float, stickX: Float, stickY: Float, nowMs: Long): NavAction? {
        val next = direction(hatX, hatY, stickX, stickY)
        if (next == held) return null
        held = next
        pressedAt = nowMs
        lastRepeatAt = nowMs
        return next
    }

    /** 宿主定时调用：按住超过首延迟后按间隔重复当前方向，否则返回 null。 */
    fun repeat(nowMs: Long): NavAction? {
        val current = held ?: return null
        if (nowMs - pressedAt < firstRepeatMs) return null
        if (nowMs - lastRepeatAt < repeatIntervalMs) return null
        lastRepeatAt = nowMs
        return current
    }

    /** 菜单关闭 / 输入焦点切换时归零，防止残留按住状态带进下一次。 */
    fun clear() {
        held = null
        pressedAt = 0L
        lastRepeatAt = 0L
    }

    private fun direction(hatX: Float, hatY: Float, stickX: Float, stickY: Float): NavAction? {
        // 十字键优先：HAT 在死区外就用 HAT，否则退回左摇杆。
        val activeHatX = if (abs(hatX) >= deadZone) hatX else 0f
        val activeHatY = if (abs(hatY) >= deadZone) hatY else 0f
        val x = if (activeHatX != 0f) activeHatX else stickX
        val y = if (activeHatY != 0f) activeHatY else stickY
        val ax = abs(x)
        val ay = abs(y)
        if (ax < deadZone && ay < deadZone) return null
        // Android 轴约定：X 正 = 右，Y 正 = 下（与 GamepadState.hat 一致）。
        return if (ax >= ay) {
            if (x > 0f) NavAction.RIGHT else NavAction.LEFT
        } else {
            if (y > 0f) NavAction.DOWN else NavAction.UP
        }
    }
}
