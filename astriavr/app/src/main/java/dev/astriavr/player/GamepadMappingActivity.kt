package dev.astriavr.player

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 4.9 手柄按键配置页。
 *
 * 形态按产品要求：**左边功能、右边键位** 两列，不再有手柄线稿。
 * 点任意一行进入捕获态，按下手柄上的键即完成绑定；同组内重复会自动解绑旧功能。
 * 捕获的是 Android keyCode，不关心手柄品牌、标签印刷或蓝牙协议。
 */
class GamepadMappingActivity : Activity() {
    private val dp = { value: Int -> UiStyle.dp(this, value) }
    private lateinit var rows: LinearLayout
    private lateinit var hint: TextView
    private lateinit var cancelButton: TextView
    private var capturing: BindAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GamepadBindings.reload(this)
        configureWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { background = UiStyle.stars(this@GamepadMappingActivity) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(UiStyle.brand(this), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        toolbar.addView(UiStyle.label(this, AppText.GAMEPAD_MAPPING_TITLE.text(), 15f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        cancelButton = textButton(AppText.GAMEPAD_MAPPING_CANCEL.text()) {
            capturing = null
            refresh()
        }
        toolbar.addView(cancelButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        toolbar.addView(textButton(AppText.GAMEPAD_MAPPING_RESET.text()) {
            GamepadBindings.reset(this)
            capturing = null
            refresh()
            toast(AppText.GAMEPAD_MAPPING_RESET_DONE.text())
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        toolbar.addView(textButton(AppText.GAMEPAD_MAPPING_BACK.text()) { finish() })
        column.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(this).apply {
            addView(rows, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        hint = UiStyle.label(this, "", 11f, UiStyle.MUTED)
        column.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从其它页面返回时重新载入，映射页保存后立刻全局生效。
        GamepadBindings.reload(this)
        refresh()
    }

    private fun textButton(value: String, click: () -> Unit) = UiStyle.label(this, value, 12f, UiStyle.TEXT, true).apply {
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = UiStyle.ripple(this@GamepadMappingActivity, UiStyle.RAISED, 8)
        setOnClickListener { click() }
    }

    private fun refresh() {
        rows.removeAllViews()
        cancelButton.visibility = if (capturing == null) View.GONE else View.VISIBLE
        for (scope in BindScope.values()) {
            val group = if (scope == BindScope.NAV)
                AppText.GAMEPAD_MAPPING_GROUP_NAV.text() else AppText.GAMEPAD_MAPPING_GROUP_PLAYER.text()
            rows.addView(UiStyle.label(this, group, 12f, UiStyle.ACCENT, true).apply {
                setPadding(0, dp(10), 0, dp(5))
            }, LinearLayout.LayoutParams(-1, -2))
            for (action in BindAction.values().filter { it.scope == scope }) {
                rows.addView(buildRow(action), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }
        hint.text = if (capturing != null) AppText.GAMEPAD_MAPPING_CAPTURING.text()
        else AppText.GAMEPAD_MAPPING_HINT.text() + " · " + AppText.GAMEPAD_MAPPING_AXIS_HINT.text()
    }

    private fun buildRow(action: BindAction): LinearLayout {
        val active = capturing == action
        val ink = if (active) UiStyle.BRAND_INK else UiStyle.TEXT
        val subInk = if (active) UiStyle.BRAND_INK else UiStyle.MUTED
        val code = GamepadBindings.keyFor(action)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = if (active) UiStyle.selected(this@GamepadMappingActivity, 10)
            else UiStyle.ripple(this@GamepadMappingActivity, UiStyle.SURFACE, 10)
            setOnClickListener {
                capturing = if (capturing == action) null else action
                refresh()
            }
        }
        row.addView(UiStyle.label(this, action.title(), 14f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(UiStyle.label(this,
            if (active) AppText.GAMEPAD_MAPPING_PRESS.text() else GamepadBindings.keyLabel(code),
            16f, ink, true).apply { gravity = Gravity.END })
        if (!active && code != GamepadBindings.NO_KEY) {
            right.addView(UiStyle.label(this, GamepadBindings.keyDetail(code), 10f, subInk).apply {
                gravity = Gravity.END
            })
        }
        row.addView(right, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    /** 捕获态吃掉所有按键：ACTION_DOWN 绑定，ACTION_UP 只消费不重复触发。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = capturing ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val code = event.keyCode
        if (GamepadBindings.isSystemKey(code)) return true
        val result = GamepadBindings.bind(this, target, code)
        capturing = null
        refresh()
        val message = result.replaced?.let { AppText.GAMEPAD_MAPPING_CONFLICT.text(it.title()) }
            ?: AppText.GAMEPAD_MAPPING_BOUND.text(target.title(), GamepadBindings.keyLabel(code))
        toast(message)
        return true
    }

    /** 摇杆 / HAT 轴暂不参与绑定；明确告诉用户原因，避免误以为按键没被识别。 */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (capturing != null && (event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                event.isFromSource(InputDevice.SOURCE_GAMEPAD))) {
            hint.text = AppText.GAMEPAD_MAPPING_AXIS_HINT.text()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun configureWindow() {
        val decor = window.decorView
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            decor.windowInsetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
