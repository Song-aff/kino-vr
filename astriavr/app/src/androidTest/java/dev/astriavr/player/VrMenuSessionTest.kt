package dev.astriavr.player

import android.content.Context
import junit.framework.TestCase

/**
 * H4/S4a VR 菜单状态层回归：**纯逻辑，不需要亮屏解锁，不启动 Activity**。
 *
 * 锁住的是「单平面切换」这条路线的关键契约：VrMenu Route 能跨 Intent 往返、
 * 选片原地起播并切 BAR、左右键沿用 HomeController 的板块环绕、
 * B 永远留着回 2D 首页的出口、具名命令正确分发。
 */
class VrMenuSessionTest : TestCase() {

    private fun context(): Context =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context

    private fun entries() = listOf(
        VrMenuEntry("1", "Alpha", "content://alpha.mp4"),
        VrMenuEntry("2", "Beta", "http://192.168.31.254:8787/beta.mp4"))

    fun testVrMenuRouteRoundTrip() {
        val intent = PlayerLauncher.intent(context(), PlayerRoute.VrMenu)
        assertEquals(PlayerRoute.VrMenu, PlayerLauncher.from(intent))
        assertTrue(intent.getBooleanExtra(PlayerLauncher.FROM_HOME, false))
    }

    fun testInitialTabAndLibraryItems() {
        val session = VrMenuSession()
        assertEquals(HomeTab.LIBRARY, session.state.tab)
        assertEquals(VrMenuMode.MENU, session.mode)
        session.setLibrary(entries())
        assertEquals(2, session.state.items.size)
        assertEquals(0, session.state.focus)
    }

    fun testConfirmLaunchesInPlaceAndSwitchesToBar() {
        val session = VrMenuSession()
        session.setLibrary(entries())
        var played: PlayerRoute.Video? = null
        session.onPlay = { played = it }
        session.handle(VrMenuInput.Confirm)
        assertEquals(PlayerRoute.Video("content://alpha.mp4", "Alpha", false), played)
        // 单平面切换：不新建 Activity，同一块银幕从菜单切成视频 + 下沿控制条。
        assertEquals(VrMenuMode.BAR, session.mode)
    }

    fun testLeftRightCycleTabs() {
        val session = VrMenuSession()
        session.handle(VrMenuInput.Right); assertEquals(HomeTab.CONNECT, session.state.tab)
        session.handle(VrMenuInput.Right); assertEquals(HomeTab.SETTINGS, session.state.tab)
        session.handle(VrMenuInput.Right); assertEquals(HomeTab.LIBRARY, session.state.tab)
        session.handle(VrMenuInput.Left); assertEquals(HomeTab.SETTINGS, session.state.tab)
    }

    fun testBackFromBarReturnsToMenuAndSecondBackExits() {
        val session = VrMenuSession()
        session.setLibrary(entries())
        var exits = 0
        session.onExit = { exits++ }
        session.handle(VrMenuInput.Toggle)
        assertEquals(VrMenuMode.BAR, session.mode)
        session.handle(VrMenuInput.Back)
        assertEquals(VrMenuMode.MENU, session.mode)
        assertEquals(0, exits)
        session.handle(VrMenuInput.Back)
        assertEquals(1, exits)
    }

    fun testToggleSwitchesBetweenMenuAndBar() {
        val session = VrMenuSession()
        session.handle(VrMenuInput.Toggle); assertEquals(VrMenuMode.BAR, session.mode)
        session.handle(VrMenuInput.Toggle); assertEquals(VrMenuMode.MENU, session.mode)
    }

    /** 4.12：菜单键 = 收起菜单回播放（宿主决定去哪），与「退出到首页」是两个出口。 */
    fun testCloseMenuIsSeparateFromExit() {
        val session = VrMenuSession()
        var closed = 0
        var exits = 0
        session.onCloseMenu = { closed++ }
        session.onExit = { exits++ }
        session.handle(VrMenuInput.CloseMenu)
        assertEquals(1, closed)
        assertEquals(0, exits)
    }

    /** 4.12：Start 键 = 跳片源库页签并铺满菜单，与首页「Start 直达片源库」一致。 */
    fun testOpenLibraryJumpsToLibraryTab() {
        val session = VrMenuSession()
        session.setLibrary(entries())
        session.handle(VrMenuInput.Right); assertEquals(HomeTab.CONNECT, session.state.tab)
        session.handle(VrMenuInput.Toggle); assertEquals(VrMenuMode.BAR, session.mode)
        session.handle(VrMenuInput.OpenLibrary)
        assertEquals(HomeTab.LIBRARY, session.state.tab)
        assertEquals(VrMenuMode.MENU, session.mode)
    }

