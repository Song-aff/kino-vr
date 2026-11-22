package dev.astriavr.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * 首页真机走查：紧凑顶栏结构、D-pad 语义链、真实局域网发现与片源导入。
 *
 * 与 [RegressionTests] 分开跑：本套件不清空播放列表，且会主动把语言恢复成中文
 * （RegressionTests 的语言重建用例会把 AppText 留在英文，污染后续真机截图）。
 */
@Suppress("DEPRECATION")
class HomeNavigationTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private var home: HomeActivity? = null

    override fun setUp() {
        super.setUp()
        check(context.packageName.endsWith(".validation")) { "Never run against the distribution app" }
        // RegressionTests 会把进程内语言状态留在英文；恢复中文，保证真机界面与截图一致。
        AppLanguage.select(context, false)
        AppText.setEnglish(false)
        // 不尝试自动唤醒：MIUI 不授予测试进程 DISABLE_KEYGUARD，且 SCREEN_BRIGHT_WAKE_LOCK
        // 在 Android 13 后台也点不亮屏幕。改为在 launchHome() 里检测息屏并快速失败，
        // 避免 startActivitySync 永久挂起；真机跑本套件前请手动点亮并解锁。
    }

    override fun tearDown() {
        home?.let { main { it.finish() } }
        instrumentation.waitForIdleSync()
        super.tearDown()
    }

    private fun main(work: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { work() } catch (error: Throwable) { failure = error } }
        instrumentation.waitForIdleSync()
        failure?.let { throw it }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun eventually(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < end) {
            var success = false
            main { success = condition() }
            if (success) return
            SystemClock.sleep(80)
        }
        fail(message)
    }

    private fun launchHome(): HomeActivity {
        // 息屏时 startActivitySync 会永久挂起（RegressionTests 曾踩过），先明确失败而不是假死。
        val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val end = SystemClock.uptimeMillis() + 6000
        while (!power.isInteractive && SystemClock.uptimeMillis() < end) SystemClock.sleep(120)
        assertTrue("屏幕未能自动唤醒（请手动点亮后重跑）", power.isInteractive)
        return (instrumentation.startActivitySync(
            Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as HomeActivity).also {
            home = it
            instrumentation.waitForIdleSync()
            // 测试期间保持常亮，避免扫描/导入过程中系统再次息屏。
            main { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    private fun controller(h: HomeActivity) = field(h, "controller") as HomeController
    private fun tab(h: HomeActivity): HomeTab = controller(h).state.tab
    private fun items(h: HomeActivity): List<HomeItem> = controller(h).state.items

    private fun key(h: HomeActivity, code: Int) {
        main { h.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
    }

    private fun capture(h: HomeActivity, name: String) {
        main {
            val view = h.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    fun testLauncherIsHomeActivity() {
        // 限定到本应用：不限定的话 MIUI 会返回系统 ResolverActivity 而不是我们的 LAUNCHER。
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val resolve = context.packageManager.resolveActivity(intent, 0)
        assertEquals("dev.astriavr.player.HomeActivity", resolve?.activityInfo?.name)
    }

    /** 用户反馈：标题与 Tab 同一行、底部提示整条删除、内容区尽量占满。 */
    fun testCompactSingleRowToolbarAndNoBottomHints() {
        val h = launchHome()
        var column: LinearLayout? = null
        main { column = (field(h, "content") as FrameLayout).parent as LinearLayout }
        val root = column!!
        assertEquals("首页只剩顶栏 + 内容区两行，不再有底部状态/提示行", 2, root.childCount)
        val toolbar = root.getChildAt(0) as LinearLayout
        // H0.5：顶栏末尾新增「进入 VR 模式」入口，仍是同一行，不另占一行。
        assertEquals("顶栏内是标题 + Tab 容器 + VR 入口", 3, toolbar.childCount)
        assertTrue("顶栏最右是 VR 模式入口按钮", toolbar.getChildAt(2) is ImageButton)
        val brand = toolbar.getChildAt(0) as LinearLayout
        assertEquals("顶栏品牌是 KINO + VR 两段拼色", 2, brand.childCount)
        assertEquals("KINO", (brand.getChildAt(0) as TextView).text.toString())
        assertEquals("VR", (brand.getChildAt(1) as TextView).text.toString())
        assertEquals(AppText.HOME_TITLE.text(), brand.contentDescription?.toString())
        val tabs = toolbar.getChildAt(1) as LinearLayout
        assertEquals("三个一级板块仍在同一行", 3, tabs.childCount)
        assertTrue("Tab 不应再独占一行", tabs.parent === toolbar)
        capture(h, "home-compact.png")
    }

    /** 触摸点击与 D-pad / 手柄是同一条 HomeInput 语义链。 */
    fun testDpadCyclesTabsAndBackReturnsToLibrary() {
        val h = launchHome()
        assertEquals(HomeTab.LIBRARY, tab(h))
        key(h, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(HomeTab.CONNECT, tab(h))
        key(h, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(HomeTab.SETTINGS, tab(h))
        key(h, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Tab 应环绕回片源库", HomeTab.LIBRARY, tab(h))
        key(h, KeyEvent.KEYCODE_DPAD_RIGHT)
        main { h.onBackPressed() }
        assertEquals("返回键先回片源库而不是退出 App", HomeTab.LIBRARY, tab(h))
    }

    /** 用户反馈：选中态要整块橙实心底 + 近黑文字，而不是橙色描边。 */
    @Suppress("UNCHECKED_CAST")
    fun testSelectionIsSolidAmberNotOutline() {
        val h = launchHome()
        val tabs = field(h, "tabButtons") as HashMap<HomeTab, Button>
        val active = tabs[HomeTab.LIBRARY]!!
        val inactive = tabs[HomeTab.CONNECT]!!
        val activeBg = active.background
        assertTrue("选中 Tab 必须是纯色实心块", activeBg is GradientDrawable)
        assertEquals("选中 Tab 底色应为强调橙", UiStyle.ACCENT,
            (activeBg as GradientDrawable).color?.defaultColor)
        assertEquals("橙底上文字必须为近黑", UiStyle.BRAND_INK, active.currentTextColor)
        val inactiveBg = inactive.background as? GradientDrawable
        assertFalse("未选中 Tab 不能是橙实心块",
            inactiveBg?.color?.defaultColor == UiStyle.ACCENT)

        // 切到「设置」板块：列表焦点行同样是橙实心底，且块内文字同步反色。
        key(h, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(h, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(HomeTab.SETTINGS, tab(h))
        val page = field(h, "settingsPage") as HomeSettingsPage
        val list = field(page, "list") as HomeItemListView
        val rows = field(list, "rows") as ArrayList<android.view.View>
        assertTrue("设置页应有可聚焦条目", rows.isNotEmpty())
        val focused = rows[0]
        val focusedBg = focused.background
        assertTrue("焦点行必须是纯色实心块", focusedBg is GradientDrawable)
        assertEquals("焦点行底色应为强调橙", UiStyle.ACCENT,
            (focusedBg as GradientDrawable).color?.defaultColor)
        val titleRow = (focused as ViewGroup).getChildAt(0) as ViewGroup
        val title = titleRow.getChildAt(0) as TextView
        assertEquals("橙底上条目标题必须为近黑", UiStyle.BRAND_INK, title.currentTextColor)
    }

    /** 真实 PC 管理端在线时，连接板块应发现设备并把片源批量写回片源库。 */
    fun testConnectTabDiscoversLanDeviceAndImportsPlaylist() {
        val h = launchHome()
        key(h, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(HomeTab.CONNECT, tab(h))
        eventually("连接板块未发现局域网片源设备（PC 管理端需运行）") {
            items(h).any { it.id.startsWith("device|") }
        }
        val device = items(h).first { it.id.startsWith("device|") }
        assertTrue("设备应有名称", device.title.isNotBlank())
        capture(h, "home-connect.png")

        main { (field(h, "connectionsPage") as HomeConnectionsPage).connectDevice(device.id) }
        // eventually 已经在主线程执行 condition，这里不能再套 main（runOnMainSync 会拒绝主线程调用）。
        eventually("片源未批量写入播放列表") {
            (field(h, "playlist") as PlaylistStore).query().use { it.count > 0 }
        }
        key(h, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(HomeTab.LIBRARY, tab(h))
        capture(h, "home-library-filled.png")
    }
}
