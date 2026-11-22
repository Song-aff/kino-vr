package dev.astriavr.player

import android.content.Context
import android.view.KeyEvent
import junit.framework.TestCase

/**
 * 4.9 修订 2：锁住「VR 内部的操作与外部一致」这条契约。
 *
 * 2D 首页、VR 菜单、换片列表都必须先把物理键交给同一个 [NavKeyRouter.resolve]，
 * 再翻译成各自的宿主输入；改一次映射表，内外同时生效。测试全程纯逻辑，
 * 不需要亮屏、不启动 Activity，也不会留下用户配置（setUp / tearDown 都 reset）。
 */
class NavKeyRouterTest : TestCase() {

    private fun context(): Context =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context

    override fun setUp() {
        super.setUp()
        GamepadBindings.reset(context())
    }

    override fun tearDown() {
        GamepadBindings.reset(context())
        super.tearDown()
    }

    /** 默认映射下，六个核心导航键解析出的语义与 4.8 之前的硬编码一致。 */
    fun testDefaultCoreKeysResolve() {
        assertEquals(NavAction.UP, NavKeyRouter.resolve(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(NavAction.DOWN, NavKeyRouter.resolve(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(NavAction.LEFT, NavKeyRouter.resolve(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(NavAction.RIGHT, NavKeyRouter.resolve(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(NavAction.CONFIRM, NavKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(NavAction.BACK, NavKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_B))
    }

    /** 同一物理键在 2D 首页与 VR 菜单里必须落到同一语义，逐键对齐。 */
    fun testHomeAndVrMenusAgreeOnEveryCoreKey() {
        val expectedHome = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to HomeInput.Navigate(HomeDirection.UP),
            KeyEvent.KEYCODE_DPAD_DOWN to HomeInput.Navigate(HomeDirection.DOWN),
            KeyEvent.KEYCODE_DPAD_LEFT to HomeInput.Navigate(HomeDirection.LEFT),
            KeyEvent.KEYCODE_DPAD_RIGHT to HomeInput.Navigate(HomeDirection.RIGHT),
            KeyEvent.KEYCODE_BUTTON_A to HomeInput.Confirm,
            KeyEvent.KEYCODE_BUTTON_B to HomeInput.Back
        )
        val expectedVr = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to VrMenuInput.Up,
            KeyEvent.KEYCODE_DPAD_DOWN to VrMenuInput.Down,
            KeyEvent.KEYCODE_DPAD_LEFT to VrMenuInput.Left,
            KeyEvent.KEYCODE_DPAD_RIGHT to VrMenuInput.Right,
            KeyEvent.KEYCODE_BUTTON_A to VrMenuInput.Confirm,
            KeyEvent.KEYCODE_BUTTON_B to VrMenuInput.Back
        )
        for ((key, home) in expectedHome) {
            val action = NavKeyRouter.resolve(key)
            assertNotNull("keyCode $key 未解析出导航语义", action)
            assertEquals("keyCode $key 在 2D 首页的语义", home, NavKeyRouter.toHomeInput(action!!))
            assertEquals("keyCode $key 在 VR 菜单的语义", expectedVr[key],
                NavKeyRouter.toVrMenuInput(action, false))
        }
    }

    /** 系统 / 键盘兜底：映射被改乱也永远留着确认、返回、退出出口。 */
    fun testSystemFallbackKeys() {
        assertEquals(NavAction.CONFIRM, NavKeyRouter.resolve(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(NavAction.CONFIRM, NavKeyRouter.resolve(KeyEvent.KEYCODE_ENTER))
        assertEquals(NavAction.BACK, NavKeyRouter.resolve(KeyEvent.KEYCODE_BACK))
        assertEquals(NavAction.BACK, NavKeyRouter.resolve(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(NavAction.MENU, NavKeyRouter.resolve(KeyEvent.KEYCODE_MENU))
        assertEquals(NavAction.EXIT, NavKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    /** 4.12：菜单键与 Start 键必须是两个独立语义，不再一个键两用。 */
    fun testMenuAndStartAreDistinctKeys() {
        assertEquals(NavAction.MENU, NavKeyRouter.resolve(KeyEvent.KEYCODE_MENU))
        assertEquals(NavAction.PLAYLIST, NavKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_START))
        // 首页：菜单键进 VR 操控层（交给宿主），Start 键直达片源库。
        assertNull(NavKeyRouter.toHomeInput(NavAction.MENU))
        assertEquals(HomeInput.SelectTab(HomeTab.LIBRARY),
            NavKeyRouter.toHomeInput(NavAction.PLAYLIST))
        // VR 菜单态：菜单键收起菜单，Start 键跳片源库。
        assertEquals(VrMenuInput.CloseMenu, NavKeyRouter.toVrMenuInput(NavAction.MENU, false))
        assertEquals(VrMenuInput.OpenLibrary, NavKeyRouter.toVrMenuInput(NavAction.PLAYLIST, false))
        assertFalse(NavKeyRouter.isRepeatable(NavAction.MENU))
        assertFalse(NavKeyRouter.isRepeatable(NavAction.PLAYLIST))
    }

    /** 自定义绑定后，2D 首页与 VR 菜单必须同步跟着变（这正是「不好用的手柄」要的）。 */
    fun testCustomBindingAppliesToBothHomeAndVr() {
        val mediaPlay = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        GamepadBindings.bind(context(), BindAction.CONFIRM, mediaPlay)
        assertEquals(NavAction.CONFIRM, NavKeyRouter.resolve(mediaPlay))
        assertEquals(HomeInput.Confirm, NavKeyRouter.toHomeInput(NavAction.CONFIRM))
        assertEquals(VrMenuInput.Confirm, NavKeyRouter.toVrMenuInput(NavAction.CONFIRM, false))
        // 旧键不再被当成确认；PLAYER 组的复用不应泄漏进菜单导航。
        assertNull(NavKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_A))
    }

    /** 换片列表：左右逐条、上下整页翻，确认只认 CONFIRM（4.14；旧版把「上」当确认）。 */
    fun testPlaylistUsesSameDirectionSemantics() {
        fun playlist(key: Int) = NavKeyRouter.toPlaylistAction(NavKeyRouter.resolve(key)!!)
        assertEquals(PlaylistNavigation.PREV_PAGE, playlist(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PlaylistNavigation.NEXT_PAGE, playlist(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PlaylistNavigation.LEFT, playlist(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlaylistNavigation.RIGHT, playlist(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlaylistNavigation.CONFIRM, playlist(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue("上键应整页翻，不应被当成确认",
            playlist(KeyEvent.KEYCODE_DPAD_UP) != PlaylistNavigation.CONFIRM)
    }

    /** VR 特有层：4.12 起菜单键收起菜单，Y 键仍开合控制条，退出键回 2D 首页，返回键长按是紧急出口。 */
    fun testVrOnlyActionsAndLongPressExit() {
        assertEquals(VrMenuInput.CloseMenu, NavKeyRouter.toVrMenuInput(NavAction.MENU, false))
        assertEquals(VrMenuInput.Toggle, NavKeyRouter.toVrMenuInput(NavAction.TOGGLE, false))
        assertEquals(VrMenuInput.Exit, NavKeyRouter.toVrMenuInput(NavAction.EXIT, false))
        assertEquals(VrMenuInput.Back, NavKeyRouter.toVrMenuInput(NavAction.BACK, false))
        assertEquals(VrMenuInput.Exit, NavKeyRouter.toVrMenuInput(NavAction.BACK, true))
    }

    /** 2D 首页的 MENU / TOGGLE / EXIT 交给宿主执行平台动作，不塞进纯状态机。 */
    fun testHomeOnlyActionsAreDeferredToHost() {
        assertNull(NavKeyRouter.toHomeInput(NavAction.MENU))
        assertNull(NavKeyRouter.toHomeInput(NavAction.TOGGLE))
        assertNull(NavKeyRouter.toHomeInput(NavAction.EXIT))
    }

    /** 4.10：参数键是唯一一个「首页切板块 / VR 呼出参数页」的共享快捷语义。 */
    fun testParamsShortcutIsSharedAcrossScopes() {
        assertEquals(NavAction.PARAMS, NavKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(HomeInput.SelectTab(HomeTab.SETTINGS), NavKeyRouter.toHomeInput(NavAction.PARAMS))
        assertEquals(VrMenuInput.Params, NavKeyRouter.toVrMenuInput(NavAction.PARAMS, false))
        assertNull(NavKeyRouter.toPlaylistAction(NavAction.PARAMS))
        assertFalse(NavKeyRouter.isRepeatable(NavAction.PARAMS))
    }

    /** 参数键改绑后，2D 首页与 VR 菜单必须同步跟着变，且不会污染其它导航键。 */
    fun testCustomParamsBindingAppliesToBothScopes() {
        GamepadBindings.bind(context(), BindAction.PARAMS, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(NavAction.PARAMS, NavKeyRouter.resolve(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(NavKeyRouter.resolve(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(HomeInput.SelectTab(HomeTab.SETTINGS),
            NavKeyRouter.toHomeInput(NavKeyRouter.resolve(KeyEvent.KEYCODE_MEDIA_NEXT)!!))
        assertEquals(NavAction.RIGHT, NavKeyRouter.resolve(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    /** 只有方向键允许长按连续移动，避免长按返回键一路退出。 */
    fun testRepeatPolicy() {
        assertTrue(NavKeyRouter.isRepeatable(NavAction.UP))
        assertTrue(NavKeyRouter.isRepeatable(NavAction.LEFT))
        assertFalse(NavKeyRouter.isRepeatable(NavAction.CONFIRM))
        assertFalse(NavKeyRouter.isRepeatable(NavAction.BACK))
        assertFalse(NavKeyRouter.isRepeatable(NavAction.EXIT))
    }

    /** 未绑定的杂键不应被误当成导航键；音量/电源等系统键尤其不能抢。 */
    fun testUnknownAndSystemKeysAreNotNavigation() {
        assertNull(NavKeyRouter.resolve(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(NavKeyRouter.resolve(KeyEvent.KEYCODE_POWER))
        assertNull(NavKeyRouter.resolve(KeyEvent.KEYCODE_F1))
    }

    /** 媒体键可绑定：廉价蓝牙手柄 / VR 遥控器发的正是这类键，不能被过滤。 */
    fun testMediaKeysAreBindable() {
        GamepadBindings.bind(context(), BindAction.LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        GamepadBindings.bind(context(), BindAction.RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(NavAction.LEFT, NavKeyRouter.resolve(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(NavAction.RIGHT, NavKeyRouter.resolve(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(HomeInput.Navigate(HomeDirection.LEFT),
            NavKeyRouter.toHomeInput(NavKeyRouter.resolve(KeyEvent.KEYCODE_MEDIA_PREVIOUS)!!))
        assertEquals(VrMenuInput.Right,
            NavKeyRouter.toVrMenuInput(NavKeyRouter.resolve(KeyEvent.KEYCODE_MEDIA_NEXT)!!, false))
    }
}
