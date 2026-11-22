package dev.astriavr.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Sunshine / Moonlight 控制面（S3b 阶段 1）。
 *
 * 只做「发现主机 → serverinfo 握手 → 能力/配对状态展示」，纯 Kotlin +
 * 平台自带 HttpURLConnection / XmlPullParser，零新增依赖、零 JNI、零 NDK。
 * 视频流接收（RTP / FEC / depacketize）依赖 moonlight-common-c 的
 * libmoonlight-core.so，属于 S3b 阶段 2：本机工具链无 NDK，该库将从
 * 官方 GPLv3 release APK 中提取（见 README）。
 *
 * 协议依据：moonlight-android 的 NvHTTP.java —— GET /serverinfo 走 HTTP
 * 47989，统一追加 uniqueid + uuid；配对后走 HTTPS 47984（HttpsPort 字段）。
 * 实测 Sunshine 0.23 响应字段见 README。注意响应里的 LocalIP 是 127.0.0.1，
 * 主机地址只能取 UDP 发现包里的 IP，绝不能用 serverinfo 自带字段。
 */
data class StreamHost(
    val ip: String,
    val httpPort: Int,
    val name: String,
    val uniqueId: String,
    val httpsPort: Int,
    val pairStatus: Int,
    val state: String,
    val currentGame: Int,
    val codecMask: Int,
    val maxLumaHevc: Long,
    val appVersion: String
) {
    val paired: Boolean get() = pairStatus == 1
    val busy: Boolean get() = currentGame != 0
    val supportsH264: Boolean get() = codecMask and CODEC_H264 != 0
    val supportsHevc: Boolean get() = codecMask and CODEC_HEVC != 0
    val supportsAv1: Boolean get() = codecMask and CODEC_AV1_MASK != 0

    /** 仅用于展示：Sunshine 的 AV1 高位定义随版本变化，不做硬性能力判定。 */
    val codecLabel: String
        get() {
            val out = ArrayList<String>()
            if (supportsH264) out.add("H.264")
            if (supportsHevc) out.add("HEVC")
            if (supportsAv1) out.add("AV1")
            if (out.isEmpty()) out.add("未知")
            return out.joinToString(" · ")
        }

    companion object {
        const val CODEC_H264 = 0x0001
        const val CODEC_HEVC = 0x0300          // HEVC Main(0x100) + Main10(0x200)
        const val CODEC_AV1_MASK = 0x1C0000    // Sunshine AV1 相关位，仅展示
    }
}

object StreamControl {
    const val HTTP_PORT = 47989
    const val HTTPS_PORT = 47984
    const val UNIQUE_ID = "0123456789ABCDEF"   // 与 moonlight-android 一致，便于跨客户端退出会话
    private const val TAG = "VRStreamControl"
    private val main = Handler(Looper.getMainLooper())

    /**
     * 探测一台主机是否运行 Sunshine。子线程发起、主线程回调；
     * 超时 / 非 Sunshine / 端口未放行一律返回 null，不把异常抛到 UI。
     */
    fun probe(ip: String, port: Int = HTTP_PORT, onDone: (StreamHost?) -> Unit) {
        Thread({
            val host = try {
                fetch(ip, port)
            } catch (e: Exception) {
                Log.d(TAG, "探测 $ip:$port 失败: ${e.javaClass.simpleName} ${e.message}")
                null
            }
            Log.d(TAG, "探测 $ip:$port -> ${host?.name ?: "无响应"}")
            main.post { onDone(host) }
        }, "vr-stream-probe").apply { isDaemon = true; start() }
    }

    private fun fetch(ip: String, port: Int): StreamHost? {
        val url = URL("http://$ip:$port/serverinfo?uniqueid=$UNIQUE_ID&uuid=${UUID.randomUUID()}")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return null
            val fields = HashMap<String, String>()
            conn.inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(InputStreamReader(stream, Charsets.UTF_8))
                var tag: String? = null
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> tag = parser.name
                        XmlPullParser.TEXT -> if (tag != null && !parser.text.isNullOrBlank()) {
                            fields[tag!!] = fields[tag!!].orEmpty() + parser.text
                        }
                        XmlPullParser.END_TAG -> tag = null
                    }
                    parser.next()
                }
            }
            // 必须看起来像 Sunshine/GFE 的 serverinfo，避免把别的 HTTP 服务误判成串流主机。
            if (fields["hostname"].isNullOrBlank() && fields["GfeVersion"].isNullOrBlank()) return null
            return StreamHost(
                ip = ip,
                httpPort = port,
                name = fields["hostname"].orEmpty().ifBlank { ip },
                uniqueId = fields["uniqueid"].orEmpty(),
                httpsPort = fields["HttpsPort"]?.toIntOrNull() ?: HTTPS_PORT,
                pairStatus = fields["PairStatus"]?.toIntOrNull() ?: 0,
                state = fields["state"].orEmpty(),
                currentGame = fields["currentgame"]?.toIntOrNull() ?: 0,
                codecMask = fields["ServerCodecModeSupport"]?.toIntOrNull() ?: 0,
                maxLumaHevc = fields["MaxLumaPixelsHEVC"]?.toLongOrNull() ?: 0L,
                appVersion = fields["appversion"].orEmpty()
            )
        } finally {
            conn.disconnect()
        }
    }
}
