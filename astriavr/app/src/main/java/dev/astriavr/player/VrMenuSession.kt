package dev.astriavr.player

/**
 * H4/S4a：VR 菜单的**纯 Kotlin 状态层**，零 android.view / Context 依赖。
 *
 * 直接复用 H0 的 [HomeController]：三个 [HomeTab] 在 VR 里显示为
 * 片源库 / 播放 / 观看参数，tab 环绕、焦点上下移动、确认与返回逻辑全部原样继承。
 * [HomeRenderer] 换成 [VrMenuRenderer]（输出 Bitmap 纹理而不是 View 树），
 * Controller 一行不改 —— 这正是 ADR-001 当初留 HomeRenderer 接口的目的。
 *
 * 与 2D 首页的关键差别：`HomeEffect.Launch` **不再启动新 Activity**，宿主原地
 * openSource 后直接关掉菜单覆盖层，进入页面1 的沉浸双眼播放（4.12 起不再停在
 * 下沿控制条 —— 用户要的是「选片即沉浸、无常驻进度条」，要调进度再按菜单键呼出）。
 */

/** 菜单条目的具名命令，宿主按 key 执行播放控制或观看参数调整。 */
object VrMenuCommands {
    const val PLAY_PAUSE = "vr.playPause"
    const val SEEK_BACK = "vr.seekBack"
    const val SEEK_FORWARD = "vr.seekForward"
    const val SEEK_BACK_LONG = "vr.seekBackLong"
    const val SEEK_FORWARD_LONG = "vr.seekForwardLong"
    const val SHOW_MENU = "vr.showMenu"
    const val EXIT_HOME = "vr.exitHome"
    const val IPD = "vr.ipd"
    const val SCREEN_SIZE = "vr.screenSize"
    const val SCREEN_FLIP = "vr.screenFlip"
    /** 4.13：两圆垂直位置（居中 / 吸底），头戴底部支撑垫高手机时用吸底对准镜片。 */
    const val VERTICAL_ALIGN = "vr.verticalAlign"
    const val FOV = "vr.fov"
    const val SPEED = "vr.speed"
    // 4.10：戴镜时改片源模式的四项。自动识别不准的片源要在头显里直接切，
    // 不能要求摘镜回 2D 设置页；全部沿用「A 循环到下一档」的既有语义。
    const val VIEWING_MODE = "vr.viewingMode"
    const val PROJECTION = "vr.projection"
    const val LAYOUT = "vr.layout"
    const val SWAP_EYES = "vr.swapEyes"
    const val BACK_TO_PLAY = "vr.backToPlay"
    const val NOOP = "vr.noop"
}

/** 语义输入：触摸、D-pad、手柄摇杆、凝视点选最终都归约成这几种。 */
sealed class VrMenuInput {
    object Up : VrMenuInput()
    object Down : VrMenuInput()
    object Left : VrMenuInput()
    object Right : VrMenuInput()
    object Confirm : VrMenuInput()
    object Back : VrMenuInput()
    object Toggle : VrMenuInput()
    object Exit : VrMenuInput()
    /** 4.10：一键直达「观看参数」页（可配置手柄键，播放中呼出）。 */
    object Params : VrMenuInput()
    /** 4.12：菜单键在菜单态 = 收起菜单回到播放；与「退出到首页」分开。 */
    object CloseMenu : VrMenuInput()
    /** 4.12：Start 键在菜单态 = 跳到片源库（视频列表）页签。 */
    object OpenLibrary : VrMenuInput()
}

/** 片源库条目的最小数据，避免契约层依赖 SQLite 的 PlaylistStore.Entry。 */
data class VrMenuEntry(val id: String, val name: String, val uri: String, val subtitle: String = "")

class VrMenuSession {
    /** 选片：原地起播，不跳 Activity。 */
    var onPlay: (PlayerRoute.Video) -> Unit = {}
    /** 具名命令 + 步进方向（+1 / -1）：播放控制与观看参数都走这里。 */
    var onCommand: (String, Int) -> Unit = { _, _ -> }
    /** B / Menu 的兜底出口，永远能回 2D 首页，避免人被卡在头显里。 */
    var onExit: () -> Unit = {}
    /** 4.10：参数页的「返回播放」。从普通播放页呼出的浮层靠它回到原视频。 */
    var onResumePlayback: () -> Unit = {}
    /** 4.12：菜单键收起菜单。宿主决定回播放（从页面1呼出）还是回 2D 首页（从首页进入）。 */
    var onCloseMenu: () -> Unit = {}
    /** 状态或模式变化，宿主据此重绘并上传菜单纹理。 */
    var onChange: () -> Unit = {}