    fun testPlaybackCommandDispatch() {
        val session = VrMenuSession()
        val keys = ArrayList<String>()
        session.onCommand = { key, _ -> keys.add(key) }
        session.handle(VrMenuInput.Right)      // 播放
        session.handle(VrMenuInput.Confirm)    // 播放 / 暂停
        assertEquals(VrMenuCommands.PLAY_PAUSE, keys.first())
    }

    fun testViewingCommandDispatch() {
        val session = VrMenuSession()
        val keys = ArrayList<String>()
        session.onCommand = { key, _ -> keys.add(key) }
        session.handle(VrMenuInput.Left)       // 观看参数（环绕）
        session.handle(VrMenuInput.Confirm)    // 4.10：第一项改为「观看模式」
        assertEquals(VrMenuCommands.VIEWING_MODE, keys.first())
    }

    /** 4.10：参数页必须包含戴镜时常改的四项模式参数，且列表末尾永远有返回出口。 */
    fun testViewingTabIncludesModeParametersAndExit() {
        val session = VrMenuSession()
        session.handle(VrMenuInput.Left)       // SETTINGS
        val keys = session.state.items.map { (it.intent as HomeIntent.Command).key }
        assertEquals(VrMenuCommands.VIEWING_MODE, keys[0])
        assertTrue(keys.contains(VrMenuCommands.PROJECTION))
        assertTrue(keys.contains(VrMenuCommands.LAYOUT))
        assertTrue(keys.contains(VrMenuCommands.SWAP_EYES))
        assertTrue(keys.contains(VrMenuCommands.IPD))   // 原有 5 项不能丢
        assertTrue(keys.contains(VrMenuCommands.VERTICAL_ALIGN)) // 4.13：居中/吸底
        assertEquals(VrMenuCommands.BACK_TO_PLAY, keys.last())
    }

    /** 播放中按「参数」键直达参数页，一次 B 回视频，不会被误当成「退出到首页」。 */
    fun testParamsShortcutOpensViewingTabAndBackResumes() {
        val session = VrMenuSession()
        session.setLibrary(entries())
        var resumed = 0
        var exited = 0
        session.onResumePlayback = { resumed++ }
        session.onExit = { exited++ }
        session.handle(VrMenuInput.Confirm)    // 起播 -> BAR
        assertEquals(VrMenuMode.BAR, session.mode)
        session.handle(VrMenuInput.Params)
        assertEquals(VrMenuMode.MENU, session.mode)
        assertEquals(HomeTab.SETTINGS, session.state.tab)
        assertEquals(0, session.state.focus)
        assertTrue(session.paramsOnly)
        assertEquals(AppText.VIEWING_MODE.text(), session.state.items[0].title)
        session.handle(VrMenuInput.Back)
        assertEquals(1, resumed)
        assertEquals(0, exited)
        assertEquals(VrMenuMode.BAR, session.mode)
        assertFalse(session.paramsOnly)
    }

    /** 参数页里左右切板块后回到普通菜单语义，B 不再被解释成「返回播放」。 */
    fun testLeavingParamsPageClearsShortcutState() {
        val session = VrMenuSession()
        var resumed = 0
        session.onResumePlayback = { resumed++ }
        session.handle(VrMenuInput.Params)
        session.handle(VrMenuInput.Right)      // 切板块
        assertFalse(session.paramsOnly)
        session.handle(VrMenuInput.Back)
        assertEquals(0, resumed)
    }

    /** 「返回播放」这条命令和 B 键是同一个出口。 */
    fun testBackToPlayCommandResumes() {
        val session = VrMenuSession()
        var resumed = 0
        session.onResumePlayback = { resumed++ }
        session.handle(VrMenuInput.Params)
        session.handle(VrMenuInput.Down)       // 焦点从观看模式移到下一项
        session.handle(VrMenuInput.Up)         // 回到观看模式
        // 直接走最后一项：先切到 SETTINGS 末尾再确认
        repeat(session.state.items.size - 1) { session.handle(VrMenuInput.Down) }
        session.handle(VrMenuInput.Confirm)
        assertEquals(1, resumed)
    }

    fun testShowMenuCommandReturnsToMenu() {
        val session = VrMenuSession()
        session.setLibrary(entries())
        session.handle(VrMenuInput.Confirm)    // 起播 -> BAR
        session.handle(VrMenuInput.Right)      // 切到「播放」板块
        // playbackItems 的第 6 项（index 5）是「返回 VR 菜单」。
        for (i in 0 until 5) session.handle(VrMenuInput.Down)
        session.handle(VrMenuInput.Confirm)
        assertEquals(VrMenuMode.MENU, session.mode)
    }

    fun testExtrasShortCircuitsToAvoidRepaintLoop() {
        val session = VrMenuSession()
        var changes = 0
        session.onChange = { changes++ }
        val extras = VrMenuExtras(nowPlaying = "Alpha", playing = true, positionMs = 1000)
        session.setExtras(extras)
        val afterFirst = changes
        session.setExtras(extras)
        assertEquals(afterFirst, changes)
    }
}
