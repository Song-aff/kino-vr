package dev.astriavr.player

import android.content.Context
import android.view.KeyEvent
import java.util.EnumMap

/**
 * 手柄功能的作用域。
 * 同组内一个物理键只能绑一个功能；跨组允许复用，因为导航/菜单与播放页是互斥场景
 * （例如 A 键默认既是「确认」也是「视角回正」，旧版硬编码就是这么复用 A 的）。
 */
enum class BindScope { NAV, PLAYER }

/**
 * 可配置的手柄功能。defaultKey 与 4.8 之前的硬编码逐项对齐，
 * 因此「恢复默认」精准回到用户熟悉的旧行为，不产生任何行为漂移。
 */
enum class BindAction(
    val scope: BindScope,
    val defaultKey: Int,
    val titleZh: String,
    val titleEn: String
) {
    UP(BindScope.NAV, KeyEvent.KEYCODE_DPAD_UP, "上 / 视场角 −", "Up / FOV −"),
    DOWN(BindScope.NAV, KeyEvent.KEYCODE_DPAD_DOWN, "下 / 视场角 ＋", "Down / FOV +"),
    LEFT(BindScope.NAV, KeyEvent.KEYCODE_DPAD_LEFT, "左 / 快退", "Left / seek back"),
    RIGHT(BindScope.NAV, KeyEvent.KEYCODE_DPAD_RIGHT, "右 / 快进", "Right / seek forward"),
    CONFIRM(BindScope.NAV, KeyEvent.KEYCODE_BUTTON_A, "确认", "Confirm"),
    BACK(BindScope.NAV, KeyEvent.KEYCODE_BUTTON_B, "返回（长按退出）", "Back (hold to exit)"),
    // 4.12：菜单键与 Start 键职责分开。MENU 只负责呼出/收起 VR 菜单，
    // PLAYLIST（Start）只负责唤出视频列表。旧版一个 BUTTON_START 同时干两件事，
    // 导致「按菜单弹出的是播放列表」。两者都是可配置键，戴镜内不改映射页也能用。
    MENU(BindScope.NAV, KeyEvent.KEYCODE_MENU, "VR 菜单（呼出/收起）", "VR menu (open/close)"),
    PLAYLIST(BindScope.NAV, KeyEvent.KEYCODE_BUTTON_START, "视频列表（换片）", "Video list (playlist)"),
    TOGGLE(BindScope.NAV, KeyEvent.KEYCODE_BUTTON_Y, "切换菜单与控制条", "Toggle menu / bar"),
    EXIT(BindScope.NAV, KeyEvent.KEYCODE_BUTTON_SELECT, "退出到 2D 首页", "Exit to 2D home"),
    // 4.10：VR 视频中一键呼出「观看参数」。默认 R2 是唯一在两组里都没被占用的键，
    // 不会抢走播放页的 IPD/倍速/回正；廉价手柄若没有 R2，可在配置页改绑任意键。
    PARAMS(BindScope.NAV, KeyEvent.KEYCODE_BUTTON_R2, "参数 / 模式（播放中呼出）", "Params / mode (in video)"),

    PLAY_PAUSE(BindScope.PLAYER, KeyEvent.KEYCODE_BUTTON_L2, "播放 / 暂停", "Play / pause"),
    RECENTER(BindScope.PLAYER, KeyEvent.KEYCODE_BUTTON_A, "视角回正", "Recenter"),
    GYRO(BindScope.PLAYER, KeyEvent.KEYCODE_BUTTON_B, "陀螺仪开关", "Gyroscope"),
    IPD_DOWN(BindScope.PLAYER, KeyEvent.KEYCODE_BUTTON_X, "瞳距 −", "IPD −"),
    IPD_UP(BindScope.PLAYER, KeyEvent.KEYCODE_BUTTON_Y, "瞳距 ＋", "IPD +"),
    SPEED_DOWN(BindScope.PLAYER, KeyEvent.KEYCODE_BUTTON_L1, "倍速 −", "Speed −"),
    SPEED_UP(BindScope.PLAYER, KeyEvent.KEYCODE_BUTTON_R1, "倍速 ＋", "Speed +");

    fun title(): String = if (AppText.isEnglish()) titleEn else titleZh
}

data class BindResult(val action: BindAction, val keyCode: Int, val replaced: BindAction?)

