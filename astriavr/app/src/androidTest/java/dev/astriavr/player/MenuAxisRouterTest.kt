package dev.astriavr.player

import junit.framework.TestCase

/**
 * 4.11：锁住「十字键 HAT / 左摇杆 → 菜单方向」这条轴输入契约。
 *
 * 旧版 VR 菜单只收 KeyEvent，轴事件全漏进 GamepadInput 变成快进 / FOV / 转视角，
 * 戴镜后方向键既操控不了菜单、又被播放层抢走。这些断言保证：
 * 方向语义与 GamepadState.hat 一致、边沿触发不连跳、长按连发、HAT 优先。
 * 纯逻辑，不需要亮屏、不需要真实手柄。
 */
class MenuAxisRouterTest : TestCase() {

    private val directions = setOf(
        NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT)

    /** Android 轴约定：X 正 = 右，Y 正 = 下；与播放层 GamepadState.hat 一致。 */
    fun testHatFourDirections() {
        val router = MenuAxisRouter()
        assertEquals(NavAction.RIGHT, router.feed(1f, 0f, 0f, 0f, 1000L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1010L))
        assertEquals(NavAction.LEFT, router.feed(-1f, 0f, 0f, 0f, 1020L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1030L))
        assertEquals(NavAction.DOWN, router.feed(0f, 1f, 0f, 0f, 1040L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1050L))
        assertEquals(NavAction.UP, router.feed(0f, -1f, 0f, 0f, 1060L))
    }

    /** 没有 HAT 的廉价手柄：左摇杆同样能驱动菜单四向。 */
    fun testLeftStickFourDirections() {
        val router = MenuAxisRouter()
        assertEquals(NavAction.RIGHT, router.feed(0f, 0f, 0.9f, 0f, 1000L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1010L))
        assertEquals(NavAction.LEFT, router.feed(0f, 0f, -0.9f, 0f, 1020L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1030L))
        assertEquals(NavAction.DOWN, router.feed(0f, 0f, 0f, 0.9f, 1040L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1050L))
        assertEquals(NavAction.UP, router.feed(0f, 0f, 0f, -0.9f, 1060L))
    }

    fun testDeadZoneSuppressesDrift() {
        val router = MenuAxisRouter()
        assertNull(router.feed(0f, 0f, 0.49f, 0.49f, 1000L))
        assertNull(router.feed(0.49f, 0f, 0f, 0f, 1010L))
        assertEquals(NavAction.RIGHT, router.feed(0f, 0f, 0.5f, 0f, 1020L))
    }

    /** 一个 ACTION_MOVE 的 history 里同一个方向不会被重复触发。 */
    fun testEdgeOnlyFiresOnceWhileHeld() {
        val router = MenuAxisRouter()
        assertEquals(NavAction.DOWN, router.feed(0f, 1f, 0f, 0f, 1000L))
        assertNull(router.feed(0f, 1f, 0f, 0f, 1005L))
        assertNull(router.feed(0f, 1f, 0f, 0f, 1010L))
        assertNull(router.feed(0f, 0.9f, 0f, 0f, 1015L))
        assertEquals(NavAction.DOWN, router.held())
    }

    /** 松开回中后重新武装，下一次按下仍会触发（否则菜单只能按一次）。 */
    fun testNeutralRearmsNextPress() {
        val router = MenuAxisRouter()
        assertEquals(NavAction.UP, router.feed(0f, -1f, 0f, 0f, 1000L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1100L))
        assertNull(router.held())
        assertEquals(NavAction.UP, router.feed(0f, -1f, 0f, 0f, 1200L))
    }

    /** 斜向拨动取偏转更大的单轴，避免对角抖动让焦点乱跳。 */
    fun testDiagonalPicksDominantAxis() {
        val router = MenuAxisRouter()
        assertEquals(NavAction.RIGHT, router.feed(0f, 0f, 0.9f, 0.4f, 1000L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1010L))
        assertEquals(NavAction.DOWN, router.feed(0f, 0f, 0.3f, 0.9f, 1020L))
    }

    /** 十字键与摇杆同时有输入时 HAT 优先，防止两套输入互相覆盖。 */
    fun testHatOverridesStick() {
        val router = MenuAxisRouter()
        assertEquals(NavAction.RIGHT, router.feed(1f, 0f, -0.9f, 0f, 1000L))
        assertNull(router.feed(0f, 0f, 0f, 0f, 1010L))
        assertEquals(NavAction.DOWN, router.feed(0f, 1f, 0f, -0.9f, 1020L))
    }

    /** 快速连拨（history 里的 0→右→0→下）每次边沿都要送到菜单。 */
    fun testRapidDirectionChangesFireEachEdge() {
        val router = MenuAxisRouter()
        val fired = mutableListOf<NavAction>()
        listOf(
            floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f),
            floatArrayOf(0f, 1f), floatArrayOf(0f, 0f)
        ).forEachIndexed { i, sample ->
            router.feed(sample[0], sample[1], 0f, 0f, 1000L + i * 20L)?.let { fired += it }
        }
        assertEquals(listOf(NavAction.RIGHT, NavAction.DOWN), fired)
    }

    fun testRepeatHonorsFirstDelay() {
        val router = MenuAxisRouter()
        router.feed(0f, 1f, 0f, 0f, 1000L)
        assertNull("no repeat before first delay", router.repeat(1000L + 399L))
        assertEquals(NavAction.DOWN, router.repeat(1000L + 400L))
    }

    fun testRepeatIntervalAndDirection() {
        val router = MenuAxisRouter()
        router.feed(-1f, 0f, 0f, 0f, 1000L)
        assertEquals(NavAction.LEFT, router.repeat(1500L))
        assertNull("too early", router.repeat(1500L + 149L))
        assertEquals(NavAction.LEFT, router.repeat(1650L))
    }

    /** 松开后 repeat 立即失效，不会在菜单里无限连发。 */
    fun testNeutralStopsRepeat() {
        val router = MenuAxisRouter()
        router.feed(0f, 1f, 0f, 0f, 1000L)
        assertNull(router.feed(0f, 0f, 0f, 0f, 1100L))
        assertNull(router.repeat(5000L))
    }

    /** 菜单关闭时 clear() 归零并重新武装，防止按住状态带进下一次唤起。 */
    fun testClearRearmsAndStopsRepeat() {
        val router = MenuAxisRouter()
        router.feed(1f, 0f, 0f, 0f, 1000L)
        router.clear()
        assertNull(router.held())
        assertNull(router.repeat(5000L))
        assertEquals(NavAction.RIGHT, router.feed(1f, 0f, 0f, 0f, 6000L))
    }

    /** 轴路由只能产出四个方向动作，绝不产生确认 / 返回 / 退出 / 参数。 */
    fun testOnlyDirectionalActionsAreProduced() {
        val router = MenuAxisRouter()
        val produced = mutableSetOf<NavAction>()
        val samples = listOf(
            floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f),
            floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
            floatArrayOf(0.9f, 0.4f), floatArrayOf(-0.9f, -0.4f),
            floatArrayOf(0.2f, 0.2f) // 死区内，不应产出
        )
        var now = 1000L
        for (sample in samples) {
            router.feed(sample[0], sample[1], sample[0], sample[1], now)?.let { produced += it }
            router.feed(0f, 0f, 0f, 0f, now + 10)
            now += 20
        }
        assertTrue("unexpected action: $produced", produced.all { it in directions })
        assertEquals(directions, produced)
    }
}
