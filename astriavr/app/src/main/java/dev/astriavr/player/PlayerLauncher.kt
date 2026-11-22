package dev.astriavr.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * [PlayerRoute] 与 Android Intent 之间**唯一**的转换点（H0）。
 *
 * 首页（含未来的 VR 首页）只构造 Route；播放页只解析 Route。
 * 这样新增一种播放来源时，不需要改任何 View / GL 绘制代码。
 */
object PlayerLauncher {
    private const val KIND = "astriavr.route.kind"
    private const val KIND_VIDEO = "video"
    private const val KIND_STREAM = "stream"
    private const val KIND_VR_MENU = "vrmenu"
    private const val URI = "astriavr.route.uri"
    private const val NAME = "astriavr.route.name"
    private const val FROM_START = "astriavr.route.fromStart"
    private const val HOST_IP = "astriavr.route.hostIp"
    private const val HOST_NAME = "astriavr.route.hostName"
    const val FROM_HOME = "astriavr.route.fromHome"

    fun intent(context: Context, route: PlayerRoute): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(FROM_HOME, true)
            when (route) {
                is PlayerRoute.Video -> {
                    putExtra(KIND, KIND_VIDEO)
                    putExtra(URI, route.uri)
                    putExtra(NAME, route.name)
                    putExtra(FROM_START, route.fromStart)
                }
                is PlayerRoute.Stream -> {
                    putExtra(KIND, KIND_STREAM)
                    putExtra(HOST_IP, route.hostIp)
                    putExtra(HOST_NAME, route.hostName)
                }
                PlayerRoute.VrMenu -> putExtra(KIND, KIND_VR_MENU)
            }
        }

    fun launch(activity: Activity, route: PlayerRoute) = activity.startActivity(intent(activity, route))

    /** 首页右上角「进入 VR」入口：与选片不同，它只带一个模式标记，不带片源。 */
    fun launchVrMenu(activity: Activity) = launch(activity, PlayerRoute.VrMenu)

    /** 没有 Route 时返回 null：兼容直接从 instrumentation / 外部 Intent 启动播放页。 */
    fun from(intent: Intent?): PlayerRoute? {
        val source = intent ?: return null
        return when (source.getStringExtra(KIND)) {
            KIND_VIDEO -> source.getStringExtra(URI)?.let {
                PlayerRoute.Video(it, source.getStringExtra(NAME), source.getBooleanExtra(FROM_START, false))
            }
            KIND_STREAM -> source.getStringExtra(HOST_IP)?.let {
                PlayerRoute.Stream(it, source.getStringExtra(HOST_NAME).orEmpty())
            }
            KIND_VR_MENU -> PlayerRoute.VrMenu
            else -> null
        }
    }

    /** 播放页退出：无论栈里有没有 Home，都保证回到首页而不是桌面。 */
    fun goHome(activity: Activity) {
        val home = Intent(activity, HomeActivity::class.java)
        if (activity.isTaskRoot) {
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(home)
        activity.finish()
    }
}

/** 供连接中心手填 URL 使用，避免各页面各自拼 Uri。 */
fun parseMediaUri(raw: String): Uri? {
    val uri = Uri.parse(raw.trim())
    return if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) uri else null
}
