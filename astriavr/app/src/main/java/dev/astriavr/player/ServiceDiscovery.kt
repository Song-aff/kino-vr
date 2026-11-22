package dev.astriavr.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 一台 PC 片源**设备**（由 UDP 广播/应答发现，名称与 IP 均来自数据包本身）。
 * v0.3：发现列表只列设备，片源文件在「连接」后才拉取并写入播放列表。
 */
data class LanService(val name: String, val ip: String, val port: Int, val fileCount: Int = -1) {
    val key: String get() = "$ip:$port"

    /** 相对路径（可能含子目录）按段编码，保留 '/' 作为路径分隔符。 */
    fun urlFor(relPath: String): String =
        "http://$ip:$port/" + relPath.split('/').joinToString("/") { Uri.encode(it) }
}

/**
 * 局域网设备发现。协议与 PC 端 pc-server/discovery.rs 约定：
 *
 *   APK -> 255.255.255.255:47821   "VRPLAYER_V1|PROBE"
 *   PC  -> 单播/广播               "VRPLAYER_V1|ANNOUNCE|<name>|<ip>|<port>|<ts>|<fileCount>"
 *   APK -> http://<ip>:<port>/api/list   JSON 相对路径数组
 *
 * 只用 UDP broadcast，不用 multicast：后者要求 Android 申请
 * CHANGE_WIFI_MULTICAST_STATE + MulticastLock，会破坏「零新增权限」。
 * 设备 IP 必须取**包内字段**，不能用 UDP 源地址 —— PC 装 VPN/TUN 虚拟网卡时
 * 广播会走虚拟网卡，源地址可能是 198.18.x.x 这类不可达地址。
 *
 * 自管线程，不占用 MainActivity 的单线程 io executor；stop() 后不再回调设备列表。
 */
