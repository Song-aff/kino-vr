package dev.astriavr.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.media.AudioManager
import android.provider.Settings
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale
import java.util.concurrent.Executors

/** Local video player with a shared Chinese / English UI catalog. */
@SuppressLint("SetTextI18n")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Keep framework dialog/list text at its default size as well. This only
        // overrides font scaling for this screen; Android display density is retained.
        applyOverrideConfiguration(AppLanguage.configuration())
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("astriavr-settings", MODE_PRIVATE) }
    private lateinit var playlist: PlaylistStore
    private lateinit var covers: CoverRepository
    private lateinit var playlistPage: PlaylistPage
    private lateinit var vrPlaylist: VrPlaylistView
    private lateinit var playlistInput: PlaylistInput
    private var playlistOpen = false
    private var resumeAfterPlaylist = false
    private var resumeAfterVrPlaylist = false
    @Volatile private var closing = false
    private val compensationPrefs by lazy { getSharedPreferences("lens-compensation-by-band", MODE_PRIVATE) }
    private lateinit var tracker: HeadTracker
    private lateinit var gamepad: GamepadInput
    private lateinit var vrView: VrView
    private lateinit var flatVideo: FlatVideoView
    private var ordinaryVideo = false
    private var hdrEnabled = true
    private lateinit var hdrSwitch: Switch
    private lateinit var hdrInfo: TextView
    private lateinit var ordinaryPictureCard: View
    private var manualViewingChoice = false
    private var vrResumed = true
    private lateinit var logoView: StopLogoView
    private lateinit var topActions: LinearLayout
    private lateinit var recenterButton: ImageButton
    private lateinit var opticsGroup: View
    private lateinit var visualCard: View
    private val panoramaOptions = mutableListOf<View>()
    private lateinit var stereoHint: StereoHintView
    private lateinit var opticsText: TextView
    private lateinit var speedText: TextView
    private lateinit var settingsPage: LinearLayout
    private var settingsOpen = false
    private var languageChanging = false
    private var resumeAfterLanguageChange = false
    private var languagePositionMs = 0L
    private var restoredLanguagePosition: Long? = null
    private lateinit var screenInfo: TextView
    private lateinit var diameterText: TextView
    private lateinit var diameterSeek: SeekBar
    private lateinit var dragSwitch: Switch
    private lateinit var rotationSwitch: Switch
    private lateinit var ordinaryRotationSwitch: Switch
    private lateinit var pinchSwitch: Switch
    private var syncingRotationSwitches = false
    private var detectedScreenApplied = false
    private var customScreenApplied = false
    private var firstSetupShown = false
    private val seekAcceleration = SeekAcceleration()
    private val boundarySeek = BoundarySeekSequence()
    private var adjacentRequest = 0
    private var adjacentPending = false
    private var navigationBack: BackNavigation? = null
    private lateinit var correctionText: TextView
    private lateinit var correctionSeek: SeekBar
    private lateinit var phoneCorrectionText: TextView
    private lateinit var phoneCorrectionSeek: SeekBar
    private lateinit var root: FrameLayout
    private lateinit var idleBackdrop: View
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private var frostedBars: FrostedPlaybackBars? = null
    private lateinit var tuningRow: LinearLayout
    private lateinit var tuningScroll: android.widget.HorizontalScrollView
    private lateinit var detailText: PlaybackInfoView
    private val bitrateMeter = VideoBitrateMeter()
    private var bitrateSampling = false
    private val bitrateTicker = object : Runnable {
        override fun run() {
            syncBitrateSampling()
            if (!bitrateSampling) return
            val rate = bitrateMeter.takeBitsPerSecond()
            detailText.setBitrate(if (rate.isFinite()) String.format(Locale.ROOT, AppText.BITRATE_F_MBPS.text(), rate / 1_000_000.0) else AppText.BITRATE.text())
            handler.postDelayed(this, 1000)
        }
    }
    private lateinit var videoTitle: TextView
    private lateinit var deviceStatus: DeviceStatusView
    private lateinit var debugButton: Button
    private var debugMode = false
    private var confirmationDialog: android.app.Dialog? = null
    private val playbackButtons = mutableListOf<View>()
    private lateinit var playButton: ImageButton
    private lateinit var layoutButton: Button
    private lateinit var projectionButton: Button
    private lateinit var viewingModeButton: Button
    private lateinit var viewingModeCard: LinearLayout
    private var bigScreenCard: LinearLayout? = null
    private var screenSizeButton: Button? = null
    private var screenFlipButton: Button? = null
    private lateinit var languageSettingsCard: LinearLayout
    private lateinit var settingsBody: LinearLayout
    private lateinit var settingsLeft: LinearLayout
    private lateinit var settingsColumns: LinearLayout
    private lateinit var controlsCard: View
    private lateinit var ordinaryControlsCard: View
    private lateinit var controllerGuideCard: View
    private lateinit var phoneAspectButton: Button
    private lateinit var phoneShapeButton: Button
    private lateinit var phoneCorrectionPanel: LinearLayout
    private lateinit var ipdGroup: View
    private val doubleLensOptions = mutableListOf<View>()
    private val singleLensOptions = mutableListOf<View>()
    private lateinit var phoneEyeButton: Button
    private lateinit var phoneEyeRow: View
    // 4.9：手柄线稿已移除，改用 GamepadMappingActivity 的可编辑键位表。
    private var projectionMode = 0 // VideoProjection source ID, 0 auto
    private var layoutMode = -1 // -1 auto, otherwise the existing SBS/TB/mono indices
    private var videoFormat: Format? = null
    private var metadataProjection = 0
    private var savedCoverProjection = ""
    private val videoPrefs by lazy { getSharedPreferences("video-projections", MODE_PRIVATE) }
    private lateinit var swapButton: Button
    private lateinit var verticalAlignButton: Button
    private lateinit var timeText: TextView
    private lateinit var ipdText: TextView
    private lateinit var gyroButton: ImageButton
    private lateinit var flatResetButton: ImageButton
    private lateinit var seek: SeekBar
    private var player: ExoPlayer? = null
    private var source: Uri? = null
    private var sourceLoading = false
    private var sourceName = AppText.CHOOSE_A_LOCAL_VR_VIDEO.text()
    private var positionMs = 0L
    private var pendingPlay = false
    private var active = false
    // S3b 阶段 3：串流会话与本地片源互斥。source 用 stream:// 哨兵 URI 占位，
    // 让渲染 / 控件逻辑认为「有内容在播」，但播放器交给 StreamSession 而不是 ExoPlayer。
    private var streamSession: StreamSession? = null
    private var streamState = ""
    private var pairingGeneration = 0
    private var pairingDialog: SettingsDialog? = null
    private var startupReady = false
    private var draggingSeek = false
    private var controlsVisible = true
    // H4/S4a：VR 菜单态。此时全部 2D 控件隐藏，菜单作为第二张 GL 纹理贴在
    // world-locked 银幕上；输入统一走 VrMenuSession 的语义层。
    private var vrMenuActive = false
    /** true = 从 2D 首页进的「VR 影院」（大屏 + 退出回首页）；
     *  false = 从普通 VR 播放页呼出的临时参数浮层（保留当前观看模式 + 返回原视频）。 */
    private var vrMenuFromHome = true
    private var vrMenuSession: VrMenuSession? = null
    private var vrMenuRenderer: VrMenuRenderer? = null
    // 4.11：VR 菜单态的轴输入路由。唤起菜单后 GamepadInput 被冻结，
    // 十字键(HAT) / 左摇杆改由这里翻译成菜单方向，不再触发播放页控制。
    private val menuAxis = MenuAxisRouter()
    private var menuAxisRepeatScheduled = false
    private var noticeView: TextView? = null
    private val hideNotice = Runnable { noticeView?.visibility = View.GONE }
    private var speedIndex = PlaybackTuning.DEFAULT_SPEED
    private var brightness = -1f
    private val persistSettings = Runnable { saveSettings() }
    private var settings = RenderSettings()
    private var decoder = ""
    private var failure: String? = null
    private var renderFailure = false
    private var playIcon = ""
    private var projectionWarning = ""
    private var lastSavedMs = 0L
    private var nameRequest = 0
    private var nameQuery: android.os.CancellationSignal? = null
    private val importCancellation = android.os.CancellationSignal()

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            if (!settingsOpen && confirmationDialog?.isShowing != true && !playlistOpen && !playlistInput.open && !settings.phoneMode && !ordinaryVideo && !firstSetupShown && !prefs.getBoolean("screen_setup_done", false) && root.width > 0 && root.height > 0) {
                firstSetupShown = true
                detectScreenParameters(true)
            }
            updateStatus()
            if (player?.isPlaying == true && SystemClock.elapsedRealtime() - lastSavedMs > 5000) savePosition()
            handler.postDelayed(this, 500)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashLog.hasReport(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        try {
            initializePlayerScreen()
            startupReady = true
            // Restore only a recreated Activity, never autoplay on a fresh launch.
            savedInstanceState?.getString("session_uri")?.let {
                speedIndex = savedInstanceState.getInt("session_speed", PlaybackTuning.DEFAULT_SPEED)
                    .coerceIn(0, 6)
                if (savedInstanceState.getBoolean("language_changed") && !savedInstanceState.getBoolean("session_loading"))
                    restoredLanguagePosition = savedInstanceState.getLong("session_position")
                openSource(Uri.parse(it), if (savedInstanceState.getBoolean("session_loading")) null else savedInstanceState.getLong("session_position"),
                    savedInstanceState.getString("session_name"), autoplay = savedInstanceState.getBoolean("language_resume"))
                if (savedInstanceState.getBoolean("language_changed"))
                    manualViewingChoice = savedInstanceState.getBoolean("session_manual_viewing")
            }
            if (savedInstanceState?.getBoolean("playlist_open") == true) openPlaylistPage()
            if (savedInstanceState?.getBoolean("settings_open") == true) openSettingsPage()
            // H0：首页只传「播放什么」，播放页自己决定怎么播。Activity 重建（语言切换、
            // 旋屏）时走 savedInstanceState 恢复，不重复消费同一个 Route。
            if (savedInstanceState == null) {
                PlayerLauncher.from(intent)?.let { route -> handler.post { applyRoute(route) } }
            }
            syncBackCallback()
        } catch (error: Exception) {
            CrashLog.record(this, Thread.currentThread().name, error)
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun initializePlayerScreen() {
        CrashLog.phase = AppText.LOADING_SETTINGS_AND_INITIALIZING_THE_INTERFACE.text()
        debugMode = prefs.getBoolean("debug_mode", false)
        ordinaryVideo = prefs.getBoolean("ordinary_video", false)
        hdrEnabled = Build.VERSION.SDK_INT < 31 || prefs.getBoolean("ordinary_hdr", true)
        if (ordinaryVideo && debugMode) {
            debugMode = false
            prefs.edit().putBoolean("debug_mode", false).apply()
        }
        applyRotationPreference()
        loadProjectionChoice()
        val phoneCorrection = prefs.getInt("phone_rect_compensation_v2", 60)
        settings = RenderSettings(
            Optics.stepIpd(prefs.getFloat("ipd", 6.5f), 0),
            prefs.getInt("layout", 0).coerceIn(0, 2), prefs.getBoolean("swap", false),
            Optics.clampFov(prefs.getInt("fov_8deg", 88)), eyeDiameterCm = Optics.diameter(prefs.getFloat("eye_diameter", 5f)),
            phoneMode = prefs.getBoolean("phone_mode", false),
            phoneFovDegrees = Optics.clampPhoneFov(prefs.getInt("phone_fov", 90)),
            phoneFillScreen = prefs.getBoolean("phone_fill_screen", false),
            phoneElliptical = prefs.getBoolean("phone_elliptical", false),
            phoneWideCorrection = ((phoneCorrection.coerceIn(0, 100) + 2) / 5) * 5,
            screenMode = prefs.getBoolean("screen_mode", false),
            screenFovDegrees = prefs.getInt("screen_fov", 70).coerceIn(40, 110),
            screenFlip = prefs.getInt("screen_flip", 0).coerceIn(0, 2),
            // 4.13：默认吸底 —— 头戴底部支撑会把手机垫高，居中的两圆整体偏上。
            alignBottom = prefs.getBoolean("align_bottom", true))
        detectedScreenApplied = prefs.getBoolean("screen_detected", false)
        customScreenApplied = prefs.getBoolean("screen_custom", false)
        if (detectedScreenApplied || customScreenApplied) {
            val w = prefs.getFloat("screen_width_cm", 0f).toDouble()
            val h = prefs.getFloat("screen_height_cm", 0f).toDouble()
            if (Optics.fits(4f, w, h) && w >= h && w <= 50 && h <= 35) {
                settings = settings.copy(screenWidthCm = w, screenHeightCm = h)
            } else { detectedScreenApplied = false; customScreenApplied = false }
        }
        // Preserve parameters already explicitly chosen in an earlier version.
        if (detectedScreenApplied || customScreenApplied) prefs.edit().putBoolean("screen_setup_done", true).apply()
        settings = settings.fitScreen()
        settings = settings.copy(edgeCorrection = readCompensation(settings.eyeDiameterCm))
        brightness = prefs.getFloat("brightness", -1f).let { if (it.isFinite() && it >= 0f) it.coerceIn(.05f, 1f) else -1f }
        playlist = PlaylistStore(this)
        covers = CoverRepository(this, playlist)
        playlistInput = PlaylistInput(::toggleVrPlaylist) { action ->
            when (action) {
                // 4.14：左右逐条、上下整页翻（一页 = 可视 5 张，move 内部钳位）。
                PlaylistNavigation.LEFT -> vrPlaylist.move(-1)
                PlaylistNavigation.RIGHT -> vrPlaylist.move(1)
                PlaylistNavigation.PREV_PAGE -> vrPlaylist.move(-VrPlaylistView.PAGE)
                PlaylistNavigation.NEXT_PAGE -> vrPlaylist.move(VrPlaylistView.PAGE)
                PlaylistNavigation.CONFIRM -> vrPlaylist.selection()?.let { entry ->
                    closeVrPlaylist(false)
                    beginControllerUse()
                    openSource(Uri.parse(entry.uri), entry.position, entry.name, revealControls = false)
                }
            }
        }

        tracker = HeadTracker(this) { windowManager.defaultDisplay.rotation }
        // 4.12：戴头显看 VR 的默认体验就是陀螺仪跟头；旧版默认 false 让用户
        // 一戴上发现画面不跟头转，还得摘下来找开关。改为默认开，用户可随时关。
        tracker.setGyroEnabled(prefs.getBoolean("gyro_enabled", true))
        gamepad = GamepadInput(this, ::onControllerAction, { yaw, pitch ->
            if (!ordinaryVideo) { beginControllerUse(); tracker.moveView(yaw, pitch) }
        }, ::adjustFromController, { speedIndex }, { playlistInput.clear(); syncBackCallback() })
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vrView = VrView(this, tracker).apply {
            vrRenderer.settings = settings
            dragEnabled = prefs.getBoolean("drag_enabled", true)
            onDrag = { yaw, pitch -> tracker.moveView(yaw, pitch) }
            onTap = { resetSeekSequence(); showControls(!controlsVisible) }
            onDoubleTap = {
                if (source != null) { togglePlayback() }
            }
            onSeekTap = { direction, startedAt ->
                if (source != null && !debugMode) {
                    val elapsedStart = if (startedAt < 0) -1L else
                        SystemClock.elapsedRealtime() - (SystemClock.uptimeMillis() - startedAt)
                    seekStep(direction, screenDoubleStartedMs = elapsedStart)
                }
            }
            onOutput = { output ->
                if (active && !ordinaryVideo && vrRenderer.output === output) {
                    val session = streamSession
                    if (session != null) session.attachSurface(output.surface)
                    else player?.setVideoSurface(output.surface) ?: ensurePlayer()
                }
            }
            onOutputRetired = { retired ->
                // 解码器必须先停止，Surface 才能被释放；顺序反了会让 MediaCodec 报非法状态。
                streamSession?.stop()
                player?.clearVideoSurface(retired.surface)
                retired.release()
            }
            onError = error@ { message ->
                if (ordinaryVideo && !debugMode) return@error
                if (active) {
                    renderFailure = true
                    failure = message
                    releasePlayer()
                    showControls(true)
                    updateStatus()
                }
            }
        }
        root.addView(vrView, FrameLayout.LayoutParams(-1, -1))
        flatVideo = FlatVideoView(this).apply {
            visibility = View.GONE
            zoomEnabled = prefs.getBoolean("flat_pinch_enabled", true)
            onVideoTouch = { event -> vrView.handleVideoTouch(event, width) }
            onTap = { vrView.onTap() }
            onMultiTouchStart = { vrView.cancelPendingGestures() }
            onMultiTouchActive = { vrView.onTouchActive(it) }
        }
        root.addView(flatVideo, FrameLayout.LayoutParams(-1, -1))
        // The theme fills the idle page; opening a video removes this overlay completely.
        idleBackdrop = View(this).apply {
            background = UiStyle.stars(this@MainActivity)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(idleBackdrop, FrameLayout.LayoutParams(-1, -1))
        stereoHint = StereoHintView(this)
        root.addView(stereoHint, FrameLayout.LayoutParams(-1, -1))
        vrPlaylist = VrPlaylistView(this, playlist, covers) { closeVrPlaylist(true) }
        root.addView(vrPlaylist, FrameLayout.LayoutParams(-1, -1))
        buildControls()
        buildSettingsPage()
        applyDebugAppearance()
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyPageInsets(insets)
            insets
        }
        CrashLog.phase = AppText.SHOWING_THE_PLAYER.text()
        setContentView(root)
        // PhoneWindow on the target device dereferences a null DecorView if its
        // InsetsController is requested before setContentView installs the decor.
        configureWindow()
        updateSettings()
        updateStatus()
    }

    private fun applyPageInsets(insets: WindowInsets?) {
        val safe = android.graphics.Rect()
        if (Build.VERSION.SDK_INT >= 28) insets?.displayCutout?.let {
            safe.set(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom)
        }
        // Apply cached window insets before showing a lazy page, not one frame after its entrance.
        if (::settingsPage.isInitialized) settingsPage.setPadding(dp(22) + safe.left, dp(6) + safe.top, dp(22) + safe.right, safe.bottom)
        if (::playlistPage.isInitialized) playlistPage.setContentPadding(dp(18) + safe.left, dp(8) + safe.top, dp(18) + safe.right, dp(8) + safe.bottom)
    }

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        val decor = window.decorView
        window.attributes = window.attributes.apply {
            screenBrightness = brightness
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            // View's controller can be null before attachment; focus/resume applies it again.
            // Do not use Window.getInsetsController(): its implementation can throw before
            // returning, so a Kotlin ?. on that getter cannot protect against this crash.
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

    private fun buildControls() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = UiStyle.stars(this@MainActivity, StarfieldDrawable.Kind.BAR)
        }
        val headings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        logoView = StopLogoView(this).apply { onStop = ::stopCurrentVideo }
        videoTitle = label("", 16f).apply {
            setTypeface(typeface, Typeface.NORMAL)
            setSingleLine(); gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(16))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        detailText = PlaybackInfoView(this)
        headings.addView(videoTitle, LinearLayout.LayoutParams(-1, -2))
        headings.addView(detailText, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(5) })
        val actions = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        topActions = actions
        fun topAction(icon: String, description: String, action: () -> Unit): ImageButton {
            val control = iconButton(icon, description, action).apply { tooltipText = description; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            actions.addView(control, LinearLayout.LayoutParams(dp(48), dp(48)).apply { if (actions.childCount > 0) marginStart = dp(6) })
            return control
        }
        gyroButton = topAction("gyro", AppText.TOGGLE_GYROSCOPE_B.text(), ::toggleGyro)
        recenterButton = topAction("center", AppText.RECENTER_A.text(), ::recenterNow)
        flatResetButton = topAction("restart", AppText.RESET_THE_STANDARD_VIDEO_S_SIZE.text()) {
            flatVideo.resetTransform()
            stereoHint.showValue(AppText.VIEW_RESET.text())
        }
        topAction("settings", AppText.SETTINGS.text(), ::openSettingsPage)
        topAction("playlist", AppText.PLAYLIST.text(), ::openPlaylistPage)
        // H0.3：顶栏最右侧的显式「返回首页」入口。与系统返回键不同，它不受
        // 手柄保护逻辑（连着手柄时返回不退出）限制，任何时候点都能回首页。
        topAction("return", AppText.BACK_TO_HOME.text()) { PlayerLauncher.goHome(this) }
        topBar.addView(logoView, LinearLayout.LayoutParams(dp(156), dp(48)))
        topBar.addView(headings, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
        deviceStatus = DeviceStatusView(this)
        topBar.addView(deviceStatus, LinearLayout.LayoutParams(dp(54), dp(40)).apply { marginEnd = dp(8) })
        // 宽度由 syncControls() 按可见按钮数重算；这里用 wrap_content 兜住首帧。
        topBar.addView(actions, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = true
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = UiStyle.stars(this@MainActivity, StarfieldDrawable.Kind.BAR)
        }
        val progressRow = row()
        playButton = iconButton("play", AppText.PLAY.text()) { togglePlayback() }
        playbackButtons.add(playButton)
        progressRow.addView(playButton, LinearLayout.LayoutParams(dp(46), dp(44)))
        progressRow.addView(iconButton("back", AppText.REWIND.text()) { seekStep(-1) }.also { playbackButtons.add(it) }, LinearLayout.LayoutParams(dp(42), dp(44)))
        seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(UiStyle.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(UiStyle.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(UiStyle.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { resetSeekSequence(); draggingSeek = true }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = player?.duration ?: C.TIME_UNSET
                        if (duration > 0) {
                            val message = "${time(duration * progress / 1000)} / ${time(duration)}"
                            timeText.text = message
                            stereoHint.showValue(message)
                        }
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val current = player
                    val duration = current?.duration ?: C.TIME_UNSET
                    if (current != null && duration > 0 && current.isCurrentMediaItemSeekable) {
                        val target = duration * bar.progress / 1000
                        boundarySeek.reset()
                        if (target == 0L) {
                            pendingPlay = false
                            current.pause()
                            boundarySeek.reached(-1, SystemClock.elapsedRealtime())
                        }
                        else if (target >= duration) boundarySeek.reached(1, SystemClock.elapsedRealtime())
                        current.seekTo(target)
                    }
                    draggingSeek = false
                }
            })
        }
        val timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeline.addView(seek, LinearLayout.LayoutParams(-1, dp(28)))
        timeText = label("00:00 / --:--", 9f).apply { gravity = Gravity.CENTER; setTextColor(UiStyle.MUTED) }
        timeline.addView(timeText, LinearLayout.LayoutParams(-1, dp(16)))
        progressRow.addView(timeline, LinearLayout.LayoutParams(0, dp(44), 1f))
        progressRow.addView(iconButton("forward", AppText.FORWARD.text()) { seekStep(1) }.also { playbackButtons.add(it) }, LinearLayout.LayoutParams(dp(42), dp(44)))
        tuningRow = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fun tuningGroup(title: String, decrease: () -> Unit, increase: () -> Unit, onGroup: (View) -> Unit = {}): TextView {
            val group = row().apply { background = UiStyle.shape(this@MainActivity, 0x58234574, 12) }
            group.addView(button("−", decrease).apply { contentDescription = AppText.DECREASE.text(title); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f); background = UiStyle.ripple(this@MainActivity, Color.TRANSPARENT, 10) }, LinearLayout.LayoutParams(dp(24), dp(44)))
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            words.addView(label(title, 8f).apply { gravity = Gravity.CENTER; setTextColor(UiStyle.MUTED) })
            val value = label("", 12f).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
            words.addView(value)
            group.addView(words, LinearLayout.LayoutParams(0, dp(44), 1f))
            group.addView(button("+", increase).apply { contentDescription = AppText.INCREASE.text(title); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f); background = UiStyle.ripple(this@MainActivity, Color.TRANSPARENT, 10) }, LinearLayout.LayoutParams(dp(24), dp(44)))
            tuningRow.addView(group, LinearLayout.LayoutParams(dp(84), dp(44)).apply { if (tuningRow.childCount > 0) marginStart = dp(6) })
            onGroup(group)
            return value
        }
        ipdText = tuningGroup(AppText.IPD_CM.text(), { changeIpd(-1) }, { changeIpd(1) }, { ipdGroup = it })
        speedText = tuningGroup(AppText.PLAYBACK_SPEED.text(), { changeSpeed(-1) }, { changeSpeed(1) })
        opticsText = tuningGroup(AppText.FIELD_OF_VIEW.text(), { changeFov(-1) }, { changeFov(1) }, { opticsGroup = it })
        tuningScroll = android.widget.HorizontalScrollView(this).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 0)
            addView(tuningRow, FrameLayout.LayoutParams(-2, dp(44), Gravity.CENTER_VERTICAL))
        }
        val playbackRow = row()
        playbackRow.addView(progressRow, LinearLayout.LayoutParams(0, dp(44), 1f))
        playbackRow.addView(tuningScroll, LinearLayout.LayoutParams(dp(264), dp(44)).apply { marginStart = dp(8) })
        bottomBar.addView(playbackRow, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        fun glassButtons(view: View) {
            if (view is ImageButton) view.background = UiStyle.ripple(this, 0x48234574, 14)
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) glassButtons(view.getChildAt(i))
        }
        glassButtons(topBar); glassButtons(bottomBar)
        frostedBars = FrostedPlaybackBars(root, listOf(topBar, bottomBar),
            eligible = { active && hasWindowFocus() && controlsVisible && !settingsOpen && !playlistOpen &&
                !playlistInput.open && topBar.isShown && bottomBar.isShown },
            source = { if (source == null && !debugMode) null
                else if (ordinaryVideo && !debugMode) flatVideo.surfaceView else vrView },
            contentId = { if (debugMode) "debug" else source },
            position = { player?.currentPosition ?: positionMs },
            moving = { debugMode || !ordinaryVideo || player?.isPlaying == true })
    }

    private fun updateTuningLayout() {
        ipdGroup.visibility = if (settings.phoneMode || ordinaryVideo) View.GONE else View.VISIBLE
        opticsGroup.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        val width = dp(if (settings.phoneMode || ordinaryVideo) 104 else 84)
        val buttonWidth = dp(if (settings.phoneMode || ordinaryVideo) 28 else 24)
        var visibleCount = 0
        for (i in 0 until tuningRow.childCount) {
            val group = tuningRow.getChildAt(i) as LinearLayout
            val margin = if (group.visibility == View.VISIBLE && visibleCount++ > 0) dp(6) else 0
            val params = group.layoutParams as LinearLayout.LayoutParams
            if (params.width != width || params.marginStart != margin) {
                params.width = width; params.marginStart = margin; group.layoutParams = params
            }
            for (index in intArrayOf(0, 2)) group.getChildAt(index).let { button ->
                if (button.layoutParams.width != buttonWidth) button.layoutParams = button.layoutParams.apply { this.width = buttonWidth }
            }
        }
        val targetWidth = visibleCount * width + (visibleCount - 1).coerceAtLeast(0) * dp(6)
        if (tuningScroll.layoutParams.width != targetWidth) {
            tuningScroll.layoutParams = tuningScroll.layoutParams.apply { this.width = targetWidth }
            tuningScroll.scrollTo(0, 0)
        }
    }

    private fun syncVideoMode() {
        // VR 菜单必须走 GL 渲染器；即使上一次停留在普通 2D 视频模式也强制切回。
        val useVr = vrMenuActive || !ordinaryVideo || debugMode
        vrView.visibility = if (useVr) View.VISIBLE else View.GONE
        flatVideo.visibility = if (!useVr && source != null) View.VISIBLE else View.GONE
        vrView.tapTarget = if (useVr) vrView else flatVideo
        vrView.dragEnabled = !ordinaryVideo && prefs.getBoolean("drag_enabled", true)
        val runVr = active && useVr
        if (runVr != vrResumed) {
            if (runVr) vrView.onResume() else {
                vrView.queueEvent { vrView.vrRenderer.releaseOnGlThread() }
                vrView.onPause()
            }
            vrResumed = runVr
        }
        // VR 菜单态虽然没有片源，但 world-locked 银幕需要持续的头部姿态才能固定在世界里。
        if (runVr && !playlistOpen && (vrMenuActive || source != null || debugMode)) tracker.start() else tracker.stop()
    }

    private fun screenFlipLabel() = when (settings.screenFlip) {
        1 -> AppText.SCREEN_FLIP_VERTICAL.text()
        2 -> AppText.SCREEN_FLIP_ROTATE.text()
        else -> AppText.SCREEN_FLIP_NORMAL.text()
    }

    private fun setViewingMode(mode: Int) {
        val screen = mode == 3
        settings = settings.copy(screenMode = screen)
        // Big screen mode is a fourth viewing mode. It always uses the VR renderer
        // (dual-lens) so the flat-screen shader branch actually runs; automatic
        // detection would otherwise route 2D sources to FlatVideoView.
        changeViewingMode(if (screen) 0 else mode, automatic = false)
        vrView.cancelPendingGestures()
        updateSettings()
        updateStatus()
    }

    private fun shouldResumePlayback() = PlaybackPosition.shouldResume(pendingPlay,
        player?.playWhenReady == true, player?.playbackState == Player.STATE_ENDED)

    private fun setHdrEnabled(enabled: Boolean) {
        if (hdrEnabled == enabled) return
        hdrEnabled = enabled
        prefs.edit().putBoolean("ordinary_hdr", enabled).apply()
        hdrSwitch.isChecked = enabled
        if (ordinaryVideo) {
            val resume = shouldResumePlayback()
            // Android 15+ can change HDR headroom without rebuilding or interrupting playback.
            if (Build.VERSION.SDK_INT >= 35) {
                flatVideo.surfaceView.setDesiredHdrHeadroom(if (enabled) 0f else 1f)
            } else {
                releasePlayer()
                pendingPlay = resume
                if (active) ensurePlayer()
            }
        }
        updateHdrInfo()
    }

    private fun hdrUnavailable() {
        if (!ordinaryVideo || hdrEnabled) return
        setHdrEnabled(true)
        notifyUser(AppText.THIS_DECODER_CANNOT_CONVERT_HDR_TO.text())
    }

    @Suppress("DEPRECATION")
    private fun updateHdrInfo() {
        if (!::hdrInfo.isInitialized) return
        val types = windowManager.defaultDisplay.hdrCapabilities.supportedHdrTypes
        val displayNames = types.asSequence().mapNotNull { when (it) {
            android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
            android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
            android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> AppText.DOLBY_VISION.text()
            else -> null
        } }.distinct().joinToString(" / ")
        val format = videoFormat
        val content = when {
            source == null || format == null -> ""
            format.sampleMimeType == "video/dolby-vision" -> AppText.CURRENT_VIDEO_DOLBY_VISION_N.text()
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> AppText.CURRENT_VIDEO_HDR_PQ_N.text()
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> AppText.CURRENT_VIDEO_HDR_HLG_N.text()
            else -> AppText.CURRENT_VIDEO_SDR_OR_NO_HDR.text()
        }
        hdrInfo.text = content + (if (displayNames.isEmpty()) AppText.THE_DISPLAY_REPORTS_NO_HDR_SUPPORT.text() else AppText.DISPLAY_SUPPORTS.text(displayNames)) +
            (if (Build.VERSION.SDK_INT < 31) AppText.NTHIS_ANDROID_VERSION_HANDLES_HDR_AUTOMATICALLY.text() else "")
    }

    private fun changeViewingMode(mode: Int, automatic: Boolean) {
        val nextOrdinary = mode == 2
        if (!automatic) {
            manualViewingChoice = true
            source?.let { videoPrefs.edit().putBoolean("${projectionKey(it)}_ordinary_override",nextOrdinary).apply() }
        }
        if (ordinaryVideo == nextOrdinary && (nextOrdinary || settings.phoneMode == (mode == 1))) return
        val resume = shouldResumePlayback()
        if (nextOrdinary && debugMode && !automatic) setDebugMode(false)
        releasePlayer()
        ordinaryVideo = nextOrdinary
        flatVideo.resetTransform()
        // Ordinary playback does not overwrite the last single-/double-lens VR preference.
        if (!nextOrdinary) settings = settings.copy(phoneMode = mode == 1)
        pendingPlay = resume
        renderFailure = false
        vrView.cancelPendingGestures(); logoView.cancelPending()
        if (active) { if (ordinaryVideo) gamepad.stop() else gamepad.start() }
        gamepad.clearInput(); resetSeekSequence(); stereoHint.clear()
        vrView.vrRenderer.resetFrame()
        updateSettings(); updateStatus()
        syncBackCallback()
        if (active) ensurePlayer()
    }

    private fun stopCurrentVideo() {
        stopStream()
        if (debugMode) setDebugMode(false)
        if (source == null) { showControls(true); return }
        pendingPlay = false; resumeAfterPlaylist = false; resumeAfterVrPlaylist = false
        closeVrPlaylist(false)
        vrView.cancelPendingGestures(); logoView.cancelPending()
        gamepad.clearInput(); resetSeekSequence(); stereoHint.clear()
        releasePlayer() // Save the resume position before clearing the active source.
        nameRequest++; nameQuery?.cancel(); nameQuery = null
        source = null; sourceLoading = false; sourceName = ""; positionMs = 0; videoFormat = null
        metadataProjection = 0; projectionWarning = ""; savedCoverProjection = ""
        failure = null; renderFailure = false; decoder = ""
        vrView.vrRenderer.resetFrame(); flatVideo.resetTransform(); flatVideo.setVideoSize(0, 0, 1f)
        updateSettings(false); updateStatus(); showControls(true)
    }

    private fun showConfirmation(title: String, message: String, actionLabel: String, action: () -> Unit) {
        showSettingsDialog(ConfirmationDialog(this, title, message, actionLabel, action))
    }

    private fun showSettingsDialog(dialog: SettingsDialog) {
        if (confirmationDialog?.isShowing == true || isFinishing || isDestroyed) return
        confirmationDialog = dialog
        dialog.setOnDismissListener {
            if (confirmationDialog === dialog) confirmationDialog = null
            if (!closing && startupReady) configureWindow()
        }
        dialog.show()
    }

    private fun chooseSetting(title: String, choices: List<String>, selected: Int, chosen: (Int) -> Unit) {
        if (choices.size == 2) {
            chosen(1 - selected.coerceIn(0, 1))
            return
        }
        // Compact one-column choices; row heights fit the actual window with no scrolling.
        val group = object : LinearLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val available = if (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED) dp(40) * childCount
                    else View.MeasureSpec.getSize(h)
                val rowHeight = minOf(dp(40), available / childCount.coerceAtLeast(1)).coerceAtLeast(1)
                for (i in 0 until childCount) getChildAt(i).layoutParams.height = rowHeight
                super.onMeasure(w, h)
            }
        }.apply { orientation = LinearLayout.VERTICAL }
        val dialog = SettingsDialog(this, title, group, listOf(SettingsDialog.Action(AppText.CANCEL.text())), scrollContent = false)
        choices.forEachIndexed { index, text ->
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                val display = text.removeSuffix(AppText.DEFAULT.text())
                this.text = if (title == AppText.VIEWING_MODE.text() && display.endsWith(" · VR")) {
                    android.text.SpannableString(display).apply {
                        val start = display.length - 2
                        setSpan(android.text.style.ForegroundColorSpan(UiStyle.ACCENT), start, display.length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, display.length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else display
                contentDescription = text
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f); setTextColor(UiStyle.TEXT)
                buttonTintList = android.content.res.ColorStateList.valueOf(UiStyle.ACCENT)
                setPadding(dp(4), 0, dp(4), 0); minHeight = 0; minimumHeight = 0
                includeFontPadding = false; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                isChecked = index == selected
                setOnClickListener { dialog.dismiss(); chosen(index) }
            }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        showSettingsDialog(dialog)
    }

    private fun setDebugMode(enabled: Boolean) {
        if (enabled && ordinaryVideo) return
        debugMode = enabled
        prefs.edit().putBoolean("debug_mode", enabled).apply()
        if (enabled) {
            pendingPlay = false
            resumeAfterPlaylist = false; resumeAfterVrPlaylist = false
            player?.pause(); savePosition()
            if (ordinaryVideo) releasePlayer()
        }
        gamepad.clearInput(); resetSeekSequence(); stereoHint.clear()
        applyDebugAppearance()
        syncVideoMode()
        updateStatus()
    }

    private fun applyDebugAppearance() {
        vrView.vrRenderer.debugMode = debugMode
        stereoHint.settings = if (ordinaryVideo && !debugMode) settings.copy(phoneMode = true, phoneFillScreen = true) else settings
        stereoHint.debugMode = debugMode
        idleBackdrop.visibility = if (!vrMenuActive && source == null && !debugMode) View.VISIBLE else View.GONE
        frostedBars?.refresh()
        // Debug only changes the viewing area; both playback bars retain their starfield backgrounds.
        debugButton.text = if (debugMode) AppText.EXIT_DEBUG.text() else AppText.DEBUG_MODE.text()
        debugButton.setTextColor(if (debugMode) UiStyle.ACCENT else UiStyle.TEXT)
        debugButton.isSelected = debugMode
        for (button in playbackButtons) {
            val enabled = !debugMode || button === playButton
            button.isEnabled = enabled; button.alpha = if (enabled) 1f else .35f
        }
    }

    private fun buildSettingsPage() {
        // Settings typography is independent of the compact playback controls.
        fun label(value: String, size: Float) = this@MainActivity.label(value, size + 2f).apply {
            includeFontPadding = false
            setLineSpacing(0f, 1f)
        }
        fun button(value: String, action: () -> Unit) = this@MainActivity.button(value, action).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            maxLines = 2
            setAutoSizeTextTypeUniformWithConfiguration(12, 14, 1, TypedValue.COMPLEX_UNIT_DIP)
        }
        settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiStyle.stars(this@MainActivity)
            setPadding(dp(22), dp(6), dp(22), 0)
            isClickable = true
            visibility = View.GONE
        }
        val header = row()
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(AppText.SETTINGS.text(), 23f).apply { setTypeface(typeface, Typeface.BOLD) })
        header.addView(heading, LinearLayout.LayoutParams(0, dp(46), 1f))
        debugButton = button(AppText.DEBUG_MODE.text()) {
            if (debugMode) {
                setDebugMode(false)
                closeSettingsPage()
            } else showConfirmation(AppText.ENTER_DEBUG_MODE.text(),
                AppText.PAUSE_THE_VIDEO_AND_SHOW_A.text(), AppText.ENTER_DEBUG.text()) {
                setDebugMode(true)
                closeSettingsPage()
            }
        }
        header.addView(debugButton, LinearLayout.LayoutParams(dp(128), dp(40)).apply { marginEnd = dp(12) })
        header.addView(button(AppText.RESET_DEFAULTS.text()) {
            showConfirmation(AppText.RESET_ALL_SETTINGS.text(),
                AppText.RESET_VIEWING_BRIGHTNESS_SPEED_LENS_CORRECTION.text(), AppText.RESET_DEFAULTS.text(), ::restoreDefaults)
        }, LinearLayout.LayoutParams(dp(128), dp(40)).apply { rightMargin = dp(12) })
        header.addView(iconButton("return", AppText.BACK_TO_PLAYER.text()) { closeSettingsPage() }, LinearLayout.LayoutParams(dp(48), dp(40)))
        settingsPage.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(16)) }
        settingsBody = body
        val columns = row().apply { gravity = Gravity.TOP }
        settingsColumns = columns
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsLeft = left
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
        columns.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        // All viewing modes share two columns; ordinary controls occupy the right column alone.
        fun card(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(18))
                background = UiStyle.shape(this@MainActivity, UiStyle.SURFACE, 18)
            }
            card.addView(label(title, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
            if (subtitle != null) card.addView(label(subtitle, 11f).apply { setTextColor(UiStyle.MUTED); setPadding(0, dp(3), 0, dp(8)) })
            parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            return card
        }
        fun actionRow(parent: LinearLayout, title: String, control: View): LinearLayout {
            val line = row()
            line.addView(label(title, 13f).apply { minimumHeight = dp(44) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            line.addView(control, LinearLayout.LayoutParams(dp(156), dp(40)))
            parent.addView(line)
            return line
        }
        fun toggle(parent: LinearLayout, title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit): Switch {
            val line = row()
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), dp(8), dp(4)) }
            words.addView(label(title, 13f))
            words.addView(label(subtitle, 10f).apply { setTextColor(UiStyle.MUTED); if (!AppText.isEnglish()) maxLines = 2 })
            line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            val control = Switch(this).apply {
                contentDescription = title
                isChecked = checked
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(UiStyle.ACCENT, UiStyle.MUTED))
                setOnCheckedChangeListener { _, enabled -> change(enabled) }
            }
            line.addView(control, LinearLayout.LayoutParams(dp(52), dp(48)))
            parent.addView(line)
            return control
        }
        viewingModeCard = row().apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = UiStyle.shape(this@MainActivity, UiStyle.SURFACE, 18)
        }
        viewingModeCard.addView(label(AppText.VIEWING_MODE.text(), 14f).apply {
            setTypeface(typeface, Typeface.BOLD)
            minimumHeight = dp(44)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        viewingModeButton = button("") {
            chooseSetting(AppText.VIEWING_MODE.text(), listOf(AppText.D_DUAL_LENS_VR.text(), AppText.PHONE_SINGLE_LENS_VR.text(), AppText.SETTINGS_STANDARD_VIDEO.text(), AppText.BIG_SCREEN_MODE.text()),
                if (settings.screenMode) 3 else if (ordinaryVideo) 2 else if (settings.phoneMode) 1 else 0, ::setViewingMode)
        }
        viewingModeCard.addView(viewingModeButton, LinearLayout.LayoutParams(dp(156), dp(40)))
        left.addView(viewingModeCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        body.addView(columns)
        val bigScreen = card(left, AppText.BIG_SCREEN_MODE.text(), AppText.BIG_SCREEN_MODE_HINT.text())
        bigScreenCard = bigScreen
        val sizeButton = button("") {
            chooseSetting(AppText.BIG_SCREEN_SIZE.text(), listOf("55°", "70°", "85°", "100°"),
                listOf(55, 70, 85, 100).indexOf(settings.screenFovDegrees).coerceAtLeast(0)) { choice ->
                settings = settings.copy(screenFovDegrees = listOf(55, 70, 85, 100)[choice])
                vrView.cancelPendingGestures(); updateSettings(); updateStatus()
            }
        }
        screenSizeButton = sizeButton
        sizeButton.text = "${settings.screenFovDegrees}°"
        actionRow(bigScreen, AppText.BIG_SCREEN_SIZE.text(), sizeButton)
        // 三态方向修正：覆盖「手机在盒内水平反放 180°」与「大屏分支 Y 符号相反」两种根因。
        // 默认保持既有行为，用户可以即时切换并持久化，不影响 VR 球面路径。
        val flipButton = button("") {
            chooseSetting(AppText.BIG_SCREEN_ORIENTATION.text(),
                listOf(AppText.SCREEN_FLIP_NORMAL.text(), AppText.SCREEN_FLIP_VERTICAL.text(),
                    AppText.SCREEN_FLIP_ROTATE.text()),
                settings.screenFlip.coerceIn(0, 2)) { choice ->
                settings = settings.copy(screenFlip = choice)
                vrView.cancelPendingGestures(); updateSettings(); updateStatus()
            }
        }
        screenFlipButton = flipButton
        flipButton.text = screenFlipLabel()
        actionRow(bigScreen, AppText.BIG_SCREEN_ORIENTATION.text(), flipButton)
        val visual = card(left, AppText.PICTURE.text())
        visualCard = visual
        phoneAspectButton = button("") {
            chooseSetting(AppText.PHONE_ASPECT_RATIO.text(), listOf(AppText.LETTERBOX.text(), AppText.FULL_SCREEN.text()), if (settings.phoneFillScreen) 1 else 0) {
                settings = settings.copy(phoneFillScreen = it == 1)
                vrView.cancelPendingGestures(); updateSettings(); updateStatus()
            }
        }
        singleLensOptions.add(actionRow(visual, AppText.PHONE_ASPECT_RATIO.text(), phoneAspectButton))
        phoneShapeButton = button("") {
            chooseSetting(AppText.PHONE_PROJECTION.text(), listOf(AppText.DYNAMIC_RECTANGLE.text(), AppText.ELLIPSE.text()), if (settings.phoneElliptical) 1 else 0) {
                settings = settings.copy(phoneElliptical = it == 1)
                vrView.cancelPendingGestures(); stereoHint.clear(); updateSettings(); updateStatus()
            }
        }
        singleLensOptions.add(actionRow(visual, AppText.PHONE_PROJECTION.text(), phoneShapeButton))
        val phoneCorrection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        phoneCorrectionPanel = phoneCorrection
        phoneCorrectionText = label("", 12f).apply { setPadding(0, dp(8), 0, dp(3)) }
        phoneCorrection.addView(phoneCorrectionText)
        phoneCorrectionSeek = SeekBar(this).apply {
            max = 20; keyProgressIncrement = 1
            contentDescription = AppText.ULTRAWIDE_CORRECTION_IN_STEPS_EQUALS_THE.text()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        settings = settings.copy(phoneWideCorrection = progress * 5)
                        updateSettings()
                    }
                }
            })
        }
        phoneCorrection.addView(phoneCorrectionSeek, LinearLayout.LayoutParams(-1, dp(38)))
        visual.addView(phoneCorrection)
        singleLensOptions.add(phoneCorrection)
        val sizes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diameterText = label("", 12f).apply { setPadding(0, dp(6), 0, 0) }
        sizes.addView(diameterText)
        diameterSeek = SeekBar(this).apply {
            max = 15
            keyProgressIncrement = 1
            contentDescription = AppText.CIRCULAR_FIELD_DIAMETER_PER_EYE_TO.text()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) selectDiameter((20 + progress) / 5f)
                }
            })
        }
        sizes.addView(diameterSeek, LinearLayout.LayoutParams(-1, dp(48)))
        val limits = row()
        limits.addView(label("4 cm", 10f), LinearLayout.LayoutParams(0, -2, 1f))
        limits.addView(label("7 cm", 10f))
        sizes.addView(limits)
        visual.addView(sizes)
        doubleLensOptions.add(sizes)
        projectionButton = button("") {
            val projectionChoices = VideoProjection.choices(settings.phoneMode)
            val selected = VideoProjection.forViewingMode(projectionMode, settings.phoneMode)
            chooseSetting(AppText.SOURCE_PROJECTION.text(), projectionChoices.map { settingsProjectionLabel(it) },
                projectionChoices.indexOf(selected).coerceAtLeast(0)) { choice ->
                projectionMode = projectionChoices[choice]
                if (projectionMode == VideoProjection.STEREO_FISHEYE_180 && layoutMode == 2) layoutMode = -1
                saveProjectionChoice(); updateSettings(); updateStatus()
            }
        }
        panoramaOptions.add(actionRow(visual, AppText.SOURCE_PROJECTION.text(), projectionButton))
        layoutButton = button("") {
            val stereoFisheye = settings.sourceProjection == VideoProjection.STEREO_FISHEYE_180
            val choices = if (stereoFisheye) listOf(AppText.AUTO_DETECT_DEFAULT.text(), AppText.SIDE_BY_SIDE_SBS.text(), AppText.TOP_BOTTOM_TB.text())
                else listOf(AppText.AUTO_DETECT_DEFAULT.text(), AppText.SIDE_BY_SIDE_SBS.text(), AppText.TOP_BOTTOM_TB.text(), AppText.MONO.text())
            val selected = if (stereoFisheye && layoutMode == 2) 0 else layoutMode + 1
            chooseSetting(AppText.VIDEO_LAYOUT.text(), choices, selected) { choice ->
                layoutMode = choice - 1
                saveProjectionChoice(); updateSettings(); updateStatus()
            }
        }
        panoramaOptions.add(actionRow(visual, AppText.VIDEO_LAYOUT.text(), layoutButton))
        swapButton = button("") {
            chooseSetting(AppText.EYE_ORDER.text(), listOf(AppText.NORMAL.text(), AppText.SWAP_EYES.text()), if (settings.swapEyes) 1 else 0) {
                settings = settings.copy(swapEyes = it == 1); updateSettings()
            }
        }
        doubleLensOptions.add(actionRow(visual, AppText.SWAP_EYES.text(), swapButton))
        // 4.13：两圆垂直位置。头戴底部支撑把手机垫高时选「吸底」让镜片对准画面。
        verticalAlignButton = button("") {
            chooseSetting(AppText.VERTICAL_POSITION.text(),
                listOf(AppText.VERTICAL_CENTERED.text(), AppText.VERTICAL_BOTTOM.text()),
                if (settings.alignBottom) 1 else 0) {
                settings = settings.copy(alignBottom = it == 1)
                vrView.cancelPendingGestures(); updateSettings(); updateStatus()
            }
        }
        doubleLensOptions.add(actionRow(visual, AppText.VERTICAL_POSITION.text(), verticalAlignButton))
        phoneEyeButton = button("") {
            chooseSetting(AppText.VIEWING_LENS.text(), listOf(AppText.LEFT_LENS.text(), AppText.RIGHT_LENS.text()), if (settings.swapEyes) 1 else 0) {
                settings = settings.copy(swapEyes = it == 1); updateSettings()
            }
        }
        phoneEyeRow = actionRow(visual, AppText.VIEWING_LENS.text(), phoneEyeButton)
        correctionText = label("", 12f).apply { setPadding(0, dp(8), 0, dp(3)) }
        visual.addView(correctionText)
        doubleLensOptions.add(correctionText)
        correctionSeek = SeekBar(this).apply {
            max = 20; progress = settings.edgeCorrection / 5
            keyProgressIncrement = 1
            contentDescription = AppText.LENS_DISTORTION_CORRECTION_TO_IN_STEPS.text()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        compensationPrefs.edit().putInt(compensationKey(settings.eyeDiameterCm), progress * 5).apply()
                        updateSettings()
                    }
                }
            })
        }
        visual.addView(correctionSeek, LinearLayout.LayoutParams(-1, dp(38)))
        doubleLensOptions.add(correctionSeek)
        val correctionInfo = label(AppText.FOR_VR_HEADSETS_USE_FOR_DIRECT.text(), 10f).apply { setTextColor(UiStyle.MUTED) }
        visual.addView(correctionInfo)
        doubleLensOptions.add(correctionInfo)
        languageSettingsCard = row().apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = UiStyle.shape(this@MainActivity, UiStyle.SURFACE, 18)
        }
        languageSettingsCard.addView(label("语言/Language", 14f).apply {
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        languageSettingsCard.addView(button(AppText.LANGUAGE_NAME.text()) {
            chooseSetting(AppText.LANGUAGE.text(), listOf("简体中文", "English"), if (AppText.isEnglish()) 1 else 0) {
                changeLanguage(it == 1)
            }
        }, LinearLayout.LayoutParams(dp(156), dp(40)))
        left.addView(languageSettingsCard, 1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val display = card(right, AppText.SCREEN_CALIBRATION.text(), AppText.CENTER_THE_CIRCULAR_VIEWS_USING_THE.text())
        doubleLensOptions.add(display)
        screenInfo = label("", 11f).apply { setTextColor(UiStyle.MUTED); setLineSpacing(dp(1).toFloat(), 1f) }
        display.addView(screenInfo, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val screenActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screenActions.addView(button(AppText.DETECT_AGAIN.text()) { detectScreenParameters() }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(8) })
        screenActions.addView(button(AppText.ENTER_MANUALLY.text()) { editScreenParameters(false) }, LinearLayout.LayoutParams(-1, dp(40)))
        display.addView(screenActions)
        val controls = card(right, AppText.CONTROLS.text(), AppText.CHOOSE_HOW_YOU_PREFER_TO_WATCH.text())
        controlsCard = controls
        dragSwitch = toggle(controls, AppText.DRAG_TO_LOOK.text(), AppText.DRAG_WITH_ONE_FINGER_TO_LOOK.text(), vrView.dragEnabled) {
            vrView.dragEnabled = it; prefs.edit().putBoolean("drag_enabled", it).apply()
        }
        rotationSwitch = toggle(controls, AppText.AUTO_ROTATE.text(), AppText.SWITCH_BETWEEN_LANDSCAPE_ORIENTATIONS_WITH_THE.text(), prefs.getBoolean("rotate_enabled", false)) {
            if (!syncingRotationSwitches) setRotationEnabled(it)
        }
        val ordinaryPicture = card(left, AppText.PICTURE.text())
        ordinaryPictureCard = ordinaryPicture
        hdrSwitch = toggle(ordinaryPicture, "HDR", AppText.ON_AUTOMATIC_HDR_OUTPUT_OFF_DISPLAY.text(), hdrEnabled, ::setHdrEnabled)
        hdrSwitch.isEnabled = Build.VERSION.SDK_INT >= 31
        hdrSwitch.alpha = if (hdrSwitch.isEnabled) 1f else .5f
        hdrInfo = label("", 11f).apply { setTextColor(UiStyle.MUTED); setPadding(0, dp(6), 0, 0) }
        ordinaryPicture.addView(hdrInfo)
        val ordinaryControls = card(right, AppText.CONTROLS.text(), AppText.TOUCH_AND_ROTATION_SETTINGS_FOR_STANDARD.text())
        ordinaryControlsCard = ordinaryControls
        pinchSwitch = toggle(ordinaryControls, AppText.PINCH_TO_ZOOM.text(), AppText.PINCH_TO_ZOOM_AND_DRAG_TO.text(), prefs.getBoolean("flat_pinch_enabled", true)) {
            prefs.edit().putBoolean("flat_pinch_enabled", it).apply()
            flatVideo.zoomEnabled = it
        }
        ordinaryRotationSwitch = toggle(ordinaryControls, AppText.AUTO_ROTATE.text(), AppText.SWITCH_BETWEEN_LANDSCAPE_ORIENTATIONS_WITH_THE.text(), prefs.getBoolean("rotate_enabled", false)) {
            if (!syncingRotationSwitches) setRotationEnabled(it)
        }
        ordinaryControls.addView(label(AppText.AFTER_ZOOMING_TAP_RESET_IN_THE.text(), 11f).apply {
            setTextColor(UiStyle.MUTED); setPadding(0, dp(8), 0, 0)
        })
        val touchHelp = AppText.TAP_TO_SHOW_OR_HIDE_THE.text()
        val boundaryHelp = AppText.AT_THE_END_OR_WHEN_PAUSED.text()
        for (section in listOf(controls, ordinaryControls)) {
            section.addView(label("${touchHelp}\n${boundaryHelp}", 11f).apply {
                setTextColor(UiStyle.MUTED); setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(8), 0, dp(4))
            })
        }
        // 4.9：这里不再是静态手柄线稿，而是进入「功能 | 键位」可编辑列表的入口。
        val guide = card(body, AppText.PLAYER_GAMEPAD_TITLE.text(), AppText.PLAYER_GAMEPAD_HINT.text())
        controllerGuideCard = guide
        guide.addView(label(AppText.PLAYER_GAMEPAD_OPEN.text(), 13f).apply {
            setTextColor(UiStyle.ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = UiStyle.ripple(this@MainActivity, UiStyle.RAISED, 10)
            setOnClickListener { startActivity(Intent(this@MainActivity, GamepadMappingActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2))
        val about = card(body, AppText.ABOUT.text(), AppText.ASTRIAVR_A_NEW_VIEW_OF_YOUR.text())
        val info = packageManager.getPackageInfo(packageName, 0)
        about.addView(label(AppText.VERSION_LOCAL_VR_PLAYER_FOR_ANDROID.text(info.versionName), 12f))
        fun aboutSection(title: String, text: String) {
            about.addView(label(title, 12f).apply {
                setTextColor(UiStyle.TEXT); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(5))
            })
            about.addView(label(text, 11f).apply {
                setTextColor(UiStyle.MUTED); setLineSpacing(dp(4).toFloat(), 1f)
            })
        }
        aboutSection(AppText.WHAT_IT_CAN_DO.text(), AppText.ASTRIAVR_PLAYS_LOCAL_PANORAMIC_AND_STANDARD.text())
        aboutSection(AppText.HOW_VIDEO_BECOMES_YOUR_VIEW.text(), AppText.MEDIA_EXOPLAYER_HANDLES_PLAYBACK_AND_SYSTEM.text())
        aboutSection(AppText.ADJUST_FOR_A_COMFORTABLE_VIEW.text(), AppText.IPD_SETS_THE_SPACING_BETWEEN_THE.text())
        aboutSection(AppText.USEFUL_DETAILS.text(), AppText.IN_DUAL_LENS_MODE_PRESS_MENU.text())
        about.addView(label("GitHub @AstriaFR", 12f).apply {
            setTextColor(UiStyle.ACCENT); setPadding(0, dp(12), 0, 0)
        })
        settingsPage.addView(ScrollView(this).apply { isFillViewport = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(settingsPage, FrameLayout.LayoutParams(-1, -1))
    }

    private fun applyRotationPreference() {
        requestedOrientation = if (prefs.getBoolean("rotate_enabled", false))
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun setRotationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("rotate_enabled", enabled).apply()
        syncingRotationSwitches = true
        try {
            rotationSwitch.isChecked = enabled
            ordinaryRotationSwitch.isChecked = enabled
        } finally { syncingRotationSwitches = false }
        applyRotationPreference()
    }

    private fun openSettingsPage() {
        if (settingsOpen) return
        closeVrPlaylist(false)
        settingsOpen = true
        syncBitrateSampling()
        gamepad.clearInput()
        resetSeekSequence()
        stereoHint.clear()
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
        videoTitle.isSelected = false
        syncBackCallback()
    }

    private fun changeLanguage(english: Boolean) {
        if (languageChanging || english == AppText.isEnglish()) return
        languageChanging = true
        val current = player
        resumeAfterLanguageChange = PlaybackPosition.shouldResume(pendingPlay,
            current?.playWhenReady == true, current?.playbackState == Player.STATE_ENDED)
        languagePositionMs = current?.currentPosition ?: positionMs
        saveSettings()
        AppLanguage.select(this, english)
        // Rebuild every native and Canvas label; the saved session keeps the video and position.
        recreate()
    }

    private fun selectDiameter(diameter: Float) {
        settings = settings.copy(eyeDiameterCm = diameter).fitScreen()
        updateSettings()
        if (settings.eyeDiameterCm < diameter) notifyUser(AppText.THIS_SCREEN_SUPPORTS_CIRCULAR_VIEWS_UP.text(settings.eyeDiameterCm))
    }

    private fun restoreDefaults() {
        if (ordinaryVideo) setViewingMode(0)
        handler.removeCallbacks(persistSettings)
        gamepad.clearInput()
        resetSeekSequence()
        stereoHint.clear()
        vrView.cancelPendingGestures()
        setDebugMode(false)
        settings = RenderSettings()
        projectionMode = 0
        layoutMode = -1
        videoPrefs.edit().clear().apply()
        compensationPrefs.edit().clear().apply()
        prefs.edit().putInt("projection_mode", 0).putInt("layout_mode", -1)
            .putBoolean("gyro_enabled", true).putBoolean("drag_enabled", true)
            .putBoolean("rotate_enabled", false).putBoolean("flat_pinch_enabled", true)
            .putBoolean("screen_setup_done", false).apply()
        detectedScreenApplied = false
        customScreenApplied = false
        tracker.setGyroEnabled(true)
        tracker.recenter()
        vrView.dragEnabled = true
        dragSwitch.isChecked = true
        rotationSwitch.isChecked = false
        ordinaryRotationSwitch.isChecked = false
        pinchSwitch.isChecked = true
        setHdrEnabled(true)
        flatVideo.zoomEnabled = true
        applyRotationPreference()
        speedIndex = PlaybackTuning.DEFAULT_SPEED
        player?.setPlaybackSpeed(PlaybackTuning.speed(speedIndex))
        brightness = -1f
        configureWindow()
        updateSettings()
        updateStatus()
        // Re-run first-launch physical-screen setup after rotation/layout settles.
        firstSetupShown = false
        notifyUser(AppText.DEFAULT_SETTINGS_RESTORED.text())
    }

    private fun applyScreen(w: Double, h: Double, custom: Boolean) {
        detectedScreenApplied = !custom
        customScreenApplied = custom
        val previousDiameter = settings.eyeDiameterCm
        settings = settings.copy(screenWidthCm = w, screenHeightCm = h).fitScreen()
        prefs.edit().putBoolean("screen_setup_done", true).apply()
        updateSettings()
        if (settings.eyeDiameterCm < previousDiameter) notifyUser(AppText.FIELD_DIAMETER_ADJUSTED_TO_CM.text(settings.eyeDiameterCm))
    }

    private fun useDefaultScreen() {
        detectedScreenApplied = false
        customScreenApplied = false
        settings = settings.copy(screenWidthCm = Optics.WIDTH_CM, screenHeightCm = Optics.HEIGHT_CM).stepIpd(0)
        prefs.edit().putBoolean("screen_setup_done", true).apply()
        updateSettings()
    }

    private fun screenFallback(reason: String, firstLaunch: Boolean) {
        showSettingsDialog(SettingsDialog.message(this, AppText.SCREEN_DETECTION_INCOMPLETE.text(),
            AppText.N_NENTER_THE_SCREEN_S_PHYSICAL.text(reason),
            listOf(SettingsDialog.Action(AppText.USE_DEFAULTS.text(), accepted = ::useDefaultScreen),
                SettingsDialog.Action(AppText.ENTER_MANUALLY.text(), true, accepted = { editScreenParameters(firstLaunch) })), !firstLaunch))
    }

    private fun editScreenParameters(firstLaunch: Boolean) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun field(title: String, current: Double): EditText {
            panel.addView(label(title, 13f))
            return EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine()
                setTextColor(UiStyle.TEXT)
                setText(String.format(Locale.ROOT, "%.2f", current))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = UiStyle.glass(this@MainActivity, UiStyle.RAISED, 16)
                panel.addView(this, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6); bottomMargin = dp(12) })
            }
        }
        panel.addView(label(AppText.ENTER_THE_ILLUMINATED_SCREEN_SIZE_EXCLUDING.text(), 12f))
        val longEdge = field(AppText.LONG_EDGE_CM.text(), settings.screenWidthCm)
        val shortEdge = field(AppText.SHORT_EDGE_CM.text(), settings.screenHeightCm)
        val validation = label("", 11f).apply { setTextColor(0xFFFFA9AA.toInt()); visibility = View.GONE }
        panel.addView(validation)
        shortEdge.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        showSettingsDialog(SettingsDialog(this, AppText.CUSTOM_SCREEN_SIZE.text(), panel,
            listOf(SettingsDialog.Action(if (firstLaunch) AppText.USE_DEFAULTS.text() else AppText.CANCEL.text(), accepted = { if (firstLaunch) useDefaultScreen() }),
                SettingsDialog.Action(AppText.SAVE_APPLY.text(), true, validate = {
                val w = Optics.parseScreenCm(longEdge.text.toString())
                val h = Optics.parseScreenCm(shortEdge.text.toString())
                val problem = when {
                    !w.isFinite() -> AppText.ENTER_A_VALUE_ABOVE_AND_UP.text()
                    !h.isFinite() || h > 35 -> AppText.ENTER_A_VALUE_ABOVE_AND_UP_151.text()
                    w < h -> AppText.THE_LONG_EDGE_MUST_BE_AT.text()
                    !Optics.fits(4f, w, h) -> AppText.THIS_SIZE_CANNOT_FIT_THE_MINIMUM.text()
                    else -> null
                }
                if (problem == null) { applyScreen(w, h, true); true }
                else {
                    validation.text = problem; validation.visibility = View.VISIBLE
                    validation.post { validation.requestRectangleOnScreen(android.graphics.Rect(0, 0, validation.width, validation.height), false) }
                    false
                }
            })), !firstLaunch))
    }

    @Suppress("DEPRECATION")
    private fun detectScreenParameters(firstLaunch: Boolean = false) {
        val metrics = android.util.DisplayMetrics()
        if (runCatching { windowManager.defaultDisplay.getRealMetrics(metrics) }.isFailure) {
            screenFallback(AppText.COULD_NOT_READ_THE_SYSTEM_S.text(), firstLaunch)
            return
        }
        val dimensions = Optics.detectedScreen(metrics.widthPixels, metrics.heightPixels, metrics.xdpi, metrics.ydpi)
        if (dimensions == null) { screenFallback(AppText.THE_SYSTEM_DID_NOT_PROVIDE_VALID.text(), firstLaunch); return }
        val w = Math.round(dimensions[0] * 100) / 100.0
        val h = Math.round(dimensions[1] * 100) / 100.0
        val fullScreen = kotlin.math.abs(root.width - metrics.widthPixels) <= 4 &&
            kotlin.math.abs(root.height - metrics.heightPixels) <= 4
        if (!fullScreen || !Optics.fits(4f, w, h)) {
            screenFallback(if (!fullScreen) AppText.THE_APP_IS_NOT_USING_THE.text() else
                AppText.THE_DETECTED_SIZE_CM_CANNOT_FIT.text(w, h), firstLaunch)
            return
        }
        if (firstLaunch) { applyScreen(w, h, false); return }
        val result = String.format(Locale.ROOT,
            AppText.SYSTEM_RESOLUTION_D_D_PX_NPHYSICAL.text(),
            metrics.widthPixels, metrics.heightPixels, metrics.xdpi, metrics.ydpi, w, h, Math.hypot(w, h) / 2.54)
        showSettingsDialog(SettingsDialog.message(this, AppText.DETECTED_SCREEN_SIZE.text(), result,
            listOf(SettingsDialog.Action(AppText.CANCEL.text()), SettingsDialog.Action(AppText.ENTER_MANUALLY.text(), accepted = { editScreenParameters(false) }),
                SettingsDialog.Action(AppText.APPLY.text(), true, accepted = { applyScreen(w, h, false) }))))
    }

    private fun closeSettingsPage() {
        if (!settingsOpen) return
        settingsOpen = false
        gamepad.clearInput()
        resetSeekSequence()
        settingsPage.visibility = View.GONE
        syncBackCallback()
        showControls(controlsVisible)
    }

    @Deprecated("Legacy Android back button")
    // API 33+ uses the registered OnBackInvokedCallback above; this is only the older OS fallback.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (vrMenuActive) { vrMenuSession?.handle(VrMenuInput.Back); return }
        if (::playlistInput.isInitialized && playlistInput.open) closeVrPlaylist(true)
        else if (playlistOpen) { if (!playlistPage.handleBack()) closePlaylistPage(true) }
        else if (settingsOpen) closeSettingsPage()
        // H0：退出播放页必须回到首页（而不是桌面），且首页状态由返回栈保留。
        else if (!startupReady || ordinaryVideo || !gamepad.hasConnectedController()) PlayerLauncher.goHome(this)
        else stereoHint.showValue(AppText.CONTROLLER_CONNECTED_BACK_WILL_NOT_EXIT.text())
    }

    private fun syncBackCallback() {
        if (!startupReady || Build.VERSION.SDK_INT < 33) return
        val needed = vrMenuActive || settingsOpen || playlistOpen || playlistInput.open || (!ordinaryVideo && gamepad.hasConnectedController())
        val navigation = navigationBack ?: BackNavigation.create(this, ::handleBack).also { navigationBack = it }
        navigation.setEnabled(needed)
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_DIP, size); setTextColor(UiStyle.TEXT)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f); isAllCaps = false
        setTextColor(UiStyle.ACCENT); setTypeface(typeface, Typeface.BOLD)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(3), 0, dp(3), 0)
        backgroundTintList = null; stateListAnimator = null; elevation = 0f
        background = UiStyle.ripple(this@MainActivity, UiStyle.RAISED, 12)
        setOnClickListener { action() }
    }
    private fun iconButton(icon: String, description: String, action: () -> Unit) = ImageButton(this).apply {
        contentDescription = description
        setImageDrawable(PlayerIcon(icon))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = UiStyle.ripple(this@MainActivity, UiStyle.RAISED, 14)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun compensationKey(diameter: Float) = when {
        diameter <= 5f -> "band_4_to_5"
        diameter <= 6f -> "band_5_2_to_6"
        else -> "band_6_2_to_7"
    }

    private fun readCompensation(diameter: Float): Int {
        val value = compensationPrefs.getInt(compensationKey(diameter), if (diameter <= 6f) 50 else 0)
        return ((value.coerceIn(0, 100) + 2) / 5) * 5
    }

    private fun updateSettings(persist: Boolean = true) {
        settings = settings.fitScreen()
        settings = settings.copy(edgeCorrection = readCompensation(settings.eyeDiameterCm))
        resolveProjection()
        vrView.vrRenderer.settings = settings
        stereoHint.settings = if (ordinaryVideo && !debugMode) settings.copy(phoneMode = true, phoneFillScreen = true) else settings
        vrPlaylist.settings = settings
        if ((settings.phoneMode || ordinaryVideo) && playlistInput.open) closeVrPlaylist(false)
        source?.takeIf { !sourceLoading }?.let {
            val layout = if (ordinaryVideo) 2 else settings.layout
            val degrees = if (ordinaryVideo) 0 else settings.sourceProjection
            val key = "${it}|${layout}|${degrees}"
            if (savedCoverProjection != key) {
                val uri = it.toString()
                playlist.submit({
                    val previous = playlist.find(uri)
                    playlist.setProjection(uri, layout, degrees)
                    previous?.takeIf { it.layout != layout || it.projection != degrees }
                }) { result ->
                    if (!closing) result.getOrNull()?.let { covers.invalidate(it) }
                }
                savedCoverProjection = key
            }
        }
        viewingModeButton.text = if (settings.screenMode) AppText.BIG_SCREEN_MODE.text() else if (ordinaryVideo) AppText.SETTINGS_STANDARD_VIDEO.text() else if (settings.phoneMode) AppText.SINGLE_LENS_VR.text() else AppText.DUAL_LENS_VR.text()
        viewingModeButton.contentDescription = AppText.VIEWING_MODE_166.text(if (settings.screenMode) AppText.BIG_SCREEN_MODE.text() else if (ordinaryVideo) AppText.SETTINGS_STANDARD_VIDEO.text() else if (settings.phoneMode) AppText.PHONE_SINGLE_LENS_VR_164.text() else AppText.D_DUAL_LENS_VR_165.text())
        screenSizeButton?.text = "${settings.screenFovDegrees}°"
        screenFlipButton?.text = screenFlipLabel()
        controlsCard.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        ordinaryControlsCard.visibility = if (ordinaryVideo) View.VISIBLE else View.GONE
        ordinaryPictureCard.visibility = if (ordinaryVideo) View.VISIBLE else View.GONE
        if (ordinaryVideo) updateHdrInfo()
        controllerGuideCard.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        debugButton.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        phoneAspectButton.text = if (settings.phoneFillScreen) AppText.FULL_SCREEN.text() else AppText.LETTERBOX.text()
        phoneShapeButton.text = if (settings.phoneElliptical) AppText.ELLIPSE.text() else AppText.DYNAMIC_RECTANGLE.text()
        phoneCorrectionText.text = AppText.ULTRAWIDE_CORRECTION.text(settings.phoneWideCorrection)
        phoneCorrectionSeek.progress = settings.phoneWideCorrection / 5
        if (Build.VERSION.SDK_INT >= 30) phoneCorrectionSeek.stateDescription = "${settings.phoneWideCorrection}%"
        for (option in singleLensOptions) option.visibility = if (settings.phoneMode && !ordinaryVideo) View.VISIBLE else View.GONE
        for (option in doubleLensOptions) option.visibility = if (settings.phoneMode || ordinaryVideo) View.GONE else View.VISIBLE
        for (option in panoramaOptions) option.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        visualCard.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        bigScreenCard?.visibility = if (settings.screenMode && !ordinaryVideo) View.VISIBLE else View.GONE
        phoneCorrectionPanel.visibility = if (!ordinaryVideo && settings.phoneMode && !settings.phoneElliptical) View.VISIBLE else View.GONE
        phoneEyeRow.visibility = if (!ordinaryVideo && settings.phoneMode && settings.layout != 2) View.VISIBLE else View.GONE
        phoneEyeButton.text = if (settings.swapEyes) AppText.RIGHT_LENS.text() else AppText.LEFT_LENS.text()
        // 4.9：静态手柄图不再随 phoneMode 切换，键位表独立页面统一显示。
        gyroButton.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        recenterButton.visibility = if (ordinaryVideo) View.GONE else View.VISIBLE
        flatResetButton.visibility = if (ordinaryVideo && source != null) View.VISIBLE else View.GONE
        var topVisible = 0
        for (i in 0 until topActions.childCount) topActions.getChildAt(i).let { button ->
            val params = button.layoutParams as LinearLayout.LayoutParams
            val margin = if (button.visibility == View.VISIBLE && topVisible++ > 0) dp(6) else 0
            if (params.marginStart != margin) { params.marginStart = margin; button.layoutParams = params }
        }
        val actionsWidth = topVisible * dp(48) + (topVisible - 1).coerceAtLeast(0) * dp(6)
        if (topActions.layoutParams.width != actionsWidth) topActions.layoutParams = topActions.layoutParams.apply { width = actionsWidth }
        dragSwitch.isEnabled = !ordinaryVideo; dragSwitch.alpha = if (ordinaryVideo) .4f else 1f
        updateTuningLayout()
        syncVideoMode()
        val minIpd = Optics.minIpd(settings.eyeDiameterCm)
        val maxIpd = Optics.maxIpd(settings.eyeDiameterCm, settings.screenWidthCm)
        diameterText.text = AppText.CIRCULAR_FIELD_PER_EYE_CM.text(settings.eyeDiameterCm)
        diameterSeek.progress = Math.round(settings.eyeDiameterCm * 5) - 20
        if (Build.VERSION.SDK_INT >= 30) diameterSeek.stateDescription = AppText.CM.text(settings.eyeDiameterCm)
        screenInfo.text = String.format(Locale.ROOT, AppText.S_SCREEN_F_F_CM_NDIAMETER.text(),
            if (customScreenApplied) AppText.CUSTOM_SCREEN_SIZE.text() else if (detectedScreenApplied) AppText.DETECTED_SCREEN_SIZE_APPLIED.text() else AppText.DEFAULT_INCHES.text(),
            settings.screenWidthCm, settings.screenHeightCm, settings.eyeDiameterCm, minIpd, maxIpd)
        opticsText.text = "${if (settings.phoneMode) settings.phoneFovDegrees else settings.fovDegrees}°"
        opticsText.contentDescription = if (settings.phoneMode) AppText.HORIZONTAL_FIELD_OF_VIEW_DEGREES.text(settings.phoneFovDegrees) else AppText.FIELD_OF_VIEW_DEGREES.text(settings.fovDegrees)
        correctionText.text = AppText.LENS_DISTORTION_CORRECTION.text(settings.edgeCorrection)
        correctionSeek.progress = settings.edgeCorrection / 5
        if (Build.VERSION.SDK_INT >= 30) correctionSeek.stateDescription = "${settings.edgeCorrection}%"
        ipdText.text = String.format(Locale.ROOT, "%.1f", settings.ipdCm)
        projectionButton.text = (if (projectionMode == 0) AppText.AUTO.text() else "") + settingsProjectionLabel(settings.sourceProjection)
        val layoutName = arrayOf(AppText.SIDE_BY_SIDE_SBS.text(), AppText.TOP_BOTTOM_TB.text(), AppText.MONO.text())[settings.layout]
        val singleFisheye = settings.sourceProjection == VideoProjection.FISHEYE_180
        layoutButton.isEnabled = !singleFisheye
        layoutButton.alpha = if (singleFisheye) .5f else 1f
        val autoLayout = layoutMode == -1 || (settings.sourceProjection == VideoProjection.STEREO_FISHEYE_180 && layoutMode == 2)
        layoutButton.text = if (singleFisheye) AppText.MONO.text() else if (autoLayout) AppText.AUTO_177.text(layoutName) else layoutName
        swapButton.text = if (settings.swapEyes) AppText.ON.text() else AppText.OFF.text()
        verticalAlignButton.text =
            if (settings.alignBottom) AppText.VERTICAL_BOTTOM.text() else AppText.VERTICAL_CENTERED.text()
        if (persist) {
            handler.removeCallbacks(persistSettings)
            handler.postDelayed(persistSettings, 300)
        }
    }

    private fun settingsProjectionLabel(projection: Int) = when (projection) {
        0 -> AppText.SETTINGS_AUTO.text()
        180 -> AppText.SETTINGS_EQ_180.text()
        360 -> AppText.SETTINGS_EQ_360.text()
        VideoProjection.FISHEYE_180 -> AppText.SETTINGS_MONO_FISHEYE.text()
        VideoProjection.STEREO_FISHEYE_180 -> AppText.SETTINGS_STEREO_FISHEYE.text()
        VideoProjection.CUBEMAP -> AppText.SETTINGS_CUBEMAP.text()
        VideoProjection.EAC -> AppText.SETTINGS_EAC.text()
        else -> VideoProjection.label(projection)
    }

    private fun saveSettings() {
        handler.removeCallbacks(persistSettings)
        prefs.edit().putFloat("ipd", settings.ipdCm).putInt("layout", settings.layout)
            .putBoolean("swap", settings.swapEyes).putFloat("brightness", brightness)
            .putInt("fov_8deg", settings.fovDegrees).putFloat("eye_diameter", settings.eyeDiameterCm)
            .putBoolean("phone_mode", settings.phoneMode).putInt("phone_fov", settings.phoneFovDegrees)
            .putBoolean("ordinary_video", ordinaryVideo)
            .putBoolean("phone_fill_screen", settings.phoneFillScreen)
            .putBoolean("phone_elliptical", settings.phoneElliptical)
            .putInt("phone_rect_compensation_v2", settings.phoneWideCorrection)
            .putBoolean("screen_mode", settings.screenMode).putInt("screen_fov", settings.screenFovDegrees)
            .putInt("screen_flip", settings.screenFlip)
            .putBoolean("align_bottom", settings.alignBottom)
            .putBoolean("screen_detected", detectedScreenApplied).putBoolean("screen_custom", customScreenApplied)
            .putFloat("screen_width_cm", settings.screenWidthCm.toFloat())
            .putFloat("screen_height_cm", settings.screenHeightCm.toFloat()).apply()
    }

    private fun projectionKey(uri: Uri): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun loadProjectionChoice() {
        val key = source?.let(::projectionKey)
        projectionMode = (if (key == null) prefs.getInt("projection_mode", 0) else
            videoPrefs.getInt("${key}_projection", prefs.getInt("projection_mode", 0)))
            .let { if (VideoProjection.valid(it)) it else 0 }
        layoutMode = (if (key == null) prefs.getInt("layout_mode", -1) else
            videoPrefs.getInt("${key}_layout", prefs.getInt("layout_mode", -1))).coerceIn(-1, 2)
    }

    private fun saveProjectionChoice() {
        val key = source?.let(::projectionKey)
        if (key == null) prefs.edit().putInt("projection_mode", projectionMode).putInt("layout_mode", layoutMode).apply()
        else videoPrefs.edit().putInt("${key}_projection", projectionMode).putInt("${key}_layout", layoutMode).apply()
    }

    private fun resolveProjection() {
        val format = videoFormat
        val stereo = when (format?.stereoMode) {
            C.STEREO_MODE_LEFT_RIGHT -> 0
            C.STEREO_MODE_TOP_BOTTOM -> 1
            C.STEREO_MODE_MONO -> 2
            else -> -1
        }
        val requested = VideoProjection.forViewingMode(projectionMode, settings.phoneMode)
        val selection = VideoProjection.resolve(requested, layoutMode, metadataProjection, stereo,
            if (source == null) "" else sourceName, format?.width ?: 0, format?.height ?: 0,
            format?.pixelWidthHeightRatio ?: 1f)
        settings = settings.copy(projectionDegrees = selection.degrees, layout = selection.layout, sourceProjection = selection.projection,
            cubePolesFlipped = selection.projection == VideoProjection.CUBEMAP && metadataProjection == VideoProjection.CUBEMAP)
        projectionButton.contentDescription = AppText.SOURCE_PROJECTION_180.text(VideoProjection.label(selection.projection), selection.reason)
        projectionWarning = if (metadataProjection < 0 && projectionMode == 0 && selection.projection in intArrayOf(180, 360))
            AppText.UNKNOWN_PROJECTION_TAG_CHOOSE_THE_SOURCE.text() else ""
    }

    private fun updateVideoFormat(format: Format?) {
        videoFormat = format
        metadataProjection = VideoProjection.metadataDegrees(format?.projectionData)
        enforceDefaultViewingMode()
        updateSettings(false)
        updateStatus()
    }

    /**
     * 4.11：打开片源时把观看模式归一到默认的 3D 双眼（双透镜 VR）。
     *
     * 自动识别不再参与观看模式：以前「没认出 VR 标记」就会跳 FlatVideoView，
     * VR 菜单/参数浮层随之消失，戴镜后只能摘机回 2D 设置页切回来。现在只有
     * 用户在 2D 设置页 / 参数页 / VR 影院 / 串流里**显式**选的模式才生效
     * （manualViewingChoice = true），其余一律回 VrView 的双透镜渲染。
     * 片源投影与布局仍由 resolveProjection() 解析，识别不准就在参数页改。
     */
    private fun enforceDefaultViewingMode() {
        if (source == null || manualViewingChoice) return
        val mode = ViewingModePolicy.decide(ordinaryVideo, settings.screenMode, settings.phoneMode) ?: return
        // changeViewingMode 不会动 screenMode，先关掉大屏分支；调用方随后都会
        // updateSettings(false)，把新的 RenderSettings 送到 VrRenderer。
        settings = settings.copy(screenMode = false)
        changeViewingMode(mode, automatic = true)
    }

    @Suppress("DEPRECATION")
    private fun openVideo(addToPlaylist: Boolean = false) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, addToPlaylist)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, if (addToPlaylist) ADD_VIDEOS else OPEN_VIDEO)
        } catch (error: Exception) {
            notifyUser(AppText.COULD_NOT_OPEN_THE_FILE_PICKER.text(error.message), Toast.LENGTH_LONG)
        }
    }

    @Deprecated("Uses the platform picker without another Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADD_VIDEOS && resultCode == RESULT_OK && data != null) {
            val uris = linkedSetOf<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            importVideos(uris.toList(), data.flags)
            return
        }
        if (requestCode != OPEN_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Temporary access may still be sufficient for this session. */ }
        }
        openSource(uri)
    }

    /**
     * 新设备首次串流：APP 自己生成并显示 4 位 PIN，后台发起挂起的 getservercert。
     * 用户到 Sunshine Web UI 输入 PIN 后，剩余 5 步必须在**同一线程**一气呵成
     * （配对会话只活 5 分钟，Web UI 提交 PIN 后留给客户端的窗口更短）。
     * 成功后服务端证书落盘，再回到 startStream() 走正常起流。
     */
    private fun showPinPairing(host: StreamHost, identity: StreamIdentity) {
        val generation = ++pairingGeneration
        val pin = StreamPairing.generatePin()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label(AppText.STREAM_PIN_HINT.text(), 14f).apply {
            setTextColor(UiStyle.MUTED)
        })
        body.addView(label(pin, 46f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(18))
            letterSpacing = 0.25f
        })
        val status = label(AppText.STREAM_PIN_WAITING.text(), 13f).apply {
            setTextColor(UiStyle.MUTED)
        }
        body.addView(status)

        val dialog = SettingsDialog(this, AppText.STREAM_PIN_TITLE.text(), body,
            listOf(SettingsDialog.Action(AppText.CANCEL.text(), accepted = {
                cancelled.set(true)
                pairingGeneration++
                pairingDialog = null
            })), cancellable = true)
        pairingDialog = dialog
        dialog.setOnDismissListener { if (pairingDialog === dialog) pairingDialog = null }
        dialog.show()
        android.util.Log.i("VRStreamPairing",
            "等待 PC 确认 PIN host=" + host.ip + " pin=" + pin)

        Thread {
            val result = StreamPairing.pair(identity, host.ip, host.httpPort,
                host.httpsPort, pin)
            handler.post {
                // 取消、Activity 已销毁、或用户在别处重新发起了配对，都丢弃这次结果。
                if (cancelled.get() || generation != pairingGeneration ||
                    isFinishing || isDestroyed) return@post
                dialog.dismiss()
                pairingDialog = null
                if (result.isPaired) {
                    notifyUser(AppText.STREAM_PIN_OK.text())
                    startStream(host)
                } else {
                    ConfirmationDialog(this, AppText.STREAM_PIN_FAILED_TITLE.text(),
                        result.detail, AppText.PAIR_RETRY.text()) {
                        showPinPairing(host, identity)
                    }.show()
                }
            }
        }.apply { isDaemon = true; name = "vr-stream-pairing" }.start()
    }

    /**
     * H0：消费首页发来的 [PlayerRoute]。首页不传任何观看参数，播放页按自己保存的
     * IPD / 投影 / 银幕设置渲染；串流只传 IP，能力集在这里重新 probe，避免
     * 把 Sunshine 的整个 serverinfo 塞进 Intent。
     */
    private fun applyRoute(route: PlayerRoute) {
        if (closing || isFinishing || isDestroyed) return
        when (route) {
            is PlayerRoute.Video -> {
                // resumePosition=null 时 openSource 会自动查播放记录续播；0 表示从头。
                openSource(Uri.parse(route.uri), if (route.fromStart) 0L else null, route.name)
            }
            is PlayerRoute.Stream -> {
                StreamControl.probe(route.hostIp) { host ->
                    if (closing || isFinishing || isDestroyed) return@probe
                    if (host == null) {
                        notifyUser(AppText.NO_STREAM_HOST.text(), Toast.LENGTH_LONG)
                        return@probe
                    }
                    startStream(host)
                }
            }
            PlayerRoute.VrMenu -> enterVrMenu()
        }
    }

    // ── H4/S4a：VR 菜单态 ────────────────────────────────────────────────────

    /**
     * 4.12：VR 菜单重新定位为「操控覆盖层」，不是一种观看模式。三条入口共用这里：
     * - `fromHome=true, params=false`：首页右上「进入 VR」，选片后进入页面1 沉浸双眼播放；
     * - `fromHome=false, params=false`：页面1 按「菜单」键呼出完整菜单，退出回页面1；
     * - `fromHome=false, params=true`：页面1 按「参数」键直达观看参数页。
     * 菜单是 world-locked 平面（shader 已解耦，球面 / 鱼眼下也能叠加），不碰片源观看模式；
     * 选片后覆盖层直接关闭、不留常驻进度条 —— 要调进度再按菜单键呼出。
     */
    private fun enterVrMenu(fromHome: Boolean = true, params: Boolean = false) {
        if (closing || isFinishing || isDestroyed) return
        vrMenuActive = true
        vrMenuFromHome = fromHome
        // 从播放页 / 换片列表切进 VR 时清掉仍按着的键与长按重复任务，
        // 否则残留状态会在戴镜后以抬手事件漏进播放页手柄层。
        if (::playlistInput.isInitialized) playlistInput.clear()
        // 4.11：唤起菜单就**停止 VR 播放控制**。GamepadInput 是转视角 / 快进 /
        // FOV / 音量的唯一来源，stop() 会注销设备监听并停掉 Choreographer 帧循环；
        // 十字键与左摇杆改由 menuAxis 接管为菜单导航（见 dispatchGenericMotionEvent）。
        if (::gamepad.isInitialized) gamepad.stop()
        menuAxis.clear()
        cancelMenuAxisRepeat()
        // 从摘镜状态点「进入 VR」时把屏幕点亮并保持常亮：戴镜后无法再触屏唤醒。
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manualViewingChoice = true
        // 4.12：VR 操控层不再是「大屏影院」，它会叠加在用户当前的双眼 / 球面上。
        // 首页进入时片源为空，保持默认 3D 双眼即可（片源模式在选片时归一），
        // 不再强制 setViewingMode(3)；这样选片后也无需从大屏跳回沉浸模式。
        vrView.vrRenderer.resetFrame()
        hideVrOverlayViews()
        val renderer = VrMenuRenderer().apply {
            onDirty = { bmp -> vrView.vrRenderer.setMenu(bmp) }
        }
        vrMenuRenderer = renderer
        val session = VrMenuSession().apply {
            onPlay = { route ->
                manualViewingChoice = true
                // 4.12：选片即进入「页面1」—— 强制 3D 双眼沉浸，绝不留在 VR 影院大屏；
                // 然后**关闭整个菜单覆盖层**（menuMode=0、隐藏 2D 控件），不留常驻进度条。
                // 想调进度 / 换片 / 改参数，随时按菜单键再呼出，全程不用摘头盔。
                setViewingMode(ViewingModePolicy.DUAL_LENS_VR)
                openSource(Uri.parse(route.uri), null, route.name, revealControls = false)
                manualViewingChoice = true
                hideVrOverlayViews()
                closeVrMenu()
            }
            onCommand = { key, delta -> handleVrMenuCommand(key, delta) }
            onExit = { if (fromHome) PlayerLauncher.goHome(this@MainActivity) else closeVrMenu() }
            onResumePlayback = { if (!fromHome) closeVrMenu() }
            onCloseMenu = { if (fromHome) PlayerLauncher.goHome(this@MainActivity) else closeVrMenu() }
            onChange = { refreshVrMenu() }
        }
        vrMenuSession = session
        session.setLibrary(loadVrMenuEntries())
        pushVrMenuExtras()
        if (params) session.openParams()
        refreshVrMenu()
        syncBackCallback()
    }

    /**
     * 4.12：页面1（沉浸播放）按「菜单」键 → 呼出完整 VR 操控菜单（片源库 / 播放 / 参数），
     * 再按菜单键或 B 收起回页面1。菜单叠加在当前观看模式上，不动片源、不动进度。
     */
    private fun openVrMenu() {
        if (closing || isFinishing || isDestroyed || vrMenuActive) return
        if (source == null || streamSession != null) return
        enterVrMenu(fromHome = false, params = false)
    }

    /**
     * 4.10：播放中按「参数」键 → 原地浮出观看参数页；不改进度、不改观看模式。
     * 4.11：即使当前已经落在 2D 普通视频态（旧偏好或用户手动选过），也允许一个
     * 按键直接回到 3D 双眼并呼出参数页 —— 以前只能把手机从盒子里拿出来，
     * 回 2D 设置页切模式，因为 FlatVideoView 上根本没有菜单纹理可贴。
     */
    private fun openVrParams() {
        if (closing || isFinishing || isDestroyed || vrMenuActive) return
        if (source == null) return
        if (ordinaryVideo) {
            // setViewingMode 会写回 ordinary_override=false 并置 manualViewingChoice，
            // 之后自动归一不会再把这个片源切回 2D；播放进度由 changeViewingMode 保留。
            setViewingMode(ViewingModePolicy.DUAL_LENS_VR)
        }
        enterVrMenu(fromHome = false, params = true)
    }

    /**
     * 关掉 VR 操控覆盖层，回到页面1 沉浸播放：菜单纹理撤掉（menuMode=0）、
     * 隐藏 2D 控件（不留常驻进度条）、恢复播放页手柄控制，视频带着原进度继续播。
     * 首页进入且尚未选片时不走这里 —— 那一路在 onExit 里回 2D 首页。
     */
    private fun closeVrMenu() {
        if (!vrMenuActive) return
        vrMenuActive = false
        vrMenuSession = null
        vrMenuRenderer = null
        vrView.vrRenderer.menuMode = 0
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::playlistInput.isInitialized) playlistInput.clear()
        // 4.11：退出菜单才恢复播放页手柄控制；菜单期间的轴状态必须归零，
        // 否则回中事件丢失会让摇杆一回来就猛转视角。
        menuAxis.clear()
        cancelMenuAxisRepeat()
        if (::gamepad.isInitialized) {
            gamepad.clearInput()
            if (active && !ordinaryVideo) gamepad.start()
        }
        hideVrOverlayViews()
        syncBackCallback()
        updateStatus()
    }

    /** 戴镜后 2D View 树会在左右眼各露出一半，VR 菜单态必须全部隐藏。 */
    private fun hideVrOverlayViews() {
        controlsVisible = false
        for (bar in arrayOf(topBar, bottomBar)) {
            bar.animate().cancel(); bar.alpha = 0f; bar.visibility = View.GONE
        }
        idleBackdrop.visibility = View.GONE
        if (::vrPlaylist.isInitialized) vrPlaylist.visibility = View.GONE
        videoTitle.isSelected = false
    }

    private fun loadVrMenuEntries(): List<VrMenuEntry> {
        val result = mutableListOf<VrMenuEntry>()
        try {
            playlist.query().use { cursor ->
                while (cursor.moveToNext()) {
                    val entry = PlaylistStore.read(cursor)
                    result += VrMenuEntry(entry.id.toString(), entry.name, entry.uri)
                }
            }
        } catch (error: Exception) {
            notifyUser(AppText.COULD_NOT_LOAD_PLAYLIST.text(), Toast.LENGTH_SHORT)
        }
        return result
    }

    /** 把 Session 的模式/播放信息同步给 Canvas 渲染器与 shader uniform。 */
    private fun refreshVrMenu() {
        if (!vrMenuActive) return
        val session = vrMenuSession ?: return
        val renderer = vrMenuRenderer ?: return
        renderer.mode = session.mode
        renderer.extras = session.extras
        renderer.paramsMode = session.paramsOnly
        vrView.vrRenderer.menuMode = if (session.mode == VrMenuMode.MENU) 1 else 2
        renderer.render(session.state)
    }

    /** 播放进度与观看参数当前值；extras 相等时 Session 会短路，不会递归重绘。 */
    private fun pushVrMenuExtras() {
        val session = vrMenuSession ?: return
        val current = player
        session.setExtras(VrMenuExtras(
            nowPlaying = if (source == null) "" else sourceName.orEmpty(),
            playing = current?.playWhenReady == true,
            positionMs = current?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = current?.duration?.takeIf { it > 0 } ?: 0L,
            values = mapOf(
                VrMenuCommands.VIEWING_MODE to vrViewingModeLabel(),
                VrMenuCommands.PROJECTION to
                    (if (projectionMode == 0) AppText.AUTO.text() + " · " else "") +
                    settingsProjectionLabel(settings.sourceProjection),
                VrMenuCommands.LAYOUT to
                    (if (layoutMode == -1) AppText.AUTO.text() + " · " else "") + vrLayoutLabel(),
                VrMenuCommands.SWAP_EYES to if (settings.swapEyes) AppText.ON.text() else AppText.OFF.text(),
                VrMenuCommands.VERTICAL_ALIGN to
                    (if (settings.alignBottom) AppText.VERTICAL_BOTTOM.text() else AppText.VERTICAL_CENTERED.text()),
                VrMenuCommands.IPD to String.format(java.util.Locale.ROOT, "%.1f cm", settings.ipdCm),
                VrMenuCommands.SCREEN_SIZE to "${settings.screenFovDegrees}°",
                VrMenuCommands.SCREEN_FLIP to screenFlipLabel(),
                VrMenuCommands.FOV to "${settings.fovDegrees}°",
                VrMenuCommands.SPEED to "${PlaybackTuning.speed(speedIndex)}x")))
    }

    /** 与 2D 设置页同一套模式名，戴镜时不会读到另一套说法。 */
    private fun vrViewingModeLabel() =
        if (settings.screenMode) AppText.BIG_SCREEN_MODE.text()
        else if (ordinaryVideo) AppText.SETTINGS_STANDARD_VIDEO.text()
        else if (settings.phoneMode) AppText.SINGLE_LENS_VR.text()
        else AppText.DUAL_LENS_VR.text()

    private fun vrLayoutLabel() = when (settings.layout) {
        0 -> AppText.SIDE_BY_SIDE_SBS.text()
        1 -> AppText.TOP_BOTTOM_TB.text()
        else -> AppText.MONO.text()
    }

    private fun handleVrMenuCommand(key: String, delta: Int) {
        val step = if (delta < 0) -1 else 1
        when (key) {
            VrMenuCommands.PLAY_PAUSE -> togglePlayback()
            VrMenuCommands.SEEK_BACK -> seekBy(-10000L)
            VrMenuCommands.SEEK_FORWARD -> seekBy(10000L)
            VrMenuCommands.SEEK_BACK_LONG -> seekBy(-60000L)
            VrMenuCommands.SEEK_FORWARD_LONG -> seekBy(60000L)
            VrMenuCommands.IPD -> settings = settings.stepIpd(step)
            VrMenuCommands.SCREEN_SIZE -> settings = settings.copy(
                screenFovDegrees = (settings.screenFovDegrees + step * 5).coerceIn(40, 110))
            VrMenuCommands.SCREEN_FLIP -> settings = settings.copy(
                screenFlip = (settings.screenFlip + step + 3) % 3)
            VrMenuCommands.FOV -> settings = settings.copy(
                fovDegrees = Optics.clampFov(settings.fovDegrees + step * 4))
            VrMenuCommands.SPEED -> speedIndex = (speedIndex + step + 7) % 7
            // 4.10：模式类参数，A 循环到下一档；改完立即生效，画面在浮层后面同步更新。
            VrMenuCommands.VIEWING_MODE -> cycleVrViewingMode()
            VrMenuCommands.PROJECTION -> cycleVrProjection()
            VrMenuCommands.LAYOUT -> cycleVrLayout()
            VrMenuCommands.SWAP_EYES -> settings = settings.copy(swapEyes = !settings.swapEyes)
            // 4.13：居中 ↔ 吸底两态循环，立刻重排两圆并持久化。
            VrMenuCommands.VERTICAL_ALIGN -> settings = settings.copy(alignBottom = !settings.alignBottom)
        }
        updateSettings()
        pushVrMenuExtras()
    }

    /**
     * 戴镜时只在「双透镜 VR / 3D 双眼 ↔ 大屏模式」之间切。单透镜只有一个
     * viewport、普通视频会跳 FlatVideoView，两者都不适合叠加菜单浮层，
     * 因此仍留在 2D 设置页；4.11 起它们不会再被自动识别选中，参数键也能
     * 从 2D 态一键回到这里的 3D 双眼。
     */
    private fun cycleVrViewingMode() = setViewingMode(if (settings.screenMode) 0 else 3)

    /** 片源投影：沿用 2D 设置页的 choices()，A 循环到下一档并持久化到当前片源。 */
    private fun cycleVrProjection() {
        val choices = VideoProjection.choices(settings.phoneMode)
        val current = VideoProjection.forViewingMode(projectionMode, settings.phoneMode)
        val index = choices.indexOf(current).let { if (it < 0) 0 else it }
        projectionMode = choices[(index + 1) % choices.size]
        // 双鱼眼不支持 mono 布局，与 2D 设置页的同名保护一致。
        if (projectionMode == VideoProjection.STEREO_FISHEYE_180 && layoutMode == 2) layoutMode = -1
        saveProjectionChoice()
    }

    /** 视频布局：自动 / SBS / 上下 / 单眼；双鱼眼时跳过不合法的 mono。 */
    private fun cycleVrLayout() {
        val stereoFisheye = settings.sourceProjection == VideoProjection.STEREO_FISHEYE_180
        val choices = if (stereoFisheye) intArrayOf(-1, 0, 1) else intArrayOf(-1, 0, 1, 2)
        val index = choices.indexOf(layoutMode).let { if (it < 0) 0 else it }
        layoutMode = choices[(index + 1) % choices.size]
        saveProjectionChoice()
    }

    private fun seekBy(deltaMs: Long) {
        val current = player ?: return
        val end = current.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        current.seekTo((current.currentPosition + deltaMs).coerceIn(0L, end))
    }

    /** 把物理按键归约成 VR 菜单语义输入；键位走 [GamepadBindings] 可配置表。 */
    /**
     * S3b 阶段 3：点击「串流主机」→ 切到大屏模式 → 把 VrView 的 Surface 交给
     * StreamSession → HTTPS /launch → MoonBridge.startConnection → MediaCodec
     * 解码帧写回同一个 Surface，SurfaceTexture 自动驱动大屏银幕重绘。
     *
     * 与本地片源路径互斥：source 用 stream:// 哨兵 URI 占位，ensurePlayer() 提前返回，
     * 因此不会创建 ExoPlayer 去解析一个不存在的媒体。
     */
    private fun startStream(host: StreamHost) {
        // filesDir 里没有服务端证书 = 从未配对成功（或证书已损坏）。
        // 阶段 4：此时先转入 APP 内 PIN 配对，成功后会回到这里继续起流。
        val identity = try { StreamIdentity.get(this) } catch (e: Exception) {
            notifyUser(AppText.STREAM_PIN_FAILED_TITLE.text() + ": " + e.message,
                Toast.LENGTH_LONG)
            return
        }
        val paired = try { identity.loadServerCert() != null } catch (e: Exception) { false }
        if (!paired) {
            showPinPairing(host, identity)
            return
        }
        stopStream()
        stopCurrentVideo()
        streamState = AppText.STREAM_STARTING.text(host.name)
        val session = StreamSession(this, host.ip, host.httpsPort, 0, host.codecMask,
            object : StreamSession.Listener {
                override fun onState(message: String) {
                    streamState = message
                    updateStatus()
                }
                override fun onError(message: String) {
                    streamState = message
                    failure = message
                    updateStatus()
                    notifyUser(message, Toast.LENGTH_LONG)
                }
                override fun onVideoSize(width: Int, height: Int) {
                    vrView.vrRenderer.videoWidth = width
                    vrView.vrRenderer.videoHeight = height
                }
                override fun onTerminated(errorCode: Int) {
                    // 连接结束（正常退出 / 失败 / 用户按停止）都回到空闲页，保留错误提示。
                    if (streamSession != null) {
                        stopCurrentVideo()
                        streamState = ""
                        showControls(true)
                    }
                }
                override fun onFramesDecoded(frames: Int) {
                    streamState = AppText.STREAM_FRAMES.text(frames)
                    updateStatus()
                }
            })
        streamSession = session
        source = Uri.parse("stream://${host.ip}")
        sourceName = AppText.STREAM_SOURCE_NAME.text(host.name)
        sourceLoading = false
        failure = null
        manualViewingChoice = true
        setViewingMode(3)
        updateSettings(false)
        updateStatus()
        // 空闲页的 GLSurfaceView 可能还没真正 onResume（vrResumed 初值为 true，
        // 但 GL 线程未必起来）。主动唤醒一次，保证 onSurfaceCreated 会走
        // onOutput 回调；若 Surface 已存在则直接挂上，attachSurface 内部幂等。
        vrView.onResume()
        vrResumed = true
        vrView.vrRenderer.output?.let { session.attachSurface(it.surface) }
    }

    private fun stopStream() {
        val session = streamSession ?: return
        streamSession = null
        session.stop()
        streamState = ""
    }

    /**
     * v0.3：局域网**设备**连接。
     * 发现列表只列设备；点击设备 → 拉 /api/list → 批量写入播放列表 → 刷新播放列表页。
     * 手填 URL 保留作兜底。Media3 默认 DataSource.Factory 按 scheme 分派
     * http(s):// → DefaultHttpDataSource（原生 Range/seek），播放/渲染链路一行不改。
     */
    private fun openNetworkUrl() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var discovery: ServiceDiscovery? = null
        var dialog: SettingsDialog? = null

        // ── 自动发现：附近的片源设备 ──
        panel.addView(label(AppText.NEARBY_SERVICES.text(), 14f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        val discoveryStatus = label(AppText.SCANNING_LAN.text(), 12f).apply { setTextColor(UiStyle.MUTED) }
        panel.addView(discoveryStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(deviceList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // ── 串流主机（S3b 阶段 1）：复用同一批设备的 IP，探测 Sunshine 47989 ──
        panel.addView(View(this).apply { setBackgroundColor(UiStyle.LINE) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        panel.addView(label(AppText.STREAM_HOSTS.text(), 14f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(label(AppText.STREAM_HOSTS_HINT.text(), 11f).apply { setTextColor(UiStyle.MUTED) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val streamStatus = label(AppText.SCANNING_LAN.text(), 12f).apply { setTextColor(UiStyle.MUTED) }
        panel.addView(streamStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val streamList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(streamList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val probedIps = HashSet<String>()
        val streamHosts = LinkedHashMap<String, StreamHost>()
        fun renderStreamHosts() {
            streamList.removeAllViews()
            if (streamHosts.isEmpty()) {
                streamStatus.text = AppText.NO_STREAM_HOST.text()
                return
            }
            streamStatus.text = AppText.STREAM_HOSTS_FOUND.text(streamHosts.size)
            for (host in streamHosts.values) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = UiStyle.ripple(this@MainActivity, UiStyle.SURFACE, 10)
                    isClickable = true
                }
                card.addView(TextView(this).apply {
                    text = host.name
                    setTextColor(UiStyle.ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                card.addView(TextView(this).apply {
                    // 明文 47989 的 PairStatus 恒为 0，授权判定只能走带客户端证书的
                    // HTTPS 47984，因此这里不再显示「已配对 / 未配对」，避免误导。
                    text = "${host.ip}:${host.httpPort} · ${host.codecLabel} · " +
                        AppText.STREAM_TAP_TO_CONNECT.text() +
                        " · " + AppText.STREAM_REPAIR.text() +
                        if (host.busy) " · " + AppText.STREAM_BUSY.text() else ""
                    setTextColor(UiStyle.MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                })
                card.setOnClickListener {
                    discovery?.stop()
                    dialog?.dismiss()
                    startStream(host)
                }
                card.setOnLongClickListener {
                    // Sunshine 重装或 Web UI 清空授权后，旧证书会一直让起流报「尚未授权」。
                    // 长按清掉凭据并重新走一次 PIN 配对，作为唯一逃生口。
                    discovery?.stop()
                    dialog?.dismiss()
                    val id = try { StreamIdentity.get(this@MainActivity) }
                        catch (e: Exception) { null }
                    if (id != null) {
                        id.clearServerCert()
                        showPinPairing(host, id)
                    } else {
                        notifyUser(AppText.STREAM_PIN_FAILED_TITLE.text(), Toast.LENGTH_LONG)
                    }
                    true
                }
                streamList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
        }
        renderStreamHosts()
        fun probeStream(ip: String) {
            if (!probedIps.add(ip)) return
            StreamControl.probe(ip) { host ->
                if (dialog?.isShowing != true) return@probe
                if (host != null) {
                    streamHosts[host.ip] = host
                    renderStreamHosts()
                } else if (streamHosts.isEmpty()) {
                    streamStatus.text = AppText.NO_STREAM_HOST.text()
                }
            }
        }
        fun renderDevices(devices: List<LanService>) {
            deviceList.removeAllViews()
            if (devices.isEmpty()) {
                discoveryStatus.text = AppText.NO_SERVICE_FOUND.text()
                return
            }
            discoveryStatus.text = AppText.SERVICES_FOUND.text(devices.size)
            for (device in devices) {
                probeStream(device.ip)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = UiStyle.ripple(this@MainActivity, UiStyle.SURFACE, 10)
                    isClickable = true
                }
                card.addView(TextView(this).apply {
                    text = device.name
                    setTextColor(UiStyle.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                card.addView(TextView(this).apply {
                    text = "${device.ip}:${device.port}" +
                        if (device.fileCount >= 0) " · " + AppText.DEVICE_VIDEO_COUNT.text(device.fileCount) else ""
                    setTextColor(UiStyle.MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                })
                card.setOnClickListener {
                    discoveryStatus.text = AppText.CONNECTING_DEVICE.text(device.name)
                    discovery?.connect(device) { files ->
                        if (dialog?.isShowing != true) return@connect
                        if (files.isEmpty()) {
                            discoveryStatus.text = AppText.DEVICE_IMPORT_FAILED.text()
                            return@connect
                        }
                        // addAll 用 INSERT OR IGNORE（uri 唯一），重复连接不会堆积重复条目。
                        // 反序插入使 sort_order 递减逻辑最终呈现为服务端给出的顺序。
                        playlist.submit({
                            playlist.addAll(files.asReversed().map { device.urlFor(it) to it })
                        }) { result ->
                            discovery?.stop()
                            dialog?.dismiss()
                            Toast.makeText(this@MainActivity,
                                AppText.IMPORTED_VIDEOS.text(device.name, result.getOrDefault(0)),
                                Toast.LENGTH_SHORT).show()
                            if (::playlistPage.isInitialized && playlistOpen) playlistPage.refresh(false, true)
                            else openPlaylistPage()
                        }
                    }
                }
                deviceList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
        }
        discovery = ServiceDiscovery { devices -> renderDevices(devices) }
        discovery.start()

        // ── 兜底：手动输入地址 ──
        panel.addView(View(this).apply { setBackgroundColor(UiStyle.LINE) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        panel.addView(label(AppText.MANUAL_ADDRESS.text(), 12f).apply { setTextColor(UiStyle.MUTED) })
        panel.addView(label(AppText.NETWORK_ADDRESS_HELP.text(), 11f).apply { setTextColor(UiStyle.MUTED) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(UiStyle.TEXT)
            setHintTextColor(UiStyle.MUTED)
            hint = AppText.NETWORK_ADDRESS_HINT.text()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = UiStyle.glass(this@MainActivity, UiStyle.RAISED, 16)
        }
        panel.addView(input, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val validation = label("", 11f).apply { setTextColor(0xFFFFA9AA.toInt()); visibility = View.GONE }
        panel.addView(validation, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val actions = listOf(
            SettingsDialog.Action(AppText.CANCEL.text(), accepted = { discovery?.stop() }),
            SettingsDialog.Action(AppText.OPEN_NETWORK_ADDRESS.text(), true, validate = {
                val raw = input.text.toString().trim()
                val uri = Uri.parse(raw)
                val valid = (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
                if (valid) {
                    discovery?.stop()
                    openSource(uri)
                    true
                } else {
                    validation.text = AppText.INVALID_NETWORK_ADDRESS.text(); validation.visibility = View.VISIBLE
                    validation.post { validation.requestRectangleOnScreen(android.graphics.Rect(0, 0, validation.width, validation.height), false) }
                    false
                }
            })
        )
        val created = SettingsDialog(this, AppText.ADD_NETWORK_ADDRESS.text(), panel, actions)
        dialog = created
        // showSettingsDialog 会覆盖 setOnDismissListener，因此靠 Action + ServiceDiscovery 内置
        // 25s 超时兜底停止扫描，不占用主线程 / io executor。
        showSettingsDialog(created)
    }

    private fun openPlaylistPage() {
        if (playlistOpen) return
        resetSeekSequence()
        closeVrPlaylist(false)
        resumeAfterPlaylist = shouldResumePlayback()
        pendingPlay = false
        player?.pause()
        tracker.stop()
        savePosition()
        gamepad.clearInput(); playlistInput.clear(); vrView.cancelPendingGestures(); stereoHint.clear()
        playlistOpen = true
        syncBitrateSampling()
        topBar.visibility = View.GONE; bottomBar.visibility = View.GONE
        videoTitle.isSelected = false
        if (!::playlistPage.isInitialized) {
            playlistPage = PlaylistPage(this, playlist, covers, { entry, fromStart ->
                closePlaylistPage(false)
                openSource(Uri.parse(entry.uri), if (fromStart) 0 else entry.position, entry.name)
            }, { openVideo(true) }, { openNetworkUrl() }, { closePlaylistPage(true) }, { id, target, after, ready ->
                playlist.submit({ playlist.moveRelative(id, target, after) }) { result ->
                    if (!closing) ready(result.getOrDefault(false))
                }
            }, { entry, ready ->
                val keepPermission = source?.toString() == entry.uri
                playlist.submit({
                    playlist.remove(entry.id)
                    if (!keepPermission) runCatching {
                        contentResolver.releasePersistableUriPermission(Uri.parse(entry.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }) { result ->
                    if (!closing) {
                        if (result.isSuccess) covers.invalidate(entry, removed = true)
                        ready(result.isSuccess)
                    }
                }
            })
            root.addView(playlistPage, FrameLayout.LayoutParams(-1, -1))
        }
        applyPageInsets(root.rootWindowInsets)
        playlistPage.present()
        syncBackCallback()
    }

    private fun closePlaylistPage(resume: Boolean) {
        if (!playlistOpen) return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(playlistPage.windowToken, 0)
        playlistPage.hidePage()
        playlistOpen = false
        if (active && !ordinaryVideo && (source != null || debugMode)) tracker.start()
        gamepad.clearInput()
        if (resume && resumeAfterPlaylist && active && !debugMode) {
            if (player == null) { pendingPlay = true; ensurePlayer() } else player?.play()
        }
        resumeAfterPlaylist = false
        showControls(true)
        syncBackCallback()
    }

    private fun importVideos(uris: List<Uri>, flags: Int) {
        if (uris.isEmpty()) return
        notifyUser(AppText.ADDING_VIDEOS.text(uris.size))
        io.execute {
            var added = 0
            var temporary = 0
            for (uri in uris) {
                if (closing) break
                runCatching {
                    var persisted = false
                    if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        persisted = runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
                    }
                    if (!persisted) temporary++
                    val name = runCatching {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, importCancellation)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: AppText.LOCAL_VIDEO.text()
                    playlist.add(uri.toString(), name)
                    added++
                }
            }
            handler.post {
                if (!closing) {
                    if (playlistOpen) playlistPage.refresh()
                    notifyUser(AppText.PROCESSED_VIDEOS_DUPLICATE_FILES_ARE_SKIPPED.text(added, uris.size) +
                        if (temporary > 0) AppText.NPERSISTENT_ACCESS_WAS_NOT_GRANTED_FOR.text(temporary) else "", Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun toggleVrPlaylist() {
        if (ordinaryVideo || settings.phoneMode || settingsOpen || playlistOpen) return
        if (playlistInput.open) { closeVrPlaylist(true); return }
        beginControllerUse()
        gamepad.clearInput(); resetSeekSequence(); vrView.cancelPendingGestures(); stereoHint.clear()
        resumeAfterVrPlaylist = shouldResumePlayback()
        pendingPlay = false
        player?.pause()
        savePosition()
        vrPlaylist.settings = settings
        vrPlaylist.show(source?.toString())
        playlistInput.setOpen(true)
        syncBitrateSampling()
        syncBackCallback()
    }

    private fun closeVrPlaylist(resume: Boolean) {
        if (!::playlistInput.isInitialized || !playlistInput.open) return
        playlistInput.setOpen(false)
        syncBitrateSampling()
        vrPlaylist.close()
        gamepad.clearInput(); resetSeekSequence()
        if (resume && resumeAfterVrPlaylist && active && !debugMode) {
            if (player == null) { pendingPlay = true; ensurePlayer() } else player?.play()
        }
        resumeAfterVrPlaylist = false
        if (failure != null) { showControls(true) }
        syncBackCallback()
    }

    private fun openSource(uri: Uri, resumePosition: Long? = null, name: String? = null,
        autoplay: Boolean = true, revealControls: Boolean = true) {
        val exactSessionPosition = restoredLanguagePosition
        restoredLanguagePosition = null
        releasePlayer()
        source = uri
        // VR 菜单态下由菜单独占观看模式：不能让自动检测把银幕切成普通 2D / 单透镜，
        // 否则 Canvas 菜单纹理贴在 VrView 上而视频却跑到 FlatVideoView。
        manualViewingChoice = vrMenuActive
        sourceLoading = true
        // 4.11：换片时先把观看模式归一到 3D 双眼。放在起播前做，避免先起
        // FlatVideoView、再靠 updateVideoFormat 回调重建 player（会闪一下，
        // 而且那段时间内戴镜根本唤不出菜单）。VR 菜单态由上面的
        // manualViewingChoice 保护，保持它自己的大屏银幕。
        if (!manualViewingChoice) enforceDefaultViewingMode()
        videoFormat = null
        metadataProjection = 0
        loadProjectionChoice()
        positionMs = resumePosition?.coerceAtLeast(0) ?: 0
        pendingPlay = autoplay
        failure = null
        decoder = ""
        sourceName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: AppText.LOCAL_VIDEO.text()
        savedCoverProjection = ""
        updateSettings(false)
        vrView.vrRenderer.resetFrame()
        flatVideo.resetTransform(); flatVideo.setVideoSize(0, 0, 1f)
        tracker.recenter()
        val request = ++nameRequest
        val initialName = sourceName
        playlist.submit({ playlist.add(uri.toString(), initialName) }) { result ->
            if (closing || request != nameRequest || source != uri) return@submit
            val entry = result.getOrNull()
            positionMs = exactSessionPosition?.coerceIn(0, entry?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)
                ?: PlaybackPosition.open(resumePosition, entry?.position ?: 0, entry?.duration ?: 0)
            if (name == null && sourceName == initialName && entry != null) sourceName = entry.name
            sourceLoading = false
            updateSettings(false)
            savePosition()
            if (result.isFailure) notifyUser(AppText.COULD_NOT_SAVE_PLAYBACK_HISTORY_CHECK.text(), Toast.LENGTH_LONG)
            if (active) ensurePlayer()
            updateStatus()
        }
        nameQuery?.cancel()
        val cancellation = android.os.CancellationSignal()
        nameQuery = cancellation
        io.execute {
            val displayName = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            handler.post {
                if (!isDestroyed && request == nameRequest && source == uri && displayName != null) {
                    sourceName = displayName
                    enforceDefaultViewingMode()
                    updateSettings(false)
                    savePosition()
                    updateStatus()
                }
            }
        }
        if (active) {
            // 4.11：output == null 只是 GLSurfaceView 还没建好纹理，不该在这里
            // queueEvent 手动模拟 onSurfaceCreated（无 EGL 上下文会 SIGSEGV）。
            // 交给它自己的 onSurfaceCreated → onOutput 回调 → ensurePlayer()。
            // 这里只处理「上一轮渲染真的失败」的主动重建；默认 3D 双眼后打开
            // 片源都会走 VR 渲染，这个分支的正确性比之前更关键。
            if (!ordinaryVideo && renderFailure) {
                renderFailure = false
                vrView.retryOutput()
            } else ensurePlayer()
        }
        showControls(revealControls || failure != null)
        updateStatus()
    }

    private fun ensurePlayer() {
        // 串流模式下 Surface 由 StreamSession 的 MediaCodec 生产，不能创建 ExoPlayer
        // 去解析 stream:// 哨兵 URI。
        if (streamSession != null) return
        if (!active || player != null || (debugMode && !pendingPlay) || sourceLoading) return
        val uri = source ?: return
        val output = vrView.vrRenderer.output
        if (!ordinaryVideo && output == null) return
        failure = null
        try {
            val requestSdr = ordinaryVideo && !hdrEnabled && Build.VERSION.SDK_INT in 31..34
            var expectedPlayer: ExoPlayer? = null
            val created = ExoPlayer.Builder(this,
                BitrateRenderersFactory(this, bitrateMeter, requestSdr) {
                    if (expectedPlayer != null && player === expectedPlayer) hdrUnavailable()
                }.setEnableDecoderFallback(true)).build()
            expectedPlayer = created
            player = created
            // The circular display area must not make the track selector discard 4K source tracks.
            created.trackSelectionParameters = created.trackSelectionParameters.buildUpon()
                .clearVideoSizeConstraints().clearViewportSizeConstraints()
                .setForceHighestSupportedBitrate(true).build()
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            created.setHandleAudioBecomingNoisy(true)
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (player === created) frostedBars?.invalidateContent()
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (player !== created) return
                    // The renderer resets its sample window on seeks; clear the displayed old rate too.
                    detailText.setBitrate(AppText.BITRATE.text())
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if (player !== created) return
                    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    val index = group?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) } }
                    updateVideoFormat(if (group != null && index != null) group.getTrackFormat(index) else null)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== created) return
                    if (playbackState == Player.STATE_READY && positionMs > 0 && created.duration > 0 && positionMs >= created.duration) {
                        positionMs = 0
                        created.seekTo(0)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        boundarySeek.reached(1, SystemClock.elapsedRealtime())
                        seekAcceleration.reset()
                        positionMs = 0; savePosition(); showControls(true)
                    } else if (playbackState == Player.STATE_READY && created.currentPosition == 0L) {
                        boundarySeek.reached(-1, SystemClock.elapsedRealtime())
                    }
                    updateStatus()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== created) return
                    if (isPlaying && debugMode) setDebugMode(false)
                    syncBitrateSampling()
                    // Pausing/buffering must preserve the user's toolbar visibility, including double taps.
                    if (source == null && !debugMode && active) { showControls(true) }
                    updateStatus()
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (player !== created) return
                    vrView.vrRenderer.videoWidth = videoSize.width
                    vrView.vrRenderer.videoHeight = videoSize.height
                    flatVideo.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                    updateVideoFormat(created.videoFormat ?: videoFormat)
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (player !== created) return
                    val failedFormat = (error as? ExoPlaybackException)?.rendererFormat
                    val failedHdrVideo = failedFormat?.sampleMimeType?.startsWith("video/") == true &&
                        (failedFormat.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                            failedFormat.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG ||
                            failedFormat.sampleMimeType == "video/dolby-vision")
                    if (requestSdr && !hdrEnabled && failedHdrVideo && error.errorCode in 4001..4006) {
                        hdrUnavailable()
                        return
                    }
                    val tip = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                            AppText.THE_FILE_HAS_MOVED_OR_ACCESS.text()
                        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                            AppText.THIS_DEVICE_CANNOT_DECODE_THE_VIDEO.text()
                        else -> AppText.TAP_PLAY_TO_RETRY_OR_SELECT.text()
                    }
                    failure = "${tip}\n${error.errorCodeName}"
                    showControls(true)
                    updateStatus()
                }
            })
            created.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) {
                    if (player !== created) return
                    decoder = decoderName
                    updateStatus()
                }
                override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                    if (player !== created) return
                    if (decoder == decoderName) { decoder = ""; updateStatus() }
                }
            })
            if (ordinaryVideo) {
                if (Build.VERSION.SDK_INT >= 35) flatVideo.surfaceView.setDesiredHdrHeadroom(if (hdrEnabled) 0f else 1f)
                created.setVideoSurfaceView(flatVideo.surfaceView)
            } else created.setVideoSurface(output!!.surface)
            created.setPlaybackSpeed(PlaybackTuning.speed(speedIndex))
            created.setMediaItem(MediaItem.fromUri(uri), positionMs)
            created.prepare()
            created.playWhenReady = pendingPlay
            pendingPlay = false
        } catch (error: Exception) {
            failure = AppText.CANNOT_PLAY.text(error.message)
            player?.release()
            player = null
            showControls(true)
            updateStatus()
        }
    }

    private fun togglePlayback() {
        resetSeekSequence()
        // 串流没有本地时间轴，播放键改为「结束串流」。
        if (streamSession != null) { stopCurrentVideo(); return }
        if (source == null) { openVideo(); return }
        if (sourceLoading) { pendingPlay = !pendingPlay; updateStatus(); return }
        if (!ordinaryVideo && renderFailure) {
            releasePlayer()
            renderFailure = false
            failure = null
            pendingPlay = true
            vrView.retryOutput()
            return
        }
        if (player?.playerError != null) {
            releasePlayer()
            pendingPlay = true
            ensurePlayer()
            return
        }
        if (player == null) { pendingPlay = true; ensurePlayer(); return }
        player?.let {
            if (it.playbackState == Player.STATE_ENDED) { it.seekTo(0); it.play() }
            else if (it.playWhenReady) it.pause() else it.play()
        }
    }

    private fun resetSeekSequence() {
        seekAcceleration.reset()
        boundarySeek.cancel(SystemClock.elapsedRealtime())
        adjacentRequest++
        adjacentPending = false
    }

    private fun seekStep(direction: Int, freshPress: Boolean = true, screenDoubleStartedMs: Long = -1) {
        if (debugMode || sourceLoading || adjacentPending || !active || settingsOpen || playlistOpen || playlistInput.open) return
        val current = player ?: return
        if (current.playerError != null || !current.isCurrentMediaItemSeekable) return
        val now = SystemClock.elapsedRealtime()
        when (boundarySeek.seek(direction, current.currentPosition, current.duration,
            current.playbackState == Player.STATE_ENDED, !current.playWhenReady, freshPress, now, screenDoubleStartedMs)) {
            BoundarySeekSequence.SWITCH -> { playAdjacent(direction); return }
            BoundarySeekSequence.WAIT -> { seekAcceleration.reset(); return }
        }
        val deltaMs = seekAcceleration.next(direction, now)
        val target = PlaybackPosition.seek(current.currentPosition, deltaMs, current.duration)
        if (target == 0L) {
            pendingPlay = false
            current.pause()
            boundarySeek.reached(-1, now)
        }
        else if (current.duration > 0 && target >= current.duration) boundarySeek.reached(1, now)
        current.seekTo(target)
        val prefix = AppText.S.text(if (direction < 0) AppText.REWIND.text() else AppText.FORWARD.text(), kotlin.math.abs(deltaMs) / 1000)
        stereoHint.showValue("${prefix}${time(target)} / ${if (current.duration > 0) time(current.duration) else "--:--"}")
        updateStatus()
    }

    private fun playAdjacent(direction: Int) {
        val uri = source ?: return
        val expectedPlayer = player ?: return
        val request = ++adjacentRequest
        adjacentPending = true
        playlist.submit({ playlist.adjacent(uri.toString(), direction) }) { result ->
            if (request != adjacentRequest) return@submit
            adjacentPending = false
            if (closing || !active || source != uri || player !== expectedPlayer ||
                settingsOpen || playlistOpen || playlistInput.open) return@submit
            val entry = result.getOrNull()
            if (entry == null) {
                boundarySeek.cancel(SystemClock.elapsedRealtime())
                notifyUser(if (result.isFailure) AppText.COULD_NOT_READ_THE_PLAYLIST_TRY.text() else AppText.THE_PLAYLIST_IS_EMPTY.text())
                return@submit
            }
            vrView.cancelPendingGestures()
            gamepad.clearInput()
            openSource(Uri.parse(entry.uri), 0L, entry.name, revealControls = controlsVisible)
            boundarySeek.suppressUntilIdle(SystemClock.elapsedRealtime())
            stereoHint.showValue("${if (direction > 0) AppText.NEXT.text() else AppText.PREVIOUS.text()} · ${entry.name}")
        }
    }

    private fun changeIpd(steps: Int) {
        if (ordinaryVideo || settings.phoneMode) return
        settings = settings.stepIpd(steps)
        updateSettings()
        stereoHint.showCenters()
        stereoHint.showValue(String.format(Locale.ROOT, AppText.IPD_F_CM.text(), settings.ipdCm))
    }

    private fun recenterNow() {
        if (ordinaryVideo) return
        tracker.recenter()
        notifyUser(AppText.RECENTERED.text())
    }

    private fun toggleGyro() {
        if (ordinaryVideo) return
        tracker.setGyroEnabled(!tracker.gyroEnabled)
        prefs.edit().putBoolean("gyro_enabled", tracker.gyroEnabled).apply()
        notifyUser(if (tracker.gyroEnabled) AppText.GYROSCOPE_CONTROL_ON.text() else AppText.GYROSCOPE_CONTROL_OFF_USE_THE_LEFT.text())
        updateStatus()
    }

    private fun onControllerAction(action: GamepadState.Action, freshPress: Boolean = true) {
        if (!active || !hasWindowFocus() || ordinaryVideo) return
        beginControllerUse()
        if (action != GamepadState.Action.SEEK_BACK && action != GamepadState.Action.SEEK_FORWARD) resetSeekSequence()
        when (action) {
            GamepadState.Action.SEEK_BACK -> seekStep(-1, freshPress)
            GamepadState.Action.SEEK_FORWARD -> seekStep(1, freshPress)
            GamepadState.Action.PLAY_PAUSE -> togglePlayback()
            GamepadState.Action.RECENTER -> recenterNow()
            GamepadState.Action.TOGGLE_GYRO -> toggleGyro()
            GamepadState.Action.IPD_DOWN, GamepadState.Action.IPD_UP -> {
                changeIpd(if (action == GamepadState.Action.IPD_DOWN) -1 else 1)
            }
            GamepadState.Action.SPEED_DOWN, GamepadState.Action.SPEED_UP -> {
                changeSpeed(if (action == GamepadState.Action.SPEED_DOWN) -1 else 1)
            }
            GamepadState.Action.SPEED_RESET -> {
                speedIndex = PlaybackTuning.DEFAULT_SPEED
                changeSpeed(0)
            }
            GamepadState.Action.VOLUME_DOWN, GamepadState.Action.VOLUME_UP -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (action == GamepadState.Action.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                // Read back the applied stream step after the audio service processes the change.
                handler.removeCallbacks(showVolume)
                handler.postDelayed(showVolume, 100)
            }
            GamepadState.Action.FOV_DOWN, GamepadState.Action.FOV_UP -> {
                changeFov(if (action == GamepadState.Action.FOV_DOWN) -1 else 1)
            }
        }
    }

    private fun beginControllerUse() {
        handler.removeCallbacks(hideNotice)
        noticeView?.visibility = View.GONE
        // Continuous stick input must not restart the hide animation on every frame.
        if (controlsVisible) showControls(false)
    }

    private fun changeSpeed(direction: Int) {
        speedIndex = PlaybackTuning.stepSpeed(speedIndex, direction)
        player?.setPlaybackSpeed(PlaybackTuning.speed(speedIndex))
        stereoHint.showValue(AppText.SPEED_X.text(PlaybackTuning.speed(speedIndex)))
        updateStatus()
    }

    private fun changeFov(direction: Int) {
        if (ordinaryVideo) return
        settings = if (settings.phoneMode) settings.copy(phoneFovDegrees = Optics.stepPhoneFov(settings.phoneFovDegrees, direction))
            else settings.copy(fovDegrees = Optics.stepFov(settings.fovDegrees, direction))
        updateSettings()
        stereoHint.showValue(if (settings.phoneMode) AppText.HORIZONTAL_FOV.text(settings.phoneFovDegrees) else AppText.FOV.text(settings.fovDegrees))
    }

    private val showVolume = Runnable {
        if (active) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            val value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            stereoHint.showValue(AppText.VOLUME.text(value, maximum))
        }
    }

    private fun notifyUser(message: String, duration: Int = Toast.LENGTH_SHORT) {
        handler.removeCallbacks(hideNotice)
        noticeView?.visibility = View.GONE
        val notice = noticeView ?: label("", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = UiStyle.glass(this@MainActivity, UiStyle.MODAL, 16)
            elevation = dp(8).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            root.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(76); leftMargin = dp(24); rightMargin = dp(24)
            })
            noticeView = this
        }
        notice.text = message
        notice.visibility = View.VISIBLE
        notice.bringToFront()
        handler.postDelayed(hideNotice, if (duration == Toast.LENGTH_LONG) 3500L else 2200L)
    }

    private fun adjustFromController(volumeSteps: Int, horizontal: Float, seconds: Float) {
        if (!active || !hasWindowFocus() || ordinaryVideo) return
        beginControllerUse()
        if (volumeSteps != 0) onControllerAction(if (volumeSteps > 0) GamepadState.Action.VOLUME_UP else GamepadState.Action.VOLUME_DOWN)
        if (horizontal != 0f) {
            val current = if (brightness >= 0f) brightness else
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            brightness = PlaybackTuning.adjustBrightness(current, horizontal, seconds)
            window.attributes = window.attributes.apply { screenBrightness = brightness }
            stereoHint.showValue(AppText.BRIGHTNESS.text(Math.round(brightness * 100)))
        }
        handler.removeCallbacks(persistSettings)
        handler.postDelayed(persistSettings, 300)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (startupReady && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val bounds = android.graphics.Rect()
            if (!logoView.getGlobalVisibleRect(bounds) || !bounds.contains(event.rawX.toInt(), event.rawY.toInt())) logoView.cancelPending()
            // A toolbar press, including its empty padding, cannot complete a video double tap.
            if (settingsOpen || listOf(topBar, bottomBar).any {
                it.visibility == View.VISIBLE && it.getGlobalVisibleRect(bounds) &&
                    bounds.contains(event.rawX.toInt(), event.rawY.toInt())
            }) vrView.cancelPendingGestures()
        }
        return super.dispatchTouchEvent(event)
    }

    /** 手柄来源判定：菜单态要把未映射的手柄键也吞掉，系统键（音量/电源）仍放行。 */
    private fun isGamepadKey(event: KeyEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.device?.let { it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK) } == true

    private fun isGamepadMotion(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.device?.let { it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK) } == true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // VR 菜单态：与 2D 首页共用 NavKeyRouter，并**完全旁路播放页输入层**。
        // 旧版只在 ACTION_DOWN 拦截，ACTION_UP 会漏到 GamepadInput —— 于是在 VR 菜单里
        // 按 A 确认会顺带触发「视角回正」、按方向键会顺带触发快退 / FOV。
        // 4.11 再补一层：未映射的手柄键也一律吞掉，不能再交还系统变成媒体控制。
        if (vrMenuActive) {
            val action = NavKeyRouter.resolve(event.keyCode)
            if (action != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    vrMenuSession?.handle(NavKeyRouter.toVrMenuInput(action, event.repeatCount > 0))
                }
                return true // 已识别的导航键：DOWN 执行、UP 只消费
            }
            return if (isGamepadKey(event)) true else super.dispatchKeyEvent(event)
        }
        if (startupReady && active && hasWindowFocus()) {
            // 4.10：播放中按「参数」键 → 呼出观看参数浮层。必须排在
            // PlaylistInput / GamepadInput 之前，否则同一个物理键会被 PLAYER 组
            // 解释成调参，DOWN 执行、UP 只消费，避免抬手事件漏进播放页。
            // 4.11：不再排除 2D 普通视频态 —— 普通视频恰恰最需要一键回到 3D
            // 双眼去改模式；这个分支在下面「吞掉手柄键」的兜底之前，能先接住它。
            if (source != null && !playlistInput.open && !settingsOpen && !playlistOpen) {
                when (NavKeyRouter.resolve(event.keyCode)) {
                    // 4.10：参数键直达观看参数页。
                    NavAction.PARAMS -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openVrParams()
                        return true
                    }
                    // 4.12：菜单键从页面1 呼出完整 VR 菜单（与 Start 的视频列表分开）。
                    NavAction.MENU -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openVrMenu()
                        return true
                    }
                    else -> Unit
                }
            }
            if (ordinaryVideo && isGamepadKey(event)) return true
            if (playlistInput.handleKey(event, !ordinaryVideo && !settingsOpen && !playlistOpen && !settings.phoneMode)) return true
            if (!settingsOpen && !playlistOpen && !playlistInput.open && gamepad.handleKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // 4.11：唤起 VR 菜单后**冻结全部 VR 播放控制**。旧版这里没有菜单态分支，
        // 左摇杆 / 十字键(HAT) 会直连 GamepadInput 变成转视角 / 快进 / FOV，
        // 而菜单只收 KeyEvent —— 于是方向键既操控不了菜单、又被 VR 功能抢走。
        // 现在轴输入独占：十字键与左摇杆翻译成菜单方向，其余轴（右摇杆/扳机）
        // 直接吞掉，绝不漏给 GamepadInput、PlaylistInput 或 Android View 树。
        if (vrMenuActive && isGamepadMotion(event)) {
            handleVrMenuMotion(event)
            return true
        }
        if (startupReady && active && hasWindowFocus()) {
            if (ordinaryVideo && isGamepadMotion(event)) return true
            if (playlistInput.handleMotion(event)) return true
            if (!settingsOpen && !playlistOpen && !playlistInput.open && gamepad.handleMotion(event)) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * 把十字键 HAT / 左摇杆采样交给 [MenuAxisRouter]，边沿动作经 [NavKeyRouter]
     * 转成 VrMenuInput —— 与按键方向走同一条语义链，戴镜内外完全一致。
     */
    private fun handleVrMenuMotion(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            menuAxis.clear(); cancelMenuAxisRepeat(); return
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return
        // 逐个喂历史采样：快速拨动（0→1→0）每个边沿都不丢也不重复。
        for (i in 0 until event.historySize) {
            val action = menuAxis.feed(
                event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_X, i),
                event.getHistoricalAxisValue(MotionEvent.AXIS_Y, i),
                event.getHistoricalEventTime(i))
            if (action != null) dispatchMenuAxis(action)
        }
        val action = menuAxis.feed(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.eventTime)
        if (action != null) dispatchMenuAxis(action)
        scheduleMenuAxisRepeat()
    }

    private fun dispatchMenuAxis(action: NavAction) {
        vrMenuSession?.handle(NavKeyRouter.toVrMenuInput(action, false))
    }

    /** 长按连发：与 KeyEvent 的 repeatCount 手感一致，松开自动停止。 */
    private val menuAxisRepeat = object : Runnable {
        override fun run() {
            menuAxisRepeatScheduled = false
            if (!vrMenuActive) { menuAxis.clear(); return }
            val action = menuAxis.repeat(SystemClock.uptimeMillis())
            if (action != null) dispatchMenuAxis(action)
            scheduleMenuAxisRepeat()
        }
    }

    private fun scheduleMenuAxisRepeat() {
        if (!vrMenuActive || menuAxis.held() == null) { cancelMenuAxisRepeat(); return }
        if (menuAxisRepeatScheduled) return
        menuAxisRepeatScheduled = true
        handler.postDelayed(menuAxisRepeat, MenuAxisRouter.REPEAT_INTERVAL_MS)
    }

    private fun cancelMenuAxisRepeat() {
        if (menuAxisRepeatScheduled) {
            handler.removeCallbacks(menuAxisRepeat)
            menuAxisRepeatScheduled = false
        }
    }

    private fun updateStatus() {
        if (!::detailText.isInitialized) return
        // 500ms ticker：进度与观看参数变化时才重绘菜单纹理（不是每帧）。
        if (vrMenuActive) pushVrMenuExtras()
        syncBitrateSampling()
        idleBackdrop.visibility = if (!vrMenuActive && source == null && !debugMode) View.VISIBLE else View.GONE
        val current = player
        root.keepScreenOn = (vrMenuActive && active) || (active && (debugMode || (failure == null && current?.playWhenReady == true && current.playbackState != Player.STATE_ENDED)))
        if (!controlsVisible || settingsOpen || playlistOpen || playlistInput.open) return
        val title = if (source == null) AppText.CHOOSE_A_VIDEO_TO_PLAY.text() else if (debugMode) AppText.FIELD_OF_VIEW_DEBUG.text() else sourceName
        // Reassigning text on the 500ms ticker restarts Android's marquee; only update on a real change.
        if (videoTitle.text.toString() != title) { videoTitle.text = title; videoTitle.tooltipText = title }
        val titleScrolling = active && source != null && !debugMode
        if (videoTitle.isSelected != titleScrolling) videoTitle.isSelected = titleScrolling
        val speedLabel = "${PlaybackTuning.speed(speedIndex)}x"
        if (speedText.text.toString() != speedLabel) speedText.text = speedLabel
        val inStream = streamSession != null
        val decoderInfo = if (inStream) AppText.STREAM_LIVE.text(streamState)
            else if (decoder.isNotEmpty()) AppText.DECODER.text(decoder)
            else if (source != null) AppText.DECODER_INACTIVE.text() else ""
        val issue = failure ?: if (inStream) "" else if (sourceLoading) AppText.LOADING_PLAYBACK_HISTORY.text()
            else if (current?.playbackState == Player.STATE_BUFFERING) AppText.BUFFERING.text()
            else if (ordinaryVideo) "" else projectionWarning
        val detail = if (debugMode) AppText.PLAYBACK_EXITS_DEBUG_MODE_OR_DOUBLE.text() else listOf(decoderInfo, issue.replace('\n', ' ')).filter { it.isNotEmpty() }.joinToString(" · ")
        detailText.setDetail(detail, source != null && !debugMode)
        detailText.visibility = if (source == null) View.GONE else View.VISIBLE
        val pausable = failure == null && (pendingPlay || (current?.playWhenReady == true && current.playbackState != Player.STATE_ENDED))
        playButton.contentDescription = if (failure != null) AppText.RETRY.text() else if (pausable) AppText.PAUSE.text() else AppText.PLAY.text()
        val nextIcon = if (pausable) "pause" else "play"
        if (playIcon != nextIcon) { playIcon = nextIcon; playButton.setImageDrawable(PlayerIcon(nextIcon)) }
        gyroButton.imageAlpha = if (tracker.gyroEnabled) 255 else 110
        gyroButton.contentDescription = if (tracker.gyroEnabled) AppText.TURN_GYROSCOPE_OFF_B.text() else AppText.TURN_GYROSCOPE_ON_B.text()
        gyroButton.tooltipText = gyroButton.contentDescription
        if (Build.VERSION.SDK_INT >= 30) gyroButton.stateDescription = if (tracker.gyroEnabled) AppText.ON_217.text() else AppText.OFF_218.text()
        if (!draggingSeek) {
            val duration = current?.duration ?: C.TIME_UNSET
            val position = current?.currentPosition ?: positionMs
            seek.isEnabled = !debugMode && duration > 0 && current?.isCurrentMediaItemSeekable == true
            seek.progress = if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
            val timeLabel = "${time(position)} / ${if (duration > 0) time(duration) else "--:--"}"
            if (timeText.text.toString() != timeLabel) timeText.text = timeLabel
        }
    }

    private fun savePosition() {
        if (sourceLoading) return
        player?.let { positionMs = if (it.playbackState == Player.STATE_ENDED) 0 else it.currentPosition.coerceAtLeast(0) }
        source?.let { uri ->
            val duration = player?.duration?.takeIf { it > 0 } ?: 0
            val name = sourceName; val position = positionMs
            playlist.submit({ playlist.save(uri.toString(), name, position, duration) }) { result ->
                if (!closing && result.isFailure) notifyUser(AppText.COULD_NOT_SAVE_PLAYBACK_POSITION_CHECK.text())
            }
        }
        lastSavedMs = SystemClock.elapsedRealtime()
    }

    private fun releasePlayer() {
        resetSeekSequence()
        boundarySeek.reset()
        savePosition()
        val previous = player
        player = null
        syncBitrateSampling()
        if (::detailText.isInitialized) detailText.setBitrate(AppText.BITRATE.text())
        decoder = ""
        previous?.clearVideoSurface()
        previous?.release()
    }

    private fun syncBitrateSampling() {
        val enabled = active && controlsVisible && !settingsOpen && !playlistOpen &&
            (!::playlistInput.isInitialized || !playlistInput.open) && !debugMode &&
            failure == null && player?.isPlaying == true
        if (enabled == bitrateSampling) return
        bitrateSampling = enabled
        bitrateMeter.setEnabled(enabled)
        handler.removeCallbacks(bitrateTicker)
        // Pause/hide stops collection while preserving the last displayed sample.
        if (enabled) handler.postDelayed(bitrateTicker, 1000)
    }

    private fun showControls(requested: Boolean) {
        // VR 菜单态下 2D 控件永久隐藏；否则 openSource / 定时器会把它们再显示出来。
        if (vrMenuActive) return
        val canHide = debugMode || source != null
        if (settingsOpen || playlistOpen || playlistInput.open) return
        val visible = requested || !canHide
        controlsVisible = visible
        if (!visible) frostedBars?.refresh()
        if (!visible) videoTitle.isSelected = false
        for (bar in arrayOf(topBar, bottomBar)) {
            bar.animate().cancel()
            if (visible) {
                if (bar.visibility != View.VISIBLE) bar.alpha = 0f
                bar.visibility = View.VISIBLE
                bar.animate().alpha(1f).setDuration(UiStyle.motionDuration(160)).setInterpolator(UiStyle.easing).start()
            } else {
                bar.animate().alpha(0f).setDuration(UiStyle.motionDuration(140)).withEndAction {
                    if (!controlsVisible) bar.visibility = View.GONE
                }.start()
            }
        }
        syncBitrateSampling()
        if (visible) updateStatus()
    }
    override fun onResume() {
        super.onResume()
        // 放在 startupReady 判断之前：从映射页返回时即使还没起播也要拿到新键位。
        GamepadBindings.reload(this)
        if (!startupReady) return
        active = true
        deviceStatus.setActive(true)
        configureWindow()
        if (!playlistOpen && !ordinaryVideo && (source != null || debugMode)) tracker.start()
        // 4.11：菜单态不重新激活播放页手柄控制（灭屏再亮时会走这里），
        // 否则刚冻结的转视角 / 快进又会被摇杆唤醒。
        if (!ordinaryVideo && !vrMenuActive) gamepad.start()
        syncBackCallback()
        renderFailure = false
        syncVideoMode()
        // The GL output may already exist if its ready callback arrived before Activity.onResume.
        ensurePlayer()
        if (!debugMode && source == null) { showControls(true) }
        frostedBars?.start()
        handler.post(ticker)
    }

    override fun onPause() {
        if (!startupReady) { super.onPause(); return }
        // 串流不适合后台运行：停掉 native 连接并清掉 stream:// 哨兵，
        // 否则 onResume 的 ensurePlayer() 会拿它当本地媒体去解析。
        if (streamSession != null) stopCurrentVideo()
        active = false
        deviceStatus.setActive(false)
        frostedBars?.stop()
        syncBitrateSampling()
        videoTitle.isSelected = false
        closeVrPlaylist(false)
        resumeAfterPlaylist = false
        playlistInput.clear()
        root.keepScreenOn = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideNotice)
        noticeView?.visibility = View.GONE
        pendingPlay = false
        tracker.stop()
        gamepad.stop()
        cancelMenuAxisRepeat()
        vrView.cancelPendingGestures()
        logoView.cancelPending()
        draggingSeek = false
        resetSeekSequence()
        stereoHint.clear()
        handler.removeCallbacks(showVolume)
        saveSettings()
        releasePlayer()
        vrView.queueEvent { vrView.vrRenderer.releaseOnGlThread() }
        if (vrResumed) { vrView.onPause(); vrResumed = false }
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::covers.isInitialized) covers.trimMemory()
    }

    override fun onDestroy() {
        closing = true
        vrMenuActive = false
        menuAxis.clear()
        cancelMenuAxisRepeat()
        vrMenuSession = null
        vrMenuRenderer = null
        if (::vrView.isInitialized) vrView.vrRenderer.menuMode = 0
        stopStream()
        pairingGeneration++
        pairingDialog?.dismiss(); pairingDialog = null
        if (::deviceStatus.isInitialized) deviceStatus.setActive(false)
        frostedBars?.close(); frostedBars = null
        confirmationDialog?.dismiss(); confirmationDialog = null
        navigationBack?.setEnabled(false)
        nameQuery?.cancel()
        importCancellation.cancel()
        if (::playlistInput.isInitialized) playlistInput.clear()
        if (::vrPlaylist.isInitialized) vrPlaylist.close(immediate = true)
        if (::playlistPage.isInitialized) playlistPage.release()
        val finishStorage = {
            io.execute { if (::playlist.isInitialized) playlist.closeWhenIdle() }
            io.shutdown()
        }
        if (::covers.isInitialized) covers.close(finishStorage) else finishStorage()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (startupReady) {
            savePosition()
            outState.putString("session_uri", source?.toString())
            outState.putString("session_name", sourceName)
            outState.putLong("session_position", if (languageChanging) languagePositionMs else positionMs)
            outState.putBoolean("session_loading", sourceLoading)
            outState.putInt("session_speed", speedIndex)
            outState.putBoolean("playlist_open", playlistOpen)
            outState.putBoolean("settings_open", settingsOpen)
            outState.putBoolean("language_resume", languageChanging && resumeAfterLanguageChange)
            outState.putBoolean("language_changed", languageChanging)
            outState.putBoolean("session_manual_viewing", manualViewingChoice)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val orientationChanged = resources.configuration.orientation != newConfig.orientation
        super.onConfigurationChanged(newConfig)
        if (startupReady) {
            configureWindow()
            (confirmationDialog as? SettingsDialog)?.fitWindow()
            if (orientationChanged) tracker.recenter()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && startupReady) configureWindow()
        if (!hasFocus && startupReady) {
            vrView.cancelPendingGestures()
            logoView.cancelPending()
            gamepad.clearInput(); resetSeekSequence(); closeVrPlaylist(false); playlistInput.clear()
        }
    }

    private fun time(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    companion object { private const val OPEN_VIDEO = 10; private const val ADD_VIDEOS = 11 }
}


