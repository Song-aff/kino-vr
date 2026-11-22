package dev.astriavr.player

import android.content.Context
import android.widget.LinearLayout

/**
 * 首页「设置」板块：**只放全局配置**。
 * 观看参数（IPD / 投影 / 畸变 / 亮度 / 银幕）留在播放页，因为要边看边调。
 * 条目统一走 [HomeItem]，VR 化时不需要重写任何业务逻辑。
 */
class HomeSettingsPage(
    context: Context,
    private val onItems: (List<HomeItem>) -> Unit
) : LinearLayout(context) {
    private val dp = { value: Int -> UiStyle.dp(context, value) }
    private val list = HomeItemListView(context, { item -> (context as? HomeActivity)?.dispatchItem(item) })

    init {
        orientation = VERTICAL
        addView(UiStyle.label(context, AppText.SETTINGS.text(), 15f, bold = true))
        addView(UiStyle.label(context, AppText.HOME_SETTINGS_WATCH_HINT.text(), 11f, UiStyle.MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        }, LayoutParams(-1, -2))
        addView(list, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        publish()
    }

    fun refresh() = publish()

    private fun publish() {
        val language = if (AppText.isEnglish()) AppText.LANGUAGE_NAME.text() else "简体中文"
        // 顶栏不再显示版本号，改在「关于」里保留，方便区分真机装的是哪一版 APK。
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("").orEmpty()
        val items = listOf(
            HomeItem("cmd|language", AppText.HOME_SETTINGS_LANGUAGE.text(),
                "$language · " + AppText.HOME_SETTINGS_LANGUAGE_HINT.text(),
                AppText.HOME_BADGE_ACTION.text(), HomeIntent.Command(HomeCommands.TOGGLE_LANGUAGE)),
            HomeItem("cmd|watch", AppText.HOME_SETTINGS_WATCH.text(),
                AppText.HOME_SETTINGS_WATCH_HINT.text(), AppText.HOME_BADGE_ACTION.text(),
                HomeIntent.Command(HomeCommands.OPEN_WATCH_SETTINGS_HELP)),
            HomeItem("cmd|gamepad", AppText.HOME_SETTINGS_GAMEPAD.text(),
                AppText.HOME_SETTINGS_GAMEPAD_HINT.text(), AppText.HOME_BADGE_ACTION.text(),
                HomeIntent.Command(HomeCommands.OPEN_GAMEPAD_MAPPING)),
            HomeItem("cmd|add", AppText.HOME_ADD_LOCAL.text(),
                AppText.HOME_ADD_LOCAL_HINT.text(), AppText.HOME_BADGE_ACTION.text(),
                HomeIntent.Command(HomeCommands.ADD_VIDEOS)),
            HomeItem("cmd|connect", AppText.HOME_CONNECT_TITLE.text(),
                AppText.HOME_CONNECT_HINT.text(), AppText.HOME_BADGE_ACTION.text(),
                HomeIntent.Command(HomeCommands.RESCAN)),
            HomeItem("info|about", AppText.HOME_SETTINGS_ABOUT.text(),
                AppText.HOME_SETTINGS_ABOUT_HINT.text() +
                    if (version.isBlank()) "" else " · KINO VR $version", "",
                HomeIntent.Command(HomeCommands.OPEN_WATCH_SETTINGS_HELP))
        )
        list.setItems(items)
        onItems(items)
    }

    fun setFocus(index: Int) = list.setFocus(index)
}