class ServiceDiscovery(private val onUpdate: (List<LanService>) -> Unit) {
    companion object {
        const val PORT = 47821
        const val MAGIC = "VRPLAYER_V1"
        private const val TAG = "VRLanDiscovery"
        private const val MAX_RUNTIME_MS = 25_000L
        private val PROBE = "$MAGIC|PROBE".toByteArray(Charsets.UTF_8)
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val services = LinkedHashMap<String, LanService>()
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private val stopTask = Runnable { stop() }

    fun start() {
        if (running) return
        running = true
        main.postDelayed(stopTask, MAX_RUNTIME_MS)
        worker = Thread({ loop() }, "vr-lan-discovery").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(stopTask)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker?.interrupt()
        worker = null
    }

    /**
     * 连接一台设备：拉取 /api/list（相对路径数组），主线程回调。
     * 空列表代表网络不通或该设备无片源，由 UI 给出提示并保留手填兜底。
     * 独立于扫描线程，即使扫描超时停止后仍可调用。
     */
    fun connect(service: LanService, onDone: (List<String>) -> Unit) {
        Thread({
            val files: List<String> = try {
                val conn = URL("http://${service.ip}:${service.port}/api/list").openConnection() as HttpURLConnection
                conn.connectTimeout = 3500
                conn.readTimeout = 3500
                conn.requestMethod = "GET"
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
                conn.disconnect()
                val arr = JSONArray(body)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                Log.d(TAG, "连接 ${service.key} 失败: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
            Log.d(TAG, "连接 ${service.key} -> ${files.size} 个片源")
            main.post { onDone(files) }
        }, "vr-lan-connect").apply { isDaemon = true; start() }
    }

    private fun isPlausibleIp(ip: String): Boolean {
        val m = IPV4.matchEntire(ip) ?: return false
        if ((1..4).any { m.groupValues[it].toIntOrNull()?.let { v -> v > 255 } != false }) return false
        return ip != "127.0.0.1" && ip != "0.0.0.0"
    }

    /**
     * 广播目标：全局广播 + 每个活动网卡的定向广播（如 192.168.31.255）。
     * 只发 255.255.255.255 在有 VPN/TUN 虚拟网卡的 PC 上会被抢默认路由，
     * 且部分家用路由器会抑制全局广播；定向广播按目标网段选网卡，可靠得多。
     */
    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return out.toList()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (b is Inet4Address && !b.isLoopbackAddress) out.add(b)
                }
            }
        } catch (_: Exception) {}
        return out.toList()
    }

    /**
     * 兜底：首轮向自身 /24 网段逐台单播 PROBE。单播不依赖任何广播放行，
     * 能穿透 AP 隔离、广播抑制与 PC 虚拟网卡劫持。仅扫描站点本地地址（RFC1918），
     * 且最多 254 个包，一轮约 4 KB，不做后台常驻。
     */
    private fun unicastTargets(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return out
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    if (a.isLoopbackAddress || !a.isSiteLocalAddress) continue
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix !in 1..30) continue
                    // 网段大于 /24 时只扫自身所在的 /24，避免一次发上万个包。
                    val bits = maxOf(prefix, 24)
                    val r = a.address
                    val ip = ((r[0].toInt() and 255) shl 24) or ((r[1].toInt() and 255) shl 16) or
                        ((r[2].toInt() and 255) shl 8) or (r[3].toInt() and 255)
                    val mask = -1 shl (32 - bits)
                    val net = ip and mask
                    val last = net or mask.inv()
                    var v = net + 1
                    while (v < last && out.size < 254) {
                        out.add(InetAddress.getByAddress(byteArrayOf(
                            ((v ushr 24) and 255).toByte(), ((v ushr 16) and 255).toByte(),
                            ((v ushr 8) and 255).toByte(), (v and 255).toByte())))
                        v++
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun loop() {
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 400
                // 端口被占时退到随机端口：仍能收到 PROBE 的单播应答，只是收不到周期广播。
                try { bind(InetSocketAddress(PORT)) }
                catch (_: Exception) { bind(InetSocketAddress(0)) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "socket 初始化失败: ${e.javaClass.simpleName} ${e.message}")
            running = false; return
        }
        socket = sock
        val buf = ByteArray(2048)
        val broadcasts = broadcastTargets()
        val unicasts = unicastTargets()
        Log.d(TAG, "启动扫描: 本地端口=${sock.localPort}, 广播目标=${broadcasts.size}, 单播目标=${unicasts.size}")
        var firstRoundDone = false
        var lastProbe = 0L
        var received = 0
        while (running) {
            val now = System.currentTimeMillis()
            // 首轮：向自身 /24 网段逐台单播探测，绕开一切广播限制。
            if (!firstRoundDone) {
                firstRoundDone = true
                for (t in unicasts) {
                    try { sock.send(DatagramPacket(PROBE, PROBE.size, InetSocketAddress(t, PORT))) }
                    catch (_: Exception) {}
                }
            }
            if (now - lastProbe > 1200) {
                lastProbe = now
                for (t in broadcasts) {
                    try { sock.send(DatagramPacket(PROBE, PROBE.size, InetSocketAddress(t, PORT))) }
                    catch (_: Exception) {}
                }
            }
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (received++ < 8) Log.d(TAG, "收到 ${packet.address}: $text")
                handleAnnounce(text)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (!running) break
            }
        }
        try { sock.close() } catch (_: Exception) {}
        Log.d(TAG, "扫描结束，共收到 $received 个 UDP 包")
    }

    private fun handleAnnounce(text: String) {
        val parts = text.split('|')
        if (parts.size < 6 || parts[0] != MAGIC || parts[1] != "ANNOUNCE") return
        val port = parts[4].toIntOrNull() ?: return
        val ip = parts[3]
        if (port !in 1..65535 || !isPlausibleIp(ip)) return
        // 第 7 段片源数（v0.3 新增，旧 PC 端没有这段时显示 -1）。
        val count = parts.getOrNull(6)?.toIntOrNull() ?: -1
        val service = LanService(parts[2].ifEmpty { ip }, ip, port, count)
        val snapshot: List<LanService>? = synchronized(lock) {
            val changed = services[service.key] != service
            services[service.key] = service
            if (changed) services.values.toList() else null
        }
        if (snapshot != null) {
            Log.d(TAG, "设备 ${service.key} (${service.name}, ${service.fileCount} 个片源)")
            main.post { if (running) onUpdate(snapshot) }
        }
    }

    /** 供 UI 在刚打开对话框时显示初始状态。 */
    fun isRunning() = running
}