    var mode: VrMenuMode = VrMenuMode.MENU
        private set
    var extras: VrMenuExtras = VrMenuExtras()
        private set
    /** true = 当前停在从播放态呼出的参数页，B 直接回播放而不是退出 VR。 */
    var paramsOnly: Boolean = false
        private set
    val state: HomeState get() = controller.state

    private val controller = HomeController { effect ->
        when (effect) {
            is HomeEffect.Render -> onChange()
            is HomeEffect.Launch -> {
                val route = effect.route
                if (route is PlayerRoute.Video) {
                    setMode(VrMenuMode.BAR)
                    onPlay(route)
                }
            }
            is HomeEffect.Command -> handleCommand(effect.key, 1)
            HomeEffect.Exit -> onExit()
        }
    }

    init {
        // 三个板块的数据一次性灌进 Controller 的缓存，切板块时零延迟恢复。
        controller.dispatch(HomeInput.Items(HomeTab.CONNECT, playbackItems()))
        controller.dispatch(HomeInput.Items(HomeTab.SETTINGS, viewingItems()))
        controller.dispatch(HomeInput.Items(HomeTab.LIBRARY, libraryItems(emptyList())))
        controller.dispatch(HomeInput.SelectTab(HomeTab.LIBRARY))
    }

    /** 片源库来自 PlaylistStore；为空时给一条不可操作的提示项，焦点不会越界。 */
    fun setLibrary(entries: List<VrMenuEntry>) {
        controller.dispatch(HomeInput.Items(HomeTab.LIBRARY, libraryItems(entries)))
    }

    /** 播放进度 / 状态 / 参数当前值；顺带重建观看参数项的副标题。 */
    fun setExtras(value: VrMenuExtras) {
        if (extras == value) return
        extras = value
        controller.dispatch(HomeInput.Items(HomeTab.SETTINGS, viewingItems()))
        if (controller.state.tab != HomeTab.SETTINGS) onChange()
    }

    fun handle(input: VrMenuInput): Boolean {
        if (input == VrMenuInput.Params) { openParams(); return true }
        // 参数页里上下 / 确认仍算「停在参数页」；一旦左右切板块或收起控制条，
        // 就回到普通菜单语义，B 不再被解释成「返回播放」。
        if (paramsOnly && (input == VrMenuInput.Left || input == VrMenuInput.Right ||
                input == VrMenuInput.Toggle)) clearParams()
        when (input) {
            VrMenuInput.Up -> controller.dispatch(HomeInput.Navigate(HomeDirection.UP))
            VrMenuInput.Down -> controller.dispatch(HomeInput.Navigate(HomeDirection.DOWN))
            // 左右键始终切板块，与 2D 首页 / HomeController 的语义保持一致；
            // 数值调整统一用 A（循环到下一档），避免两套焦点逻辑。
            VrMenuInput.Left -> controller.dispatch(HomeInput.Navigate(HomeDirection.LEFT))
            VrMenuInput.Right -> controller.dispatch(HomeInput.Navigate(HomeDirection.RIGHT))
            VrMenuInput.Confirm -> controller.dispatch(HomeInput.Confirm)
            VrMenuInput.Back -> back()
            VrMenuInput.Toggle -> setMode(if (mode == VrMenuMode.MENU) VrMenuMode.BAR else VrMenuMode.MENU)
            VrMenuInput.Exit -> onExit()
            VrMenuInput.CloseMenu -> onCloseMenu()
            VrMenuInput.OpenLibrary -> {
                controller.dispatch(HomeInput.SelectTab(HomeTab.LIBRARY))
                setMode(VrMenuMode.MENU)
            }
            VrMenuInput.Params -> Unit // 已在前面处理
        }
        return true
    }

    /** 一键直达：菜单铺满 + 观看参数板块 + 焦点落在最常用的「观看模式」。 */
    fun openParams() {
        paramsOnly = true
        controller.dispatch(HomeInput.SelectTab(HomeTab.SETTINGS))
        controller.dispatch(HomeInput.Focus(0))
        setMode(VrMenuMode.MENU)
        onChange()
    }

    /** 参数页返回播放：收成下沿控制条，并通知宿主（普通播放页浮层在此退出）。 */
    fun resumePlayback() {
        clearParams()
        setMode(VrMenuMode.BAR)
        onResumePlayback()
    }

