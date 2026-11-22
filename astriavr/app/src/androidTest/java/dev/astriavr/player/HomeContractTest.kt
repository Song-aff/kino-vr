package dev.astriavr.player

import android.content.Context
import junit.framework.TestCase

/**
 * H0 契约层回归：**纯逻辑，不需要亮屏解锁**，也不启动任何 Activity。
 *
 * 这些断言锁住的是「下阶段 VR 化 / 手柄适配」要复用的公共契约：
 * Route 能跨 Intent 往返、方向键语义、焦点环绕、effect 分发。
 * 只要它们成立，换 Renderer 时状态机就不需要重写。
 */
class HomeContractTest : TestCase() {

    private fun context(): Context =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context

    private fun items() = listOf(
        HomeItem("a", "A", intent = HomeIntent.Command("cmd.a")),
        HomeItem("b", "B", intent = HomeIntent.Launch(PlayerRoute.Stream("192.168.31.254", "PC")))
    )

    private fun controller(): Pair<HomeController, MutableList<HomeEffect>> {
        val effects = ArrayList<HomeEffect>()
        return HomeController { effects.add(it) } to effects
    }

    fun testVideoRouteRoundTrip() {
        val route = PlayerRoute.Video("http://192.168.31.254:8000/a.mp4", "a.mp4", true)
        val restored = PlayerLauncher.from(PlayerLauncher.intent(context(), route))
        assertEquals(route, restored)
        assertTrue(PlayerLauncher.intent(context(), route).getBooleanExtra(PlayerLauncher.FROM_HOME, false))
    }

    fun testStreamRouteRoundTrip() {
        val route = PlayerRoute.Stream("192.168.31.254", "KAFKA")
        assertEquals(route, PlayerLauncher.from(PlayerLauncher.intent(context(), route)))
        assertNull(PlayerLauncher.from(null))
    }

    fun testLeftRightWrapsTabs() {
        val (controller, _) = controller()
        controller.dispatch(HomeInput.Navigate(HomeDirection.LEFT))
        assertEquals(HomeTab.SETTINGS, controller.state.tab)
        controller.dispatch(HomeInput.Navigate(HomeDirection.RIGHT))
        assertEquals(HomeTab.LIBRARY, controller.state.tab)
        controller.dispatch(HomeInput.Navigate(HomeDirection.RIGHT))
        assertEquals(HomeTab.CONNECT, controller.state.tab)
    }

    fun testUpDownWrapsFocusAndItemsAreCachedPerTab() {
        val (controller, _) = controller()
        controller.dispatch(HomeInput.Items(HomeTab.LIBRARY, items()))
        assertEquals(0, controller.state.focus)
        controller.dispatch(HomeInput.Navigate(HomeDirection.UP))
        assertEquals(1, controller.state.focus)
        controller.dispatch(HomeInput.Navigate(HomeDirection.DOWN))
        assertEquals(0, controller.state.focus)
        controller.dispatch(HomeInput.SelectTab(HomeTab.CONNECT))
        assertTrue(controller.state.items.isEmpty())
        controller.dispatch(HomeInput.SelectTab(HomeTab.LIBRARY))
        assertEquals(2, controller.state.items.size)
    }

    fun testConfirmEmitsCommandAndLaunch() {
        val (controller, effects) = controller()
        controller.dispatch(HomeInput.Items(HomeTab.LIBRARY, items()))
        controller.dispatch(HomeInput.Confirm)
        assertTrue(effects.last() is HomeEffect.Command)
        assertEquals("cmd.a", (effects.last() as HomeEffect.Command).key)
        controller.dispatch(HomeInput.Navigate(HomeDirection.DOWN))
        controller.dispatch(HomeInput.Confirm)
        val launch = effects.last() as HomeEffect.Launch
        assertEquals("192.168.31.254", (launch.route as PlayerRoute.Stream).hostIp)
    }

    fun testBackGoesToLibraryThenExits() {
        val (controller, effects) = controller()
        controller.dispatch(HomeInput.SelectTab(HomeTab.SETTINGS))
        controller.dispatch(HomeInput.Back)
        assertEquals(HomeTab.LIBRARY, controller.state.tab)
        assertTrue(effects.none { it is HomeEffect.Exit })
        controller.dispatch(HomeInput.Back)
        assertTrue(effects.last() is HomeEffect.Exit)
    }

    fun testHostRoutesEffectsToRendererAndPlatform() {
        val rendered = ArrayList<HomeState>()
        val launched = ArrayList<PlayerRoute>()
        val commands = ArrayList<String>()
        var exited = false
        val renderer = object : HomeRenderer { override fun render(state: HomeState) { rendered.add(state) } }
        val platform = object : HomePlatform {
            override fun launch(route: PlayerRoute) { launched.add(route) }
            override fun command(key: String, itemId: String) { commands.add("$key:$itemId") }
            override fun exit() { exited = true }
        }
        val host = HomeHost(renderer, platform)
        val state = HomeState(tab = HomeTab.CONNECT)
        host.on(HomeEffect.Render(state))
        host.on(HomeEffect.Launch(PlayerRoute.Stream("10.0.0.2", "PC")))
        host.on(HomeEffect.Command("cmd.a", "a"))
        host.on(HomeEffect.Exit)
        assertEquals(state, rendered.single())
        assertEquals("10.0.0.2", (launched.single() as PlayerRoute.Stream).hostIp)
        assertEquals("cmd.a:a", commands.single())
        assertTrue(exited)
    }
}
