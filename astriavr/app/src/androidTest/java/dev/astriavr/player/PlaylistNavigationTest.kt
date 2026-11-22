package dev.astriavr.player

import junit.framework.TestCase

/**
 * 4.14：锁住换片列表的轴输入契约 —— 左右逐条、上下整页翻、确认播放。
 *
 * 演进：4.9 前「上」当确认（一按就开播）；4.13 把上下做成 ±1 逐条，被用户否掉
 * （「那我不会左右吗」）；4.14 定版：← → 逐条切换（±1）、↑ ↓ 整页翻
 * （±VrPlaylistView.PAGE）、确认只来自确认键。纯逻辑，不需要亮屏、不需要真实手柄。
 */
class PlaylistNavigationTest : TestCase() {

    /** 上 = 前一页（PREV_PAGE）、下 = 后一页（NEXT_PAGE）；回中无动作。 */
    fun testHatUpDownTurnPages() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.PREV_PAGE, nav.hat(0f, -1f, 1000L))
        assertEquals(0, nav.hat(0f, 0f, 1010L))
        assertEquals(PlaylistNavigation.NEXT_PAGE, nav.hat(0f, 1f, 1020L))
        assertEquals(0, nav.hat(0f, 0f, 1030L))
    }

    /** 左右 = 逐条切换，不是翻页。 */
    fun testHatLeftRightStepOneEntry() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.LEFT, nav.hat(-1f, 0f, 1000L))
        assertEquals(0, nav.hat(0f, 0f, 1010L))
        assertEquals(PlaylistNavigation.RIGHT, nav.hat(1f, 0f, 1020L))
    }

    /** 轴输入永不产生确认 —— 确认只来自确认键。 */
    fun testHatNeverConfirms() {
        val nav = PlaylistNavigation()
        val directions = arrayOf(
            floatArrayOf(0f, -1f), floatArrayOf(0f, 1f),
            floatArrayOf(-1f, 0f), floatArrayOf(1f, 0f),
            floatArrayOf(-1f, -1f), floatArrayOf(1f, 1f))
        for (d in directions) {
            val fired = nav.hat(d[0], d[1], 1000L)
            assertEquals("hat(${d[0]}, ${d[1]}) must never confirm",
                0, fired and PlaylistNavigation.CONFIRM)
            nav.hat(0f, 0f, 1010L)
        }
        assertEquals(PlaylistNavigation.CONFIRM,
            nav.key(PlaylistNavigation.CONFIRM, true, false, 1020L))
        assertEquals(0, nav.key(PlaylistNavigation.CONFIRM, false, false, 1030L))
    }

    /** 斜向取偏转更大的单轴：x 主导走逐条，y 主导走翻页。 */
    fun testDiagonalTakesDominantAxis() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.RIGHT, nav.hat(1f, .6f, 1000L))
        nav.hat(0f, 0f, 1010L)
        assertEquals(PlaylistNavigation.PREV_PAGE, nav.hat(-.6f, -1f, 1020L))
        nav.hat(0f, 0f, 1030L)
        assertEquals(PlaylistNavigation.LEFT, nav.hat(-1f, -1f, 1040L))
        nav.hat(0f, 0f, 1050L)
        assertEquals(PlaylistNavigation.NEXT_PAGE, nav.hat(.6f, 1f, 1060L))
    }

    fun testDeadZoneSuppressesDrift() {
        val nav = PlaylistNavigation()
        assertEquals(0, nav.hat(.4f, .4f, 1000L))
        assertEquals(0, nav.hat(.49f, -.49f, 1010L))
        assertEquals(PlaylistNavigation.RIGHT, nav.hat(.5f, 0f, 1020L))
    }

    /** 按住不动只在按下沿触发一次，回中后重新武装。 */
    fun testEdgeFiresOnceWhileHeld() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.PREV_PAGE, nav.hat(0f, -1f, 1000L))
        assertEquals(0, nav.hat(0f, -.9f, 1010L))
        assertEquals(0, nav.hat(0f, -1f, 1020L))
        assertEquals(0, nav.hat(0f, 0f, 1030L))
        assertEquals(PlaylistNavigation.PREV_PAGE, nav.hat(0f, -1f, 1040L))
    }

    /** HAT 与按键指向同一动作时合并去重，不会连跳两次。 */
    fun testKeyAndHatMergeWithoutDoubleFire() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.NEXT_PAGE, nav.hat(0f, 1f, 1000L))
        assertEquals("Already held via HAT", 0,
            nav.key(PlaylistNavigation.NEXT_PAGE, true, false, 1005L))
        assertEquals(0, nav.hat(0f, 0f, 1010L))
        assertEquals(0, nav.key(PlaylistNavigation.NEXT_PAGE, false, false, 1015L))
        assertTrue(nav.isNeutral)
    }

    /** 同轴对向按住互相抵消（上+下 / 左+右）。 */
    fun testOpposingDirectionsSuppressFire() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.NEXT_PAGE, nav.key(PlaylistNavigation.NEXT_PAGE, true, false, 1000L))
        assertEquals("Opposite page direction held together never fires", 0, nav.hat(0f, -1f, 1010L))
        assertEquals(0, nav.key(PlaylistNavigation.NEXT_PAGE, false, false, 1020L))
        assertFalse("Held direction survives the other release", nav.isNeutral)
        assertEquals(0, nav.hat(0f, 0f, 1030L))
        assertTrue(nav.isNeutral)
    }

    /** 跨轴不抵消：逐条与翻页可同时生效。 */
    fun testCrossAxisDirectionsCoexist() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.LEFT, nav.key(PlaylistNavigation.LEFT, true, false, 1000L))
        assertEquals(PlaylistNavigation.NEXT_PAGE, nav.hat(0f, 1f, 1010L))
    }

    /** 长按连发：350ms 起步，之后 180ms 一步；松手即停。 */
    fun testRepeatCadence() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.PREV_PAGE, nav.key(PlaylistNavigation.PREV_PAGE, true, false, 1000L))
        assertEquals(0, nav.repeat(1349L))
        assertEquals(PlaylistNavigation.PREV_PAGE, nav.repeat(1350L))
        assertEquals(0, nav.repeat(1529L))
        assertEquals(PlaylistNavigation.PREV_PAGE, nav.repeat(1530L))
        assertEquals(0, nav.key(PlaylistNavigation.PREV_PAGE, false, false, 1540L))
        assertFalse(nav.hasRepeat())
        assertEquals(0, nav.repeat(1720L))
    }

    /** 长按的重复 KeyEvent 不重复触发，连发统一走 repeat()。 */
    fun testRepeatedKeyEventsDoNotReFire() {
        val nav = PlaylistNavigation()
        assertEquals(PlaylistNavigation.LEFT, nav.key(PlaylistNavigation.LEFT, true, false, 1000L))
        assertEquals(0, nav.key(PlaylistNavigation.LEFT, true, true, 1060L))
        assertEquals(0, nav.key(PlaylistNavigation.LEFT, true, true, 1120L))
    }
}