/**
 * 全局手柄映射表：取代散落在 GamepadInput / PlaylistInput / HomeActivity /
 * MainActivity 四处硬编码的 KEYCODE_*。
 *
 * 设计边界：
 * - 只存本机 SharedPreferences，不联网、不写日志、不进任何接口响应；
 * - 捕获的是 Android keyCode，不依赖手柄厂商、不需要蓝牙权限或配对 SDK；
 * - 摇杆轴 / HAT 十字键仍是固定行为（见 README 已知限制），本表只管按键；
 * - reload 由各 Activity 的 onResume 调用，改完返回即生效，无需重启。
 */
object GamepadBindings {
    const val NO_KEY = -1
    private const val PREFS = "gamepad_bindings"
    private val keys = EnumMap<BindAction, Int>(BindAction::class.java)

    fun reload(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(keys) {
            keys.clear()
            for (action in BindAction.values()) keys[action] = prefs.getInt(action.name, action.defaultKey)
        }
    }

    fun keyFor(action: BindAction): Int = synchronized(keys) { keys[action] ?: action.defaultKey }

    /** 反查：某个物理键在同一作用域里代表哪个功能。 */
    fun actionFor(keyCode: Int, scope: BindScope): BindAction? =
        if (keyCode <= 0) null
        else BindAction.values().firstOrNull { it.scope == scope && keyFor(it) == keyCode }

    /**
     * 绑定一个键。同组内若已有别的功能占着这个键，先把旧功能置为「未绑定」，
     * 避免一键同时触发两个动作；跨组不处理（场景互斥，旧行为就依赖这种复用）。
     */
    fun bind(context: Context, action: BindAction, keyCode: Int): BindResult {
        val replaced = BindAction.values().firstOrNull {
            it !== action && it.scope == action.scope && keyFor(it) == keyCode
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        editor.putInt(action.name, keyCode)
        replaced?.let { editor.putInt(it.name, NO_KEY) }
        editor.apply()
        synchronized(keys) {
            keys[action] = keyCode
            replaced?.let { keys[it] = NO_KEY }
        }
        return BindResult(action, keyCode, replaced)
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        synchronized(keys) { keys.clear() }
        reload(context)
    }

    /**
     * 键位名用符号，避免中英文差异；未知键退回 keyCode 数字，方便对着手柄逐个试。
     *
     * 廉价蓝牙手柄 / VR 遥控器常常不发 BUTTON_A 这类游戏键，而是发媒体键
     * （85=播放暂停、87=下一曲、88=上一曲）或直接发字母键。这类键**必须可绑定**，
     * 所以不能过滤；这里给它们可读的名字，否则界面上只会显示 `#85` 之类数字。
     */
    fun keyLabel(code: Int): String {
        if (code == NO_KEY) return "—"
        // KEYCODE_0..9 与 KEYCODE_A..Z 在 AOSP 中是连续区间。
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return (code - KeyEvent.KEYCODE_0).toString()
        if (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)
            return ('A'.code + (code - KeyEvent.KEYCODE_A)).toChar().toString()
        return when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "▶/⏸"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
            KeyEvent.KEYCODE_MEDIA_STOP -> "Stop"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "⏭"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "⏮"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "◀◀"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "▶▶"
            KeyEvent.KEYCODE_MEDIA_RECORD -> "Rec"
            KeyEvent.KEYCODE_MEDIA_EJECT -> "Eject"
            KeyEvent.KEYCODE_HEADSETHOOK -> "Hook"
            KeyEvent.KEYCODE_MUTE -> "Mute"
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_C -> "C"
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_Z -> "Z"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            KeyEvent.KEYCODE_BUTTON_START -> "Start"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            KeyEvent.KEYCODE_BUTTON_MODE -> "Mode"
            KeyEvent.KEYCODE_DPAD_UP -> "↑"
            KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
            KeyEvent.KEYCODE_DPAD_LEFT -> "←"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
            KeyEvent.KEYCODE_DPAD_CENTER -> "OK"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_BACK -> "Back"
            KeyEvent.KEYCODE_MENU -> "Menu"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_SPACE -> "Space"
            else -> "#$code"
        }
    }

    /** 副标题显示原始 keyCode，方便确认手柄实际发出的键码。 */
    fun keyDetail(code: Int): String = if (code == NO_KEY) "" else "keyCode $code"

    /** 系统键不参与绑定，避免把音量/电源键抢走导致系统无法操作。 */
    fun isSystemKey(code: Int): Boolean = code in setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL
    )
}