    private fun clearParams() {
        if (!paramsOnly) return
        paramsOnly = false
        onChange()
    }

    private fun back() {
        // 4.10：从播放态呼出的参数页，一次 B 就回视频，不让人在头显里多点一层。
        if (paramsOnly) { resumePlayback(); return }
        // 播放态先收回到菜单，再按一次 B 才退出 —— 给误触留一层缓冲。
        if (mode == VrMenuMode.BAR) { setMode(VrMenuMode.MENU); return }
        // LIBRARY 时 Controller.back() 会 emit(Exit)；其余板块先退回片源库。
        controller.dispatch(HomeInput.Back)
    }

    private fun setMode(value: VrMenuMode) {
        if (mode == value) return
        mode = value
        onChange()
    }

    private fun handleCommand(key: String, delta: Int) {
        if (key == VrMenuCommands.BACK_TO_PLAY) { resumePlayback(); return }
        when (key) {
            VrMenuCommands.SHOW_MENU -> setMode(VrMenuMode.MENU)
            VrMenuCommands.EXIT_HOME -> onExit()
            VrMenuCommands.NOOP -> Unit
            else -> onCommand(key, delta)
        }
        onChange()
    }

    // ── 三个板块的条目 ───────────────────────────────────────────────────────

    private fun libraryItems(entries: List<VrMenuEntry>): List<HomeItem> =
        if (entries.isEmpty()) listOf(
            HomeItem("vr|empty", AppText.VR_MENU_EMPTY.text(), "",
                "", HomeIntent.Command(VrMenuCommands.NOOP)))
        else entries.map { entry ->
            HomeItem("vr|${entry.id}", entry.name, entry.subtitle, "",
                HomeIntent.Launch(PlayerRoute.Video(entry.uri, entry.name, false)))
        }

    private fun playbackItems(): List<HomeItem> = listOf(
        item(VrMenuCommands.PLAY_PAUSE, AppText.VR_MENU_PLAY_PAUSE.text()),
        item(VrMenuCommands.SEEK_BACK, AppText.VR_MENU_SEEK_BACK.text()),
        item(VrMenuCommands.SEEK_FORWARD, AppText.VR_MENU_SEEK_FORWARD.text()),
        item(VrMenuCommands.SEEK_BACK_LONG, AppText.VR_MENU_SEEK_BACK_LONG.text()),
        item(VrMenuCommands.SEEK_FORWARD_LONG, AppText.VR_MENU_SEEK_FORWARD_LONG.text()),
        item(VrMenuCommands.SHOW_MENU, AppText.VR_MENU_SHOW_MENU.text()),
        item(VrMenuCommands.EXIT_HOME, AppText.VR_MENU_EXIT_HOME.text())
    )

    /**
     * 4.10：模式类参数排在最前 —— 用户戴镜时最常做的就是给识别错的片源换
     * 观看模式 / 投影 / 布局。原有 5 项保持不动，末尾固定放「返回播放」，
     * 因此无论列表滚到哪一屏，总能沿一个方向找到出口。
     */
    private fun viewingItems(): List<HomeItem> = listOf(
        item(VrMenuCommands.VIEWING_MODE, AppText.VIEWING_MODE.text()),
        item(VrMenuCommands.PROJECTION, AppText.SOURCE_PROJECTION.text()),
        item(VrMenuCommands.LAYOUT, AppText.VIDEO_LAYOUT.text()),
        item(VrMenuCommands.SWAP_EYES, AppText.SWAP_EYES.text()),
        item(VrMenuCommands.IPD, AppText.VR_MENU_IPD.text()),
        item(VrMenuCommands.VERTICAL_ALIGN, AppText.VERTICAL_POSITION.text()),
        item(VrMenuCommands.SCREEN_SIZE, AppText.VR_MENU_SCREEN_SIZE.text()),
        item(VrMenuCommands.SCREEN_FLIP, AppText.VR_MENU_SCREEN_FLIP.text()),
        item(VrMenuCommands.FOV, AppText.VR_MENU_FOV.text()),
        item(VrMenuCommands.SPEED, AppText.VR_MENU_SPEED.text()),
        item(VrMenuCommands.BACK_TO_PLAY, AppText.VR_MENU_BACK_TO_PLAY.text())
    )

    private fun item(key: String, title: String) = HomeItem(
        "vr|$key", title, extras.values[key].orEmpty(), "",
        HomeIntent.Command(key))
}
