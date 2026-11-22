package dev.astriavr.player

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * 首页「连接」板块：片源设备 + 串流主机 + 手填兜底（H1）。
 *
 * 本 View 只做**数据采集与展示**：扫描结果统一转成 [HomeItem] 交给
 * [HomeController]，真正启动播放由 HomeActivity 通过 [PlayerRoute] 完成。
 * 于是 VR 化时不需要把 UDP / HTTP 扫描逻辑搬进 GL 层。
 */
class HomeConnectionsPage(
    context: Context,
    private val playlist: PlaylistStore,
    private val onItems: (List<HomeItem>) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLibraryChanged: () -> Unit,
    private val onFocusInput: () -> Unit
) : LinearLayout(context) {
    private val dp = { value: Int -> UiStyle.dp(context, value) }
    private val status = UiStyle.label(context, AppText.SCANNING_LAN.text(), 12f, UiStyle.MUTED)
    private val list = HomeItemListView(context, { item -> onItemSelected(item) }, { item -> onItemLongPressed(item) })
    private val deviceAddress = EditText(context)
    private val hostAddress = EditText(context)
    private var discovery: ServiceDiscovery? = null
    private var active = false
    private val devices = LinkedHashMap<String, LanService>()
    private val hosts = LinkedHashMap<String, StreamHost>()
    private val probedIps = HashSet<String>()
    private var pairingDialog: SettingsDialog? = null
    private var generation = 0

    init {
        orientation = VERTICAL
        addView(UiStyle.label(context, AppText.HOME_CONNECT_TITLE.text(), 15f, bold = true))
        addView(status, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(UiStyle.label(context, AppText.HOME_CONNECT_HINT.text(), 11f, UiStyle.MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        }, LayoutParams(-1, -2))
        addView(list, LayoutParams(-1, -2).apply { topMargin = dp(8) })

        addView(divider(), LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(10) })
        addView(UiStyle.label(context, AppText.MANUAL_ADDRESS.text(), 12f, UiStyle.MUTED))
        addView(addressRow(AppText.HOME_DEVICE_URL_HINT.text(), deviceAddress,
            AppText.HOME_OPEN_URL.text()) { openManualUrl() }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(addressRow(AppText.HOME_HOST_IP_HINT.text(), hostAddress,
            AppText.HOME_PROBE_HOST.text()) { probeManualHost() }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        publish()
    }

    private fun divider() = View(context).apply { setBackgroundColor(UiStyle.LINE) }

    private fun addressRow(hint: String, field: EditText, buttonText: String, action: () -> Unit): LinearLayout {
        field.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(UiStyle.TEXT)
            setHintTextColor(UiStyle.MUTED)
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = UiStyle.glass(context, UiStyle.RAISED, 12)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LayoutParams(0, -2, 1f))
            addView(android.widget.Button(context).apply {
                text = buttonText
                setTextColor(UiStyle.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                background = UiStyle.ripple(context, UiStyle.RAISED, 12)
                setOnClickListener { action() }
            }, LayoutParams(-2, dp(38)).apply { leftMargin = dp(8) })
        }
    }

    /** 构造期回调发生在 HomeActivity 的 View 树完成之前，界面就绪后重发一次。 */
    fun republish() = publish()

    /** 进入板块时开启扫描；离开时停掉 UDP 线程，避免后台占用。 */
    fun startScan() {
        if (active) return
        active = true
        discovery?.stop()
        discovery = ServiceDiscovery { found -> onDevices(found) }.also { it.start() }
        status.text = AppText.SCANNING_LAN.text()
        publish()
    }

    fun stopScan() {
        active = false
        discovery?.stop()
        discovery = null
    }

    fun setFocus(index: Int) = list.setFocus(index)

    /** 语义命令「手动输入地址」落到这里：聚焦输入框并拉起键盘。 */
    fun focusManualInput() {
        deviceAddress.requestFocus()
        deviceAddress.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(deviceAddress, 0)
        }
    }

    fun release() {
        stopScan()
        generation++
        pairingDialog?.dismiss()
        pairingDialog = null
    }

    private fun onDevices(found: List<LanService>) {
        devices.clear()
        for (device in found) {
            devices[device.key] = device
            probe(device.ip)
        }
        status.text = if (found.isEmpty()) AppText.NO_SERVICE_FOUND.text()
        else AppText.SERVICES_FOUND.text(found.size)
        publish()
    }

    private fun probe(ip: String) {
        if (!probedIps.add(ip)) return
        StreamControl.probe(ip) { host ->
            if (!active) return@probe
            if (host != null) hosts[host.ip] = host
            status.text = when {
                hosts.isNotEmpty() -> AppText.STREAM_HOSTS_FOUND.text(hosts.size)
                devices.isNotEmpty() -> AppText.SERVICES_FOUND.text(devices.size)
                else -> AppText.NO_STREAM_HOST.text()
            }
            publish()
        }
    }

    /** 手填主机 IP：UDP 无应答时的兜底，旧版没有这个入口。 */
    private fun probeManualHost() {
        val raw = hostAddress.text.toString().trim()
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(raw)) {
            toast(AppText.INVALID_NETWORK_ADDRESS.text())
            return
        }
        probedIps.remove(raw)
        status.text = AppText.SCANNING_LAN.text()
        probe(raw)
    }

    private fun openManualUrl() {
        val uri = parseMediaUri(deviceAddress.text.toString())
        if (uri == null) {
            toast(AppText.INVALID_NETWORK_ADDRESS.text())
            return
        }
        onStatus(AppText.CONNECTING_DEVICE.text(uri.host.orEmpty()))
        // 手填 URL 也是一个标准片源：进播放页，由 ExoPlayer 走 HTTP Range。
        (context as? Activity)?.let { PlayerLauncher.launch(it, PlayerRoute.Video(uri.toString(), uri.lastPathSegment, false)) }
    }

    /** 合并成一份线性可聚焦列表，2D 与未来 VR 渲染共用。 */
    private fun publish() {
        val items = ArrayList<HomeItem>()
        for (device in devices.values) {
            items.add(HomeItem(
                id = "device|${device.key}",
                title = device.name,
                subtitle = "${device.ip}:${device.port}" +
                    if (device.fileCount >= 0) " · " + AppText.DEVICE_VIDEO_COUNT.text(device.fileCount) else "",
                badge = AppText.HOME_BADGE_DEVICE.text(),
                intent = HomeIntent.Command(HomeCommands.CONNECT_DEVICE)))
        }
        for (host in hosts.values) {
            items.add(HomeItem(
                id = "host|${host.ip}",
                title = host.name,
                subtitle = "${host.ip}:${host.httpPort} · ${host.codecLabel} · " +
                    AppText.STREAM_TAP_TO_CONNECT.text() +
                    if (host.busy) " · " + AppText.STREAM_BUSY.text() else "",
                badge = AppText.HOME_BADGE_HOST.text(),
                intent = HomeIntent.Launch(PlayerRoute.Stream(host.ip, host.name))))
            items.add(HomeItem(
                id = "repair|${host.ip}",
                title = AppText.STREAM_REPAIR_TITLE.text(),
                subtitle = host.ip,
                badge = AppText.HOME_BADGE_HOST.text(),
                intent = HomeIntent.Command(HomeCommands.REPAIR_HOST)))
        }
        items.add(HomeItem("cmd|rescan", AppText.HOME_RESCAN.text(),
            AppText.HOME_RESCAN_HINT.text(), AppText.HOME_BADGE_ACTION.text(),
            HomeIntent.Command(HomeCommands.RESCAN)))
        items.add(HomeItem("cmd|add", AppText.HOME_ADD_LOCAL.text(),
            AppText.HOME_ADD_LOCAL_HINT.text(), AppText.HOME_BADGE_ACTION.text(),
            HomeIntent.Command(HomeCommands.ADD_VIDEOS)))
        items.add(HomeItem("cmd|manual", AppText.MANUAL_ADDRESS.text(),
            AppText.NETWORK_ADDRESS_HELP.text(), AppText.HOME_BADGE_ACTION.text(),
            HomeIntent.Command(HomeCommands.OPEN_MANUAL_ADDRESS)))
        list.setItems(items)
        onItems(items)
    }

    private fun onItemSelected(item: HomeItem) {
        // 触摸点击与语义 CONFIRM 走同一条分发链，避免两套逻辑。
        (context as? HomeActivity)?.dispatchItem(item)
    }

    private fun onItemLongPressed(item: HomeItem) {
        if (item.id.startsWith("host|")) repairHost(item.id.substringAfter('|'))
    }

    /** 平台层解析 CONNECT_DEVICE 后回调这里。 */
    fun connectDevice(itemId: String) {
        val device = devices[itemId.substringAfter('|')] ?: return
        status.text = AppText.CONNECTING_DEVICE.text(device.name)
        discovery?.connect(device) { files ->
            if (!active) return@connect
            if (files.isEmpty()) {
                status.text = AppText.DEVICE_IMPORT_FAILED.text()
                return@connect
            }
            playlist.submit({
                playlist.addAll(files.asReversed().map { device.urlFor(it) to it })
            }) { result ->
                toast(AppText.IMPORTED_VIDEOS.text(device.name, result.getOrDefault(0)))
                onLibraryChanged()
                publish()
            }
        }
    }

    fun repairHost(ip: String) {
        val host = hosts[ip] ?: return
        val activity = context as? Activity ?: return
        val identity = try { StreamIdentity.get(activity) } catch (e: Exception) {
            toast(AppText.STREAM_PIN_FAILED_TITLE.text()); return
        }
        identity.clearServerCert()
        val pin = StreamPairing.generatePin()
        val body = LinearLayout(activity).apply {
            orientation = VERTICAL
            addView(UiStyle.label(activity, AppText.STREAM_PIN_HINT.text(), 12f, UiStyle.MUTED))
            addView(android.widget.TextView(activity).apply {
                text = pin
                setTextColor(UiStyle.ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                letterSpacing = 0.35f
                setPadding(dp(16), dp(18), dp(16), dp(18))
            })
        }
        val generationNow = ++generation
        val dialog = SettingsDialog(activity, AppText.STREAM_PIN_TITLE.text(), body,
            listOf(SettingsDialog.Action(AppText.CANCEL.text(), accepted = { generation++ })),
            cancellable = true)
        pairingDialog?.dismiss()
        pairingDialog = dialog
        dialog.show()
        Thread {
            val result = StreamPairing.pair(identity, ip, host.httpPort, host.httpsPort, pin)
            (context as? Activity)?.runOnUiThread {
                if (generation != generationNow) return@runOnUiThread
                dialog.dismiss(); pairingDialog = null
                toast(if (result.isPaired) AppText.STREAM_PIN_OK.text()
                    else AppText.STREAM_PIN_FAILED_TITLE.text() + ": " + result.detail)
                if (result.isPaired) onStatus(AppText.STREAM_PIN_OK.text())
            }
        }.apply { isDaemon = true; name = "vr-home-pairing" }.start()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
