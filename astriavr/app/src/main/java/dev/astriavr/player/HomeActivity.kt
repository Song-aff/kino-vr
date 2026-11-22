package dev.astriavr.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 首页（H0/H1/H2）。片源库 / 连接 / 设置在这里，选完之后才进 [MainActivity] 播放。
 *
 * 本类只做两件事：把 Android 事件翻译成 [HomeInput]，以及把 [HomeState] 画成 2D View。
 * 所有导航判断都在纯 Kotlin 的 [HomeController] 里，因此换成 VR 双眼渲染器时
 * 不需要改这里的状态机，也不需要改扫描 / 播放列表 / 串流配对等数据层。
 */
class HomeActivity : Activity(), HomeRenderer, HomePlatform {
    private lateinit var controller: HomeController
    private lateinit var hostRouter: HomeHost
    private lateinit var playlist: PlaylistStore
    private lateinit var covers: CoverRepository
    private lateinit var libraryPage: PlaylistPage
    private lateinit var connectionsPage: HomeConnectionsPage
    private lateinit var settingsPage: HomeSettingsPage
    private lateinit var content: FrameLayout
    private val tabButtons = HashMap<HomeTab, Button>()
    private val io = Executors.newSingleThreadExecutor()
    private var backNavigation: BackNavigation? = null
    private var activeTab: HomeTab? = null
    // 4.11：与 VR 菜单共用轴输入路由，廉价手柄的 HAT 十字键 / 左摇杆在 2D 首页
    // 同样能上下左右选条目，保证「戴镜内外方向语义一致」。
    private val menuAxis = MenuAxisRouter()
    private var menuAxisRepeatScheduled = false
    private val uiHandler = Handler(Looper.getMainLooper())
    /** View 树未建完前，页面构造期的 items 回调只能丢弃，稍后 republish 补上。 */
    private var uiReady = false
    @Volatile private var closing = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(AppLanguage.configuration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashLog.hasReport(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        AppLanguage.load(this)
        playlist = PlaylistStore(this)
        covers = CoverRepository(this, playlist)
        hostRouter = HomeHost(this, this)
        controller = HomeController { effect -> hostRouter.on(effect) }
        buildUi()
        uiReady = true
        connectionsPage.republish()
        settingsPage.refresh()
        val savedTab = savedInstanceState?.getString("home_tab")?.let { runCatching { HomeTab.valueOf(it) }.getOrNull() }
        if (savedTab != null) {
            controller.restore(HomeState(tab = savedTab,
                focus = savedInstanceState?.getInt("home_focus", 0) ?: 0,
                status = savedInstanceState?.getString("home_status").orEmpty()))
            controller.dispatch(HomeInput.SelectTab(savedTab))
        } else {
            // 底部提示行已移除：初始状态留空，扫描/导入等状态由对应板块在内容区内显示。
            controller.dispatch(HomeInput.Status(""))
        }
        backNavigation = BackNavigation.create(this, ::handleBack).also { it.setEnabled(true) }
    }

    private val dp = { value: Int -> UiStyle.dp(this, value) }

    private fun buildUi() {
        val root = FrameLayout(this).apply { background = UiStyle.stars(this@HomeActivity) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        // 紧凑单行顶栏：标题与三个 Tab 共用一行，不再各占一行；底部不留状态/提示行，
        // 把纵向空间全部让给内容选择区（各板块自己的状态文字在内容区内显示）。
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(UiStyle.brand(this).apply {
            contentDescription = AppText.HOME_TITLE.text()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (tab in HomeTab.values()) {
            val button = Button(this).apply {
                text = tabLabel(tab)
                setTextColor(UiStyle.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                isAllCaps = false
                setSingleLine()
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { controller.dispatch(HomeInput.SelectTab(tab)) }
            }
            tabButtons[tab] = button
            tabs.addView(button, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                if (tab.ordinal > 0) leftMargin = dp(6)
            })
        }
        toolbar.addView(tabs, LinearLayout.LayoutParams(0, -2, 1f))
        // H4/S4a：最右侧「进入 VR 模式」入口。戴镜后所有操作都在 world-locked
        // 银幕上完成，这里只是一个摘镜状态下的显式入口。
        toolbar.addView(UiStyle.icon(this, "vr", AppText.HOME_VR_MODE.text(), click = {
            PlayerLauncher.launchVrMenu(this)
        }), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })
        column.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

        libraryPage = PlaylistPage(this, playlist, covers,
            play = { entry, fromStart -> launch(PlayerRoute.Video(entry.uri, entry.name, fromStart)) },
            add = { command(HomeCommands.ADD_VIDEOS, "cmd|add") },
            openNetwork = { controller.dispatch(HomeInput.SelectTab(HomeTab.CONNECT)) },
            back = { handleBack() },
            reorder = { id, target, after, ready ->
                playlist.submit({ playlist.moveRelative(id, target, after) }) { result ->
                    if (!closing) ready(result.getOrDefault(false))
                }
            },
            remove = { entry, ready ->
                playlist.submit({
                    playlist.remove(entry.id)
                    runCatching {
                        contentResolver.releasePersistableUriPermission(Uri.parse(entry.uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }) { result ->
                    if (!closing) {
                        if (result.isSuccess) covers.invalidate(entry, removed = true)
                        ready(result.isSuccess)
                    }
                }
            },
            homeMode = true)
        content.addView(libraryPage, FrameLayout.LayoutParams(-1, -1))

        connectionsPage = HomeConnectionsPage(this, playlist,
            onItems = { items -> controller.dispatch(HomeInput.Items(HomeTab.CONNECT, items)) },
            onStatus = { text -> controller.dispatch(HomeInput.Status(text)) },
            onLibraryChanged = { if (!closing) libraryPage.refresh() },
            onFocusInput = { })
        val connectScroll = ScrollView(this).apply {
            addView(connectionsPage, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(connectScroll, FrameLayout.LayoutParams(-1, -1))

        settingsPage = HomeSettingsPage(this) { items ->
            controller.dispatch(HomeInput.Items(HomeTab.SETTINGS, items))
        }
        val settingsScroll = ScrollView(this).apply {
            addView(settingsPage, FrameLayout.LayoutParams(-1, -2))
        }
        content.addView(settingsScroll, FrameLayout.LayoutParams(-1, -1))

        // 系统栏已隐藏，不再按 insets 给内容让位；列表页内部默认的 18dp 也一并清掉，
        // 让选择区真正铺满整块屏幕（只保留 column 的 8/4dp 防贴边）。
        libraryPage.setContentPadding(0, 0, 0, 0)
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // 必须在 setContentView 之后：PhoneWindow 未安装 decor 时取 InsetsController 会崩。
        configureWindow()
    }

    /** 与播放页一致的沉浸全屏：隐藏状态栏/导航栏，内容延伸到刘海区。 */
    @Suppress("DEPRECATION")
    private fun configureWindow() {
        val decor = window.decorView
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            decor.windowInsetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun tabLabel(tab: HomeTab) = when (tab) {
        HomeTab.LIBRARY -> AppText.HOME_TAB_LIBRARY.text()
        HomeTab.CONNECT -> AppText.HOME_TAB_CONNECT.text()
        HomeTab.SETTINGS -> AppText.HOME_TAB_SETTINGS.text()
    }

    // ── HomeRenderer ────────────────────────────────────────────────────────
    override fun render(state: HomeState) {
        if (!uiReady) return
        for ((tab, button) in tabButtons) {
            val active = tab == state.tab
            button.background = if (active) UiStyle.selected(this, 12)
            else UiStyle.ripple(this, UiStyle.SURFACE, 12)
            button.setTextColor(if (active) UiStyle.BRAND_INK else UiStyle.TEXT)
        }
        if (activeTab != state.tab) {
            if (activeTab == HomeTab.CONNECT) connectionsPage.stopScan()
            activeTab = state.tab
            if (state.tab == HomeTab.CONNECT) connectionsPage.startScan()
            if (state.tab == HomeTab.SETTINGS) settingsPage.refresh()
            // present() 会重新查库，只在真正切入片源库时调用，避免每次焦点变化都刷新。
            if (state.tab == HomeTab.LIBRARY) libraryPage.present()
        }
        libraryPage.visibility = if (state.tab == HomeTab.LIBRARY) View.VISIBLE else View.GONE
        (connectionsPage.parent as? View)?.visibility =
            if (state.tab == HomeTab.CONNECT) View.VISIBLE else View.GONE
        (settingsPage.parent as? View)?.visibility =
            if (state.tab == HomeTab.SETTINGS) View.VISIBLE else View.GONE
        when (state.tab) {
            HomeTab.CONNECT -> connectionsPage.setFocus(state.focus)
            HomeTab.SETTINGS -> settingsPage.setFocus(state.focus)
            else -> Unit
        }
        // 状态文字由各板块在内容区内自行显示，首页不再占一行底部提示。
    }

    // ── HomePlatform ────────────────────────────────────────────────────────
    override fun launch(route: PlayerRoute) = PlayerLauncher.launch(this, route)

    override fun command(key: String, itemId: String) {
        when (key) {
            HomeCommands.ADD_VIDEOS -> openVideoPicker()
            HomeCommands.RESCAN -> {
                controller.dispatch(HomeInput.SelectTab(HomeTab.CONNECT))
                connectionsPage.stopScan()
                connectionsPage.startScan()
            }
            HomeCommands.OPEN_MANUAL_ADDRESS -> {
                controller.dispatch(HomeInput.SelectTab(HomeTab.CONNECT))
                connectionsPage.focusManualInput()
            }
            HomeCommands.CONNECT_DEVICE -> connectionsPage.connectDevice(itemId)
            HomeCommands.REPAIR_HOST -> connectionsPage.repairHost(itemId.substringAfter('|'))
            HomeCommands.TOGGLE_LANGUAGE -> {
                AppLanguage.select(this, !AppText.isEnglish())
                recreate()
            }
            HomeCommands.OPEN_WATCH_SETTINGS_HELP ->
                toast(AppText.HOME_SETTINGS_WATCH_HINT.text())
            HomeCommands.OPEN_GAMEPAD_MAPPING ->
                startActivity(Intent(this, GamepadMappingActivity::class.java))
        }
    }

    override fun exit() = finish()

    /** View 的点击也走语义分发，保证触摸与手柄/凝视是同一条链路。 */
    fun dispatchItem(item: HomeItem) {
        val index = controller.state.items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            controller.dispatch(HomeInput.Focus(index))
            controller.dispatch(HomeInput.Confirm)
        } else {
            when (val intent = item.intent) {
                is HomeIntent.Launch -> launch(intent.route)
                is HomeIntent.Command -> command(intent.key, item.id)
            }
        }
    }

    // ── 输入：D-pad / 手柄方向键 → HomeInput ───────────────────────────────
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus !is EditText) {
            // 与 VR 菜单共用 NavKeyRouter：同一物理键在内外产生同一导航语义，
            // 改一次「手柄按键」映射表，2D 首页与 VR 菜单同时生效。
            val action = NavKeyRouter.resolve(event.keyCode)
                ?: return super.dispatchKeyEvent(event)
            // DOWN 执行动作、UP 只消费：既防 Android View 系统再触发一次焦点/点击，
            // 也与 VR 菜单的 DOWN/UP 处理完全对齐。方向键允许长按连续移动。
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.repeatCount == 0 || NavKeyRouter.isRepeatable(action))) {
                when (action) {
                    // 首页右上 VR 按钮的手柄快捷方式，与 VR 内「呼出菜单」同义。
                    NavAction.MENU, NavAction.TOGGLE -> PlayerLauncher.launchVrMenu(this)
                    // 走宿主的分层返回，保留 libraryPage 的多选 / 搜索退出逻辑。
                    NavAction.BACK, NavAction.EXIT -> handleBack()
                    else -> NavKeyRouter.toHomeInput(action)?.let { controller.dispatch(it) }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 4.11：手柄轴输入（十字键 HAT / 左摇杆）也走 [NavKeyRouter] 的语义链。
     * 很多廉价蓝牙手柄的十字键只发 HAT 轴、不发 KEYCODE_DPAD_*，旧版 2D 首页
     * 只处理 KeyEvent，手柄在首页等于没有方向键；VR 菜单里更糟 —— 轴事件漏进
     * 播放层变成快进 / 转视角。现在两处共用同一个 [MenuAxisRouter]。
     * 搜索框（EditText）聚焦时不劫持，避免影响输入法与光标。
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (currentFocus !is EditText && isGamepadMotion(event)) {
            handleHomeAxis(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepadMotion(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.device?.let { it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK) } == true

    private fun handleHomeAxis(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            menuAxis.clear(); cancelHomeAxisRepeat(); return
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return
        for (i in 0 until event.historySize) {
            val action = menuAxis.feed(
                event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_X, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_Y, i),
                event.getHistoricalEventTime(i))
            if (action != null) dispatchHomeAxis(action)
        }
        val action = menuAxis.feed(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.eventTime)
        if (action != null) dispatchHomeAxis(action)
        scheduleHomeAxisRepeat()
    }

    private fun dispatchHomeAxis(action: NavAction) {
        if (!::controller.isInitialized) return
        NavKeyRouter.toHomeInput(action)?.let { controller.dispatch(it) }
    }

    private val homeAxisRepeat = object : Runnable {
        override fun run() {
            menuAxisRepeatScheduled = false
            if (closing || currentFocus is EditText) { menuAxis.clear(); return }
            val action = menuAxis.repeat(SystemClock.uptimeMillis())
            if (action != null) dispatchHomeAxis(action)
            scheduleHomeAxisRepeat()
        }
    }

    private fun scheduleHomeAxisRepeat() {
        if (closing || menuAxis.held() == null) { cancelHomeAxisRepeat(); return }
        if (menuAxisRepeatScheduled) return
        menuAxisRepeatScheduled = true
        uiHandler.postDelayed(homeAxisRepeat, MenuAxisRouter.REPEAT_INTERVAL_MS)
    }

    private fun cancelHomeAxisRepeat() {
        if (menuAxisRepeatScheduled) {
            uiHandler.removeCallbacks(homeAxisRepeat)
            menuAxisRepeatScheduled = false
        }
    }

    @Deprecated("Legacy Android back button")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (controller.state.tab == HomeTab.LIBRARY && libraryPage.handleBack()) return
        controller.dispatch(HomeInput.Back)
    }

    // ── 片源导入 ────────────────────────────────────────────────────────────
    private fun openVideoPicker() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQUEST_ADD_VIDEOS)
        } catch (error: Exception) {
            toast(AppText.COULD_NOT_OPEN_THE_FILE_PICKER.text(error.message))
        }
    }

    @Deprecated("Uses the platform picker without another Activity dependency")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ADD_VIDEOS || resultCode != RESULT_OK || data == null) return
        val uris = linkedSetOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) return
        io.execute {
            var added = 0
            for (uri in uris) {
                if (closing) break
                runCatching {
                    if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
                        data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    val name = runCatching {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: AppText.LOCAL_VIDEO.text()
                    playlist.add(uri.toString(), name)
                    added++
                }
            }
            runOnUiThread {
                if (!closing) {
                    libraryPage.refresh()
                    toast(AppText.PROCESSED_VIDEOS_DUPLICATE_FILES_ARE_SKIPPED.text(added, uris.size))
                }
            }
        }
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        // 映射页保存后返回首页即生效，不必重启进程。
        GamepadBindings.reload(this)
        // 从播放页返回、或系统栏被临时唤出后，重新压回沉浸全屏。
        configureWindow()
        if (activeTab == HomeTab.CONNECT) connectionsPage.startScan()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureWindow()
    }

    override fun onPause() {
        connectionsPage.stopScan()
        menuAxis.clear()
        cancelHomeAxisRepeat()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("home_tab", controller.state.tab.name)
        outState.putInt("home_focus", controller.state.focus)
        outState.putString("home_status", controller.state.status)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controller.isInitialized) controller.dispatch(HomeInput.Status(controller.state.status))
        configureWindow()
    }

    override fun onDestroy() {
        closing = true
        menuAxis.clear()
        uiHandler.removeCallbacksAndMessages(null)
        backNavigation?.setEnabled(false)
        if (::connectionsPage.isInitialized) connectionsPage.release()
        if (::libraryPage.isInitialized) libraryPage.release()
        io.shutdown()
        if (::covers.isInitialized) covers.close {
            if (::playlist.isInitialized) playlist.closeWhenIdle()
        } else if (::playlist.isInitialized) playlist.closeWhenIdle()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_ADD_VIDEOS = 41
    }
}
