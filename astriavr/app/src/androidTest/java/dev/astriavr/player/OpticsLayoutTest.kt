package dev.astriavr.player

import junit.framework.TestCase

/**
 * 4.13：两圆垂直位置契约。
 *
 * 头戴底部支撑会把手机垫高，上下居中的两圆整体偏上、对不准镜片；
 * 「吸底」模式把 viewport 贴到屏幕下沿（GL 原点在左下，y = 0）。
 * 这些断言锁住 Optics.Layout 的 y 计算与 RenderSettings 透传，
 * 纯几何逻辑，不需要设备、不需要 EGL。
 */
class OpticsLayoutTest : TestCase() {

    private val width = 2800
    private val height = 1260

    /** 居中：两圆 y 与上下留白相等（奇数余量时整数除法允许差 1px）。 */
    fun testCenteredYieldsEqualMargins() {
        val layout = Optics.Layout(width, height, 6.5f, 5f, Optics.WIDTH_CM, Optics.HEIGHT_CM, false)
        assertEquals((height - layout.eyeHeight) / 2, layout.y)
        val topMargin = height - layout.y - layout.eyeHeight
        assertTrue("Margins must match within rounding",
            Math.abs(layout.y - topMargin) <= 1)
    }

    /** 吸底：viewport 贴屏幕下沿（GL y = 0），上方留出全部余量。 */
    fun testBottomAlignSitsAtBottomEdge() {
        val layout = Optics.Layout(width, height, 6.5f, 5f, Optics.WIDTH_CM, Optics.HEIGHT_CM, true)
        assertEquals(0, layout.y)
        assertEquals(height - layout.eyeHeight, height - layout.y - layout.eyeHeight)
    }

    /** 满高时两种模式重合（没有可分配的余量）。 */
    fun testFullHeightEyeBothModesMatch() {
        val centered = Optics.Layout(width, height, 6.5f, 7f, Optics.WIDTH_CM, Optics.HEIGHT_CM, false)
        val bottom = Optics.Layout(width, height, 6.5f, 7f, Optics.WIDTH_CM, Optics.HEIGHT_CM, true)
        if (centered.eyeHeight == height) {
            assertEquals(centered.y, bottom.y)
        } else {
            assertTrue("Bottom y must not exceed centered y", bottom.y <= centered.y)
        }
    }

    /** RenderSettings 默认吸底并透传给 opticalLayout()。 */
    fun testRenderSettingsPassesAlignBottom() {
        assertTrue("Default must be bottom-aligned", RenderSettings().alignBottom)
        val bottom = RenderSettings().opticalLayout(width, height)
        assertEquals(0, bottom.y)
        val centered = RenderSettings(alignBottom = false).opticalLayout(width, height)
        assertEquals((height - centered.eyeHeight) / 2, centered.y)
    }

    /** 旧构造器保持居中行为，避免既有调用点（如 VrView 初始占位）语义漂移。 */
    fun testLegacyConstructorStaysCentered() {
        val layout = Optics.Layout(width, height, 6.5f)
        assertEquals((height - layout.eyeHeight) / 2, layout.y)
    }

    /** 换眼 / IPD / 直径等参数与垂直位置互不干扰。 */
    fun testHorizontalLayoutUnaffectedByAlignment() {
        val centered = Optics.Layout(width, height, 6.5f, 5f, Optics.WIDTH_CM, Optics.HEIGHT_CM, false)
        val bottom = Optics.Layout(width, height, 6.5f, 5f, Optics.WIDTH_CM, Optics.HEIGHT_CM, true)
        assertEquals(centered.leftX, bottom.leftX)
        assertEquals(centered.rightX, bottom.rightX)
        assertEquals(centered.eyeWidth, bottom.eyeWidth)
        assertEquals(centered.eyeHeight, bottom.eyeHeight)
    }
}
