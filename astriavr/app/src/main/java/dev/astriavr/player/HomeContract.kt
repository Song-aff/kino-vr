package dev.astriavr.player

/**
 * 首页契约层 —— **纯 Kotlin，零 Android View 依赖**（H0）。
 *
 * 这一层是为「下一步把菜单 VR 化 + 适配手柄」特意留出来的：
 *
 *  - [HomeState] / [HomeItem] 是纯数据，2D View 和未来的 GL 双眼场景画的是同一份数据；
 *  - [HomeInput] 是**语义输入**（方向 / 确认 / 返回），触摸、D-pad、手柄摇杆、
 *    凝视点选最终都映射成同一种输入，业务层不需要知道事件来自哪里；
 *  - [HomeController] 只做状态转换并输出 [HomeEffect]，不碰任何 View；
 *  - [HomeRenderer] / [HomePlatform] 是可替换的渲染与平台能力接口。
 *    VR 化 = 换一个 Renderer（把 items 画进左右眼 viewport），Controller 一行不改。
 *
 * 因此这里**刻意不引入** Fragment / Jetpack Navigation / Compose / XML layout /
 * ViewModel 之类把 UI 绑死在 Android View 树上的框架：它们未来要么无法绘制进
 * GL 双 viewport，要么要求重写整个导航状态机。
 */
enum class HomeTab { LIBRARY, CONNECT, SETTINGS }

/** 一级焦点移动方向。LEFT/RIGHT 切一级板块，UP/DOWN 在板块内移动条目焦点。 */
enum class HomeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * 一个可聚焦条目。**只能放数据**，不要放 View / Drawable / click listener。
 * 未来 VR 场景会用同一份 title/subtitle 在空间里画卡片。
 */
data class HomeItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val badge: String = "",
    val intent: HomeIntent
)

/** 条目被确认后的意图：要么进播放页，要么交给宿主执行一个具名命令。 */
sealed class HomeIntent {
    data class Launch(val route: PlayerRoute) : HomeIntent()
    data class Command(val key: String) : HomeIntent()
}

object HomeCommands {
    const val ADD_VIDEOS = "home.addVideos"
    const val OPEN_MANUAL_ADDRESS = "home.openManualAddress"
    const val RESCAN = "home.rescan"
    const val TOGGLE_LANGUAGE = "home.toggleLanguage"
    const val OPEN_WATCH_SETTINGS_HELP = "home.watchSettingsHelp"
    const val OPEN_GAMEPAD_MAPPING = "home.gamepadMapping"
    const val REPAIR_HOST = "home.repairHost"
    const val CONNECT_DEVICE = "home.connectDevice"
}

/** 首页完整状态。可序列化到 Bundle（见 HomeActivity），进程重建后焦点与板块不丢。 */
data class HomeState(
    val tab: HomeTab = HomeTab.LIBRARY,
    val items: List<HomeItem> = emptyList(),
    val focus: Int = 0,
    val status: String = "",
    val version: String = ""
)

sealed class HomeInput {
    data class SelectTab(val tab: HomeTab) : HomeInput()
    /** 数据源（扫描结果 / 设置项）更新；只在当前板块时影响可见列表。 */
    data class Items(val tab: HomeTab, val items: List<HomeItem>) : HomeInput()
    data class Navigate(val direction: HomeDirection) : HomeInput()
    data class Focus(val index: Int) : HomeInput()
    data class Status(val text: String) : HomeInput()
    object Confirm : HomeInput()
    object Back : HomeInput()
}

sealed class HomeEffect {
    data class Render(val state: HomeState) : HomeEffect()
    data class Launch(val route: PlayerRoute) : HomeEffect()
    data class Command(val key: String, val itemId: String) : HomeEffect()
    object Exit : HomeEffect()
}

/** 渲染契约：未来的 VrHomeRenderer 实现同一个接口即可复用整套导航逻辑。 */
interface HomeRenderer {
    fun render(state: HomeState)
}

/** 平台能力契约：启动播放、执行命令、退出。VR 化时可换成 GL 宿主实现。 */
interface HomePlatform {
    fun launch(route: PlayerRoute)
    fun command(key: String, itemId: String)
    fun exit()
}

/**
 * 纯逻辑状态机。emit 是唯一出口，方便单元测试直接收集 effect。
 * `itemsByTab` 缓存各板块数据，切板块时立刻恢复，不必重新扫描。
 */
class HomeController(private val emit: (HomeEffect) -> Unit) {
    private val itemsByTab = mutableMapOf<HomeTab, List<HomeItem>>()
    var state: HomeState = HomeState()
        private set

    fun restore(saved: HomeState) {
        state = saved.copy(focus = saved.focus.coerceAtLeast(0))
        itemsByTab[state.tab] = state.items
        render()
    }

    fun dispatch(input: HomeInput) {
        when (input) {
            is HomeInput.SelectTab -> switchTab(input.tab)
            is HomeInput.Items -> {
                itemsByTab[input.tab] = input.items
                if (state.tab == input.tab) {
                    state = state.copy(items = input.items,
                        focus = state.focus.coerceIn(0, (input.items.size - 1).coerceAtLeast(0)))
                    render()
                }
            }
            is HomeInput.Navigate -> move(input.direction)
            is HomeInput.Focus -> {
                if (state.items.isEmpty()) return
                state = state.copy(focus = input.index.coerceIn(0, state.items.size - 1))
                render()
            }
            is HomeInput.Status -> {
                state = state.copy(status = input.text)
                render()
            }
            HomeInput.Confirm -> confirm()
            HomeInput.Back -> back()
        }
    }

    private fun switchTab(tab: HomeTab) {
        val items = itemsByTab[tab].orEmpty()
        state = state.copy(tab = tab, items = items, focus = 0)
        render()
    }

    private fun move(direction: HomeDirection) {
        when (direction) {
            HomeDirection.LEFT, HomeDirection.RIGHT -> {
                val tabs = HomeTab.values()
                val step = if (direction == HomeDirection.RIGHT) 1 else -1
                switchTab(tabs[(state.tab.ordinal + step + tabs.size) % tabs.size])
            }
            HomeDirection.UP, HomeDirection.DOWN -> {
                if (state.items.isEmpty()) return
                val step = if (direction == HomeDirection.DOWN) 1 else -1
                val size = state.items.size
                state = state.copy(focus = (state.focus + step + size) % size)
                render()
            }
        }
    }

    private fun confirm() {
        val item = state.items.getOrNull(state.focus) ?: return
        when (val intent = item.intent) {
            is HomeIntent.Launch -> emit(HomeEffect.Launch(intent.route))
            is HomeIntent.Command -> emit(HomeEffect.Command(intent.key, item.id))
        }
    }

    private fun back() {
        if (state.tab != HomeTab.LIBRARY) switchTab(HomeTab.LIBRARY) else emit(HomeEffect.Exit)
    }

    private fun render() = emit(HomeEffect.Render(state))
}

/** 把 effect 分发给可替换的渲染层与平台层。VR 化时这个分发器原样保留。 */
class HomeHost(
    private val renderer: HomeRenderer,
    private val platform: HomePlatform
) {
    fun on(effect: HomeEffect) {
        when (effect) {
            is HomeEffect.Render -> renderer.render(effect.state)
            is HomeEffect.Launch -> platform.launch(effect.route)
            is HomeEffect.Command -> platform.command(effect.key, effect.itemId)
            HomeEffect.Exit -> platform.exit()
        }
    }
}
