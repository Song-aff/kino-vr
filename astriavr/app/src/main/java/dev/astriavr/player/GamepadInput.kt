package dev.astriavr.player

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.max

/** Android's Xbox-compatible standard mapping; no pairing SDK or Bluetooth permissions needed. */
class GamepadInput(
    context: Context,
    private val onAction: (GamepadState.Action, Boolean) -> Unit,
    private val onLook: (Float, Float) -> Unit,
    private val onAdjust: (Int, Float, Float) -> Unit,
    private val getSpeedIndex: () -> Int,
    private val onConnectionChanged: () -> Unit,
) : InputManager.InputDeviceListener, Choreographer.FrameCallback {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val choreographer = Choreographer.getInstance()
    private val states = mutableMapOf<Int, GamepadState>()
    private val actions = GamepadState.Action.values()
    private var active = false
    private var stickDevice: Int? = null
    private var scheduled = false
    private var previousFrame = 0L

    fun hasConnectedController(): Boolean = inputManager.inputDeviceIds.any { id ->
        inputManager.getInputDevice(id)?.let {
            it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK)
        } == true
    }

    fun start() {
        if (active) return
        active = true
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        if (!active) { clearInput(); return }
        active = false
        inputManager.unregisterInputDeviceListener(this)
        clearInput()
    }

    fun clearInput() {
        states.clear()
        stickDevice = null
        choreographer.removeFrameCallback(this)
        scheduled = false
        previousFrame = 0
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!active || !isController(event)) return false
        val key = event.keyCode
        // PLAY_PAUSE 由数字 LT 与模拟 LT 轴合并驱动，键码可配置但轴逻辑保持固定。
        if (key == GamepadBindings.keyFor(BindAction.PLAY_PAUSE)) {
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                val state = states.getOrPut(event.deviceId) { GamepadState() }
                fire(state.leftTriggerKey(event.action == KeyEvent.ACTION_DOWN && !event.isCanceled, event.repeatCount > 0))
            }
            return true
        }
        if (key == KeyEvent.KEYCODE_BUTTON_THUMBL) return true
        val action = playerAction(key) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            val state = states.getOrPut(event.deviceId) { GamepadState() }
            val down = event.action == KeyEvent.ACTION_DOWN && !event.isCanceled
            fire(if (action == GamepadState.Action.SPEED_DOWN || action == GamepadState.Action.SPEED_UP)
                state.speedKey(action, down, event.repeatCount > 0, getSpeedIndex(), event.eventTime)
            else state.key(action, down, event.repeatCount > 0))
            scheduleFrame()
        }
        // Consume both down and up, so A/B and D-pad cannot also operate focused Android widgets.
        return true
    }

    fun handleMotion(event: MotionEvent): Boolean {
        if (!active || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { clearInput(); return true }
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val xRange = device.getMotionRange(MotionEvent.AXIS_X, event.source)
        val yRange = device.getMotionRange(MotionEvent.AXIS_Y, event.source)
        val hatRange = device.getMotionRange(MotionEvent.AXIS_HAT_X, event.source)
        val hatYRange = device.getMotionRange(MotionEvent.AXIS_HAT_Y, event.source)
        val rightAxisX = if (device.getMotionRange(MotionEvent.AXIS_Z, event.source) != null) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
        val rightAxisY = if (device.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
        val rightRangeX = device.getMotionRange(rightAxisX, event.source)
        val rightRangeY = device.getMotionRange(rightAxisY, event.source)
        val triggerRange = device.getMotionRange(MotionEvent.AXIS_LTRIGGER, event.source)
        val brakeRange = device.getMotionRange(MotionEvent.AXIS_BRAKE, event.source)
        if (xRange == null && yRange == null && hatRange == null && hatYRange == null && rightRangeX == null && rightRangeY == null && triggerRange == null && brakeRange == null) return false
        val state = states.getOrPut(event.deviceId) { GamepadState() }
        if (triggerRange != null || brakeRange != null) {
            fun triggerValue(axis: Int, range: InputDevice.MotionRange?, history: Int): Float {
                if (range == null || range.range <= 0f) return 0f
                val value = if (history < 0) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, history)
                return ((value - range.min) / range.range).coerceIn(0f, 1f)
            }
            fun triggerAt(history: Int) = max(triggerValue(MotionEvent.AXIS_LTRIGGER, triggerRange, history),
                triggerValue(MotionEvent.AXIS_BRAKE, brakeRange, history))
            for (i in 0 until event.historySize) fire(state.leftTriggerAxis(triggerAt(i)))
            fire(state.leftTriggerAxis(triggerAt(-1)))
        }
        // Process hat history to retain quick press/release transitions in batched events.
        if (hatRange != null || hatYRange != null) {
            for (i in 0 until event.historySize) fire(state.hat(event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, i), event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, i)))
            fire(state.hat(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y)))
            scheduleFrame()
        }
        if (xRange != null || yRange != null || rightRangeX != null || rightRangeY != null) {
            state.stick(
                if (xRange != null) event.getAxisValue(MotionEvent.AXIS_X) else 0f,
                if (yRange != null) event.getAxisValue(MotionEvent.AXIS_Y) else 0f,
                max(xRange?.flat ?: 0f, yRange?.flat ?: 0f))
            state.rightStick(if (rightRangeX != null) event.getAxisValue(rightAxisX) else 0f,
                if (rightRangeY != null) event.getAxisValue(rightAxisY) else 0f,
                max(rightRangeX?.flat ?: 0f, rightRangeY?.flat ?: 0f))
            if (state.isMoving()) stickDevice = event.deviceId
            else if (stickDevice == event.deviceId) stickDevice = null
            scheduleFrame()
        }
        return true
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!active) return
        val seconds = ((frameTimeNanos - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
        previousFrame = frameTimeNanos
        for (pad in states.values) fire(pad.repeatFov(seconds) or pad.repeatSeek(seconds) or pad.repeatTuning(seconds), false)
        val state = stickDevice?.let(states::get)
        // Rate control: full deflection = 90 degrees/second, independent of event or refresh rate.
        if (state != null && (state.stickX != 0f || state.stickY != 0f))
            onLook(state.stickX * 90f * seconds, -state.stickY * 90f * seconds)
        val volumeSteps = state?.volumeSteps(seconds) ?: 0
        if (state != null && (volumeSteps != 0 || state.rightX != 0f))
            onAdjust(volumeSteps, state.rightX, seconds)
        scheduleFrame(false)
    }

    private fun scheduleFrame(resetClock: Boolean = true) {
        if (!active || scheduled || (stickDevice == null && states.values.none { it.hasFovRepeat() || it.hasSeekRepeat() || it.hasTuningRepeat() })) return
        if (resetClock) previousFrame = System.nanoTime()
        scheduled = true
        choreographer.postFrameCallback(this)
    }

    /**
     * 把物理键码翻译成播放页动作。
     * NAV 组的四个方向键在播放页是快退/快进/FOV（与首页、VR 菜单共用同一组键），
     * PLAYER 组是播放页专属调参；同一键跨组复用时 PLAYER 优先（旧行为 A=视角回正）。
     */
    private fun playerAction(key: Int): GamepadState.Action? {
        val directional = when (GamepadBindings.actionFor(key, BindScope.NAV)) {
            BindAction.LEFT -> GamepadState.Action.SEEK_BACK
            BindAction.RIGHT -> GamepadState.Action.SEEK_FORWARD
            BindAction.UP -> GamepadState.Action.FOV_DOWN
            BindAction.DOWN -> GamepadState.Action.FOV_UP
            else -> null
        }
        val tuned = when (GamepadBindings.actionFor(key, BindScope.PLAYER)) {
            BindAction.RECENTER -> GamepadState.Action.RECENTER
            BindAction.GYRO -> GamepadState.Action.TOGGLE_GYRO
            BindAction.IPD_DOWN -> GamepadState.Action.IPD_DOWN
            BindAction.IPD_UP -> GamepadState.Action.IPD_UP
            BindAction.SPEED_DOWN -> GamepadState.Action.SPEED_DOWN
            BindAction.SPEED_UP -> GamepadState.Action.SPEED_UP
            else -> null
        }
        return tuned ?: directional
    }

    private fun isController(event: KeyEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.device?.let { it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK) } == true

    private fun fire(mask: Int, freshPress: Boolean = true) {
        if (mask == 0) return
        for (action in actions) if (mask and (1 shl action.ordinal) != 0) onAction(action, freshPress)
    }

    override fun onInputDeviceAdded(deviceId: Int) { onConnectionChanged() }
    override fun onInputDeviceChanged(deviceId: Int) { onInputDeviceRemoved(deviceId) }
    override fun onInputDeviceRemoved(deviceId: Int) {
        states.remove(deviceId)
        if (stickDevice == deviceId) {
            stickDevice = null
        }
        onConnectionChanged()
    }
}
