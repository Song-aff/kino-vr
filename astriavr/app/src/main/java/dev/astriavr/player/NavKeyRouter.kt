package dev.astriavr.player

import android.view.KeyEvent

/**
 * 菜单导航的**唯一事实来源**：物理按键 → 语义动作。
 *
 * 4.9 之前，[HomeActivity]（2D 首页）、[MainActivity]（VR 菜单）、
 * [PlaylistInput]（换片列表）、[GamepadInput]（播放页）各写了一份 `when (keyCode)`，
 * 于是同一个键在内外经常不是同一个动作：2D 首页没有「返回 / 菜单 / 退出」，
 * 换片列表把「上」当确认，VR 菜单的抬手事件还会漏进播放页手柄层。
 *
 * 现在三处菜单场景都先经过 [resolve]，再各自把 [NavAction] 翻译成宿主输入。
 * 契约层 [HomeController] 本来就是共用的一份，这样从**按键到状态机**整条链路
 * 内外完全一致；改一次「手柄按键」映射表，2D 首页与 VR 菜单同时生效。
 *
 * 边界：本表只管菜单导航按键。播放页专属的播放/暂停、视角回正、瞳距、倍速
 * 仍由 [GamepadInput] 的 PLAYER 组处理；摇杆 / HAT 轴事件不经过这里（见 README）。
 */
enum class NavAction { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK, MENU, TOGGLE, EXIT, PARAMS, PLAYLIST }

object NavKeyRouter {

    /** 是否允许长按连续触发（只有方向键需要，避免长按返回键一路退出）。 */
    fun isRepeatable(action: NavAction): Boolean = when (action) {
        NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> true
        else -> false
    }

    /**
     * 物理键 → 导航语义。自定义 NAV 绑定优先，随后是系统 / 键盘兜底。
     * 兜底键**永远保留**：即使映射表被改乱，DPAD_CENTER/ENTER 仍能确认，
     * BACK/ESC 仍能返回，SELECT 仍能退出头显，不会把人卡在 VR 里。
     */
    fun resolve(keyCode: Int): NavAction? {
        GamepadBindings.actionFor(keyCode, BindScope.NAV)?.let { bound ->
            navOf(bound)?.let { return it }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> NavAction.CONFIRM
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> NavAction.BACK
            KeyEvent.KEYCODE_MENU -> NavAction.MENU
            KeyEvent.KEYCODE_BUTTON_SELECT -> NavAction.EXIT
            else -> null
        }
    }

    private fun navOf(action: BindAction): NavAction? = when (action) {
        BindAction.UP -> NavAction.UP
        BindAction.DOWN -> NavAction.DOWN
        BindAction.LEFT -> NavAction.LEFT
        BindAction.RIGHT -> NavAction.RIGHT
        BindAction.CONFIRM -> NavAction.CONFIRM
        BindAction.BACK -> NavAction.BACK
        BindAction.MENU -> NavAction.MENU
        BindAction.TOGGLE -> NavAction.TOGGLE
        BindAction.EXIT -> NavAction.EXIT
        BindAction.PARAMS -> NavAction.PARAMS
        BindAction.PLAYLIST -> NavAction.PLAYLIST
        else -> null // PLAYER 组不属于菜单导航
    }

    /**
     * 2D 首页的语义：上下移焦点、左右切板块、确认、返回。
     * MENU / TOGGLE / EXIT 返回 null —— 它们需要宿主执行「进入 VR」「退出」等
     * 平台动作，由 HomeActivity 单独处理（不塞进纯状态机）。
     * PARAMS 是纯状态动作：直接落到「设置」板块，与 VR 里的参数页同一处入口。
     */
    fun toHomeInput(action: NavAction): HomeInput? = when (action) {
        NavAction.UP -> HomeInput.Navigate(HomeDirection.UP)
        NavAction.DOWN -> HomeInput.Navigate(HomeDirection.DOWN)
        NavAction.LEFT -> HomeInput.Navigate(HomeDirection.LEFT)
        NavAction.RIGHT -> HomeInput.Navigate(HomeDirection.RIGHT)
        NavAction.CONFIRM -> HomeInput.Confirm
        NavAction.BACK -> HomeInput.Back
        NavAction.PARAMS -> HomeInput.SelectTab(HomeTab.SETTINGS)
        // 4.12：Start 键在首页 = 直接落到片源库（视频列表），与播放态「Start 唤列表」同义。
        NavAction.PLAYLIST -> HomeInput.SelectTab(HomeTab.LIBRARY)
        else -> null
    }

    /**
     * VR 菜单的语义：与 2D 首页逐项对应，额外把「菜单 / 切换」映射成控制条开合，
     * 「退出」映射成回 2D 首页；返回键长按仍是戴镜时的紧急出口。
     */
    fun toVrMenuInput(action: NavAction, longPress: Boolean): VrMenuInput = when (action) {
        NavAction.UP -> VrMenuInput.Up
        NavAction.DOWN -> VrMenuInput.Down
        NavAction.LEFT -> VrMenuInput.Left
        NavAction.RIGHT -> VrMenuInput.Right
        NavAction.CONFIRM -> VrMenuInput.Confirm
        NavAction.BACK -> if (longPress) VrMenuInput.Exit else VrMenuInput.Back
        // 4.12：MENU 键在菜单态 = 收起菜单；TOGGLE（Y）仍是开合下沿控制条。
        // 从播放态「唤起菜单」由 MainActivity 的 dispatchKeyEvent 在同一 MENU 语义上处理。
        NavAction.MENU -> VrMenuInput.CloseMenu
        NavAction.TOGGLE -> VrMenuInput.Toggle
        NavAction.EXIT -> VrMenuInput.Exit
        NavAction.PARAMS -> VrMenuInput.Params
        NavAction.PLAYLIST -> VrMenuInput.OpenLibrary
    }

    /**
     * 换片列表的语义：左右 = 逐条切换，上下 = 整页翻（一页 = 可视 5 张），
     * 确认只认 CONFIRM。4.14 前上下也映射 ±1 逐条，被用户否掉（「那我不会左右吗」）；
     * 4.9 前「上」还被硬编码成确认。
     */
    fun toPlaylistAction(action: NavAction): Int? = when (action) {
        NavAction.UP -> PlaylistNavigation.PREV_PAGE
        NavAction.DOWN -> PlaylistNavigation.NEXT_PAGE
        NavAction.LEFT -> PlaylistNavigation.LEFT
        NavAction.RIGHT -> PlaylistNavigation.RIGHT
        NavAction.CONFIRM -> PlaylistNavigation.CONFIRM
        else -> null
    }
}
