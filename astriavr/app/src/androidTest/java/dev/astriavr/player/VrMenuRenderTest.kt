package dev.astriavr.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import junit.framework.TestCase
import java.io.File
import java.io.FileOutputStream

/**
 * H4/S4a：把 Canvas 菜单纹理原样导出成 PNG，供离线核对。
 *
 * 这条测试不依赖亮屏 / GL 上下文：它验证的是 `VrMenuRenderer` 的绘制链
 * （拼色品牌、三 Tab、橙实心焦点、右栏播放信息、播放态控制条）能跑通且尺寸正确。
 * 产物在测试 APK 的外部私有目录，用 adb pull 取出后即可肉眼检查。
 */
class VrMenuRenderTest : TestCase() {

    private fun context(): Context =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context

    private fun export(name: String, bitmap: Bitmap): File {
        val dir = File(context().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "vr-menu")
        dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun testExportMenuAndBarTextures() {
        val session = VrMenuSession().apply {
            setLibrary(listOf(
                VrMenuEntry("1", "Interstellar · 180° 3D", "content://a"),
                VrMenuEntry("2", "Sunshine 串流 · KAFKA", "stream://b"),
                VrMenuEntry("3", "演示片 A", "content://c"),
                VrMenuEntry("4", "演示片 B", "content://d"),
                VrMenuEntry("5", "演示片 C", "content://e"),
                VrMenuEntry("6", "演示片 D", "content://f"),
                VrMenuEntry("7", "演示片 E", "content://g")))
            setExtras(VrMenuExtras(
                nowPlaying = "Interstellar · 180° 3D",
                playing = true,
                positionMs = 42 * 60 * 1000L + 18000L,
                durationMs = 169 * 60 * 1000L,
                values = mapOf(
                    VrMenuCommands.VIEWING_MODE to "3D 双镜头 · VR",
                    VrMenuCommands.PROJECTION to "自动 · 等距柱状 180°",
                    VrMenuCommands.LAYOUT to "自动 · 左右 SBS",
                    VrMenuCommands.SWAP_EYES to "关",
                    VrMenuCommands.IPD to "6.5 cm",
                    VrMenuCommands.SCREEN_SIZE to "70°",
                    VrMenuCommands.SCREEN_FLIP to "正常",
                    VrMenuCommands.FOV to "88°",
                    VrMenuCommands.SPEED to "1.0x")))
        }
        val renderer = VrMenuRenderer()
        assertEquals(VrMenuRenderer.WIDTH, renderer.bitmap.width)
        assertEquals(VrMenuRenderer.HEIGHT, renderer.bitmap.height)

        renderer.mode = VrMenuMode.MENU
        renderer.extras = session.extras
        renderer.render(session.state)
        val menu = export("vr-menu-4.10.png", renderer.bitmap)
        assertTrue(menu.length() > 1000)

        renderer.mode = VrMenuMode.BAR
        renderer.redraw(session.state)
        val bar = export("vr-bar-4.10.png", renderer.bitmap)
        assertTrue(bar.length() > 1000)
    }
}
