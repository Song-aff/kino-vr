package dev.astriavr.player

/**
 * 进入播放页的**唯一意图描述**（H0）。
 *
 * 纯 Kotlin data，不含 Context / View / Activity / Fragment / Bundle，
 * 因此 2D 首页、未来的 VR 双眼首页、手柄/凝视输入层都能构造同一种意图。
 * 与 Android Intent 的转换只发生在 [PlayerLauncher] 一处。
 *
 * 约定：首页只负责「选什么」，播放页只负责「怎么播」；首页不得传
 * IPD / 投影 / 亮度等观看参数，那些属于播放页内部状态。
 */
sealed class PlayerRoute {
    /** 播放一个已存在于播放列表里的片源（本地 content:// 或局域网 http(s)://）。 */
    data class Video(val uri: String, val name: String?, val fromStart: Boolean) : PlayerRoute()

    /** 连接一台 Sunshine 串流主机；播放页会自行 probe 47989 拿完整能力集。 */
    data class Stream(val hostIp: String, val hostName: String) : PlayerRoute()

    /**
     * H4/S4a：进入 VR 模式但**不立即起片**。播放页原地切到大屏银幕，先把 VR 菜单
     * 画到那块 world-locked 平面上，选片后才在同一块平面换成视频并收成下沿控制条。
     * 不新建 Activity，VrView / ExoPlayer / GamepadInput / 串流会话全部原地复用。
     */
    object VrMenu : PlayerRoute()
}
