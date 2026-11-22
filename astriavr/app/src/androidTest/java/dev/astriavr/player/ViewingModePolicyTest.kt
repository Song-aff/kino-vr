package dev.astriavr.player

import junit.framework.TestCase

/**
 * 4.11：锁住「片源默认恒为 3D 双眼」这条契约。
 *
 * 旧逻辑里「没识别出 VR 标记」会被解析成普通视频（mode 2），跳到
 * FlatVideoView 后 VR 菜单/参数浮层全部消失，戴镜只能摘机回 2D 设置页切换。
 * 新策略不允许自动识别产生 2（或任何非 0 的观看模式），只把遗留状态归一回 0。
 * 纯逻辑，不需要亮屏、不启动 Activity、不留用户配置。
 */
class ViewingModePolicyTest : TestCase() {

    fun testDefaultDualLensIsLeftAlone() {
        assertNull(ViewingModePolicy.decide(false, false, false))
    }

    fun testLegacyOrdinaryVideoIsPulledBack() {
        assertEquals(0, ViewingModePolicy.decide(true, false, false)!!)
    }

    fun testBigScreenPreferenceIsPulledBack() {
        assertEquals(0, ViewingModePolicy.decide(false, true, false)!!)
    }

    fun testSingleLensPreferenceIsPulledBack() {
        assertEquals(0, ViewingModePolicy.decide(false, false, true)!!)
    }

    fun testAllEightCombinations() {
        for (ordinary in listOf(false, true))
            for (screen in listOf(false, true))
                for (phone in listOf(false, true)) {
                    val expected = if (!ordinary && !screen && !phone) null else 0
                    val actual = ViewingModePolicy.decide(ordinary, screen, phone)
                    assertTrue("combo ordinary=$ordinary screen=$screen phone=$phone -> $actual",
                        expected == actual)
                }
    }

    /** 关键回归：自动识别的返回值只能是 null 或 0，永远不能是 2（普通视频）。 */
    fun testAutomaticDetectionNeverProducesOrdinaryVideo() {
        val results = mutableListOf<Int?>()
        for (ordinary in listOf(false, true))
            for (screen in listOf(false, true))
                for (phone in listOf(false, true))
                    results += ViewingModePolicy.decide(ordinary, screen, phone)
        assertTrue("only dual-lens VR is allowed",
            results.all { it == null || it == ViewingModePolicy.DUAL_LENS_VR })
        assertFalse("mode 2 (ordinary video) must never be chosen automatically", results.contains(2))
        assertEquals(ViewingModePolicy.DUAL_LENS_VR, 0)
    }
}
