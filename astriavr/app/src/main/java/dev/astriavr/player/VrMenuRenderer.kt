package dev.astriavr.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * H4/S4a：把 [HomeState] 用 Canvas 画进一张固定 1024x512 的 Bitmap，作为第二张
 * GL_TEXTURE_2D 贴到 `VrView` 大屏分支的 hit-plane 上。
 *
 * 这是 [HomeRenderer] 的 VR 实现 —— 与 2D 首页的 View 渲染器实现同一个接口，
 * `HomeController` 的 tab / focus / 确认逻辑一行都不用改。同一份 Bitmap 会在
 * 左右眼 viewport 里各采样一次，靠双眼融合成一个有深度的 world-locked 虚像。
 *
 * 约束（ADR-001 H4）：
 *  - 纹理至少 1024x512，头显里 1px 描边会糊，所以焦点态一律橙实心底 + 近黑字；
 *  - 只有状态变化才重绘并置脏，绝不每帧上传；
 *  - 菜单 UV 由 shader 的 planeUv 独立提供，`uScreenFlip` 不参与，避免方向 bug 传染。
 */
class VrMenuRenderer : HomeRenderer {

    companion object {
        const val WIDTH = 1024
        const val HEIGHT = 512
        /** 与 VrView shader 里的 0.76 必须保持一致：播放态只叠纹理底部这一截。 */
        const val BAR_TOP = 0.76f
        private const val BG = 0xFF0B0C0E.toInt()
        private const val SURFACE = 0xFF191B1F.toInt()
        private const val RAISED = 0xFF24272C.toInt()
        private const val LINE = 0xFF33363B.toInt()
        private const val TEXT = 0xFFF5F6F8.toInt()
        private const val MUTED = 0xFF8E929A.toInt()
        private const val ACCENT = 0xFFFF7A18.toInt()
        private const val INK = 0xFF111214.toInt()
    }

    /** 复用同一张 Bitmap：状态变化只重画，不重新分配，也不每帧上传。 */
    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val clipPath = Path()
    private val rect = RectF()

    /** 状态或播放信息变化后置脏，由宿主把 [bitmap] 交给 VrRenderer 上传。 */
    var onDirty: (Bitmap) -> Unit = {}
    var mode: VrMenuMode = VrMenuMode.MENU
    var extras = VrMenuExtras()
    /** 4.10：true = 播放中呼出的参数浮层，标题与提示改成「返回播放」口径。 */
    var paramsMode = false
    private var lastState: HomeState? = null

    override fun render(state: HomeState) {
        lastState = state
        redraw(state)
    }

    fun redraw(state: HomeState? = lastState) {
        bitmap.eraseColor(Color.TRANSPARENT)
        when (mode) {
            VrMenuMode.MENU -> state?.let(::drawMenu)
            VrMenuMode.BAR -> state?.let(::drawBar)
        }
        onDirty(bitmap)
    }

    // ── 全屏菜单态 ────────────────────────────────────────────────────────────

    private fun drawMenu(state: HomeState) {
        fill.style = Paint.Style.FILL
        fill.color = BG
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), fill)
        fill.color = ACCENT
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 5f, fill)

        drawBrand(30f, 22f, 38f)
        drawText(if (paramsMode) AppText.VR_MENU_PARAMS_TITLE.text() else AppText.VR_MENU_TITLE.text(),
            226f, 52f, 24f, bold = true, color = TEXT)

        // 顶栏右侧：当前模式与返回提示
        drawText(if (paramsMode) AppText.VR_MENU_PARAMS_BACK_HINT.text() else AppText.VR_MENU_BACK_HINT.text(),
            WIDTH - 30f, 52f, 17f, color = MUTED, align = Paint.Align.RIGHT)

        val tabs = HomeTab.values()
        val tabY = 82f
        val tabH = 42f
        val tabW = 198f
        val tabGap = 10f
        for ((index, tab) in tabs.withIndex()) {
            val left = 30f + index * (tabW + tabGap)
            val selected = tab == state.tab
            rounded(left, tabY, left + tabW, tabY + tabH, 8f, if (selected) ACCENT else SURFACE)
            if (!selected) stroke(left, tabY, left + tabW, tabY + tabH, 8f, LINE)
            drawText(VrMenuLabels.tab(tab), left + tabW / 2f, tabY + 28f, 19f,
                bold = true, color = if (selected) INK else TEXT, align = Paint.Align.CENTER)
        }

        // 左栏：可聚焦条目列表，围绕焦点滚动，最多 6 行。
        val listLeft = 30f
        val listRight = 664f
        val listTop = 142f
        val rowH = 44f
        val rowGap = 6f
        val visibleRows = 6
        val items = state.items
        if (items.isEmpty()) {
            rounded(listLeft, listTop, listRight, listTop + 90f, 10f, SURFACE)
            drawText(AppText.VR_MENU_EMPTY.text(), listLeft + 20f, listTop + 54f, 18f, color = MUTED)
        } else {
            val start = (state.focus - visibleRows / 2)
                .coerceIn(0, (items.size - visibleRows).coerceAtLeast(0))
            for (row in 0 until visibleRows) {
                val index = start + row
                val item = items.getOrNull(index) ?: break
                val top = listTop + row * (rowH + rowGap)
                val bottom = top + rowH
                val focused = index == state.focus
                rounded(listLeft, top, listRight, bottom, 8f, if (focused) ACCENT else SURFACE)
                if (!focused) stroke(listLeft, top, listRight, bottom, 8f, LINE)
                val labelColor = if (focused) INK else TEXT
                val subColor = if (focused) 0xFF3A2410.toInt() else MUTED
                val name = ellipsize(item.title, 400f, 19f)
                drawText(name, listLeft + 18f, top + 29f, 19f, bold = focused, color = labelColor)
                if (item.subtitle.isNotEmpty())
                    drawText(ellipsize(item.subtitle, 190f, 15f), listRight - 16f, top + 29f, 15f,
                        color = subColor, align = Paint.Align.RIGHT)
                if (item.badge.isNotEmpty())
                    drawText(item.badge, listLeft + 18f, top + 29f, 14f, color = subColor)
            }
            // 列表右侧的滚动滑块：按可见窗口占全表的比例，位置跟随窗口起点 start。
            if (items.size > visibleRows) {
                val listH = visibleRows * (rowH + rowGap) - rowGap
                val thumbH = (listH * visibleRows / items.size).coerceAtLeast(24f)
                val maxScroll = (items.size - visibleRows).coerceAtLeast(1)
                val thumbY = listTop + start.toFloat() / maxScroll * (listH - thumbH)
                fill.color = ACCENT
                canvas.drawRect(listRight - 5f, thumbY, listRight - 2f, thumbY + thumbH, fill)
            }
        }

        // 右栏：当前播放信息 + 进度 + 操作提示（SkyBox 式「现在播放」面板）
        val infoLeft = 684f
        val infoRight = WIDTH - 30f
        rounded(infoLeft, listTop, infoRight, 446f, 10f, SURFACE)
        stroke(infoLeft, listTop, infoRight, 446f, 10f, LINE)
        drawText(AppText.VR_MENU_NOW.text(), infoLeft + 20f, listTop + 34f, 16f, bold = true, color = MUTED)
        val now = extras.nowPlaying.ifEmpty { AppText.VR_MENU_NONE.text() }
        drawText(ellipsize(now, infoRight - infoLeft - 40f, 21f), infoLeft + 20f, listTop + 72f, 21f,
            bold = true, color = TEXT)
        drawText(if (extras.playing) AppText.VR_MENU_PLAYING.text() else AppText.VR_MENU_PAUSED.text(),
            infoLeft + 20f, listTop + 102f, 15f, color = if (extras.playing) ACCENT else MUTED)

        // 进度条
        val barLeft = infoLeft + 20f
        val barRight = infoRight - 20f
        val barY = listTop + 126f
        fill.color = LINE
        canvas.drawRect(barLeft, barY, barRight, barY + 8f, fill)
        val progress = if (extras.durationMs > 0)
            (extras.positionMs.toFloat() / extras.durationMs).coerceIn(0f, 1f) else 0f
        fill.color = ACCENT
        canvas.drawRect(barLeft, barY, barLeft + (barRight - barLeft) * progress, barY + 8f, fill)
        drawText("${UiStyle.duration(extras.positionMs)} / ${UiStyle.duration(extras.durationMs)}",
            infoLeft + 20f, listTop + 160f, 15f, color = MUTED)

        // 操作提示（分区，避免一行塞不下）
        // 右栏较窄，提示必须按面板宽度省略，否则会越过卡片右边界被裁掉。
        val hintWidth = infoRight - infoLeft - 40f
        drawText(ellipsize(if (paramsMode) AppText.VR_MENU_PARAMS_HINT.text() else AppText.VR_MENU_HINT.text(),
            hintWidth, 15f), infoLeft + 20f, listTop + 214f, 15f, color = MUTED)
        if (state.tab == HomeTab.SETTINGS)
            drawText(ellipsize(AppText.VR_MENU_ADJUST_HINT.text(), hintWidth, 15f),
                infoLeft + 20f, listTop + 242f, 15f, color = ACCENT)
        if (paramsMode)
            drawText(ellipsize(AppText.VR_MENU_MODE_HINT.text(), hintWidth, 15f),
                infoLeft + 20f, listTop + 270f, 15f, color = MUTED)
        if (extras.status.isNotEmpty())
            drawText(ellipsize(extras.status, infoRight - infoLeft - 40f, 15f),
                infoLeft + 20f, 424f, 15f, color = ACCENT)

        // 底部：左右键切板块的提示
        fill.color = LINE
        canvas.drawRect(30f, 462f, WIDTH - 30f, 463f, fill)
        drawText(ellipsize(VrMenuLabels.tabHint(state.tab), 700f, 16f), 30f, 491f, 16f, color = MUTED)
        drawText("KINO VR · 4.14", WIDTH - 30f, 491f, 15f, color = MUTED, align = Paint.Align.RIGHT)
    }

    // ── 播放态：只有下沿控制条，上方保持透明让视频透出 ─────────────────────

    private fun drawBar(state: HomeState) {
        val top = HEIGHT * BAR_TOP
        fill.style = Paint.Style.FILL
        fill.color = 0xE60B0C0E.toInt()
        canvas.drawRect(0f, top, WIDTH.toFloat(), HEIGHT.toFloat(), fill)
        fill.color = ACCENT
        canvas.drawRect(0f, top, WIDTH.toFloat(), top + 3f, fill)

        val now = extras.nowPlaying.ifEmpty { AppText.VR_MENU_NONE.text() }
        drawText(ellipsize(now, 560f, 22f), 30f, top + 34f, 22f, bold = true, color = TEXT)
        drawText(ellipsize(AppText.VR_MENU_BAR_HINT.text(), 380f, 16f), WIDTH - 30f, top + 34f, 16f,
            color = MUTED, align = Paint.Align.RIGHT)

        val barLeft = 30f
        val barRight = WIDTH - 190f
        val barY = top + 60f
        fill.color = LINE
        canvas.drawRect(barLeft, barY, barRight, barY + 10f, fill)
        val progress = if (extras.durationMs > 0)
            (extras.positionMs.toFloat() / extras.durationMs).coerceIn(0f, 1f) else 0f
        val played = barLeft + (barRight - barLeft) * progress
        fill.color = ACCENT
        canvas.drawRect(barLeft, barY, played, barY + 10f, fill)
        fill.color = TEXT
        canvas.drawCircle(played, barY + 5f, 8f, fill)
        drawText("${UiStyle.duration(extras.positionMs)} / ${UiStyle.duration(extras.durationMs)}",
            WIDTH - 30f, top + 70f, 18f, color = TEXT, align = Paint.Align.RIGHT)

        drawText(if (extras.playing) AppText.VR_MENU_PLAYING.text() else AppText.VR_MENU_PAUSED.text(),
            30f, top + 104f, 16f, color = if (extras.playing) ACCENT else MUTED)
        drawText(VrMenuLabels.tab(state.tab), WIDTH / 2f, top + 104f, 16f,
            color = MUTED, align = Paint.Align.CENTER)
    }

    // ── 绘制原语 ─────────────────────────────────────────────────────────────

    private fun rounded(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        rect.set(l, t, r, b)
        fill.style = Paint.Style.FILL
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    private fun stroke(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        rect.set(l, t, r, b)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 1.5f
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.style = Paint.Style.FILL
    }

    private fun drawBrand(x: Float, y: Float, h: Float) {
        val leftW = 96f
        val rightW = 62f
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(RectF(x, y, x + leftW + rightW, y + h), 7f, 7f, Path.Direction.CW)
        canvas.clipPath(clipPath)
        fill.style = Paint.Style.FILL
        fill.color = RAISED
        canvas.drawRect(x, y, x + leftW, y + h, fill)
        fill.color = ACCENT
        canvas.drawRect(x + leftW, y, x + leftW + rightW, y + h, fill)
        canvas.restore()
        drawText("KINO", x + leftW / 2f, y + h * 0.68f, 21f, bold = true,
            color = TEXT, align = Paint.Align.CENTER)
        drawText("VR", x + leftW + rightW / 2f, y + h * 0.70f, 22f, bold = true,
            color = INK, align = Paint.Align.CENTER)
    }

    private fun drawText(value: String, x: Float, y: Float, size: Float,
                         bold: Boolean = false, color: Int = TEXT,
                         align: Paint.Align = Paint.Align.LEFT) {
        val paint = if (bold) this.bold else text
        paint.color = color
        paint.textSize = size
        paint.textAlign = align
        canvas.drawText(value, x, y, paint)
    }

    private fun ellipsize(value: String, maxWidth: Float, size: Float): String {
        val paint = text.apply { textSize = size }
        if (paint.measureText(value) <= maxWidth) return value
        var cut = value.length
        while (cut > 1 && paint.measureText(value.substring(0, cut) + "…") > maxWidth) cut--
        return value.substring(0, cut) + "…"
    }
}

/** VR 菜单的两个显示态：单平面切换，不引入第二块空间面板。 */
enum class VrMenuMode { MENU, BAR }

/** 播放信息与观看参数当前值，由播放页喂给渲染器；纯数据，方便单测。 */
data class VrMenuExtras(
    val nowPlaying: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val values: Map<String, String> = emptyMap(),
    val status: String = ""
)

/** 三 Tab 在 VR 里的中文标签与提示。HomeTab 枚举保持不动，只换显示名。 */
object VrMenuLabels {
    fun tab(tab: HomeTab) = when (tab) {
        HomeTab.LIBRARY -> AppText.VR_MENU_LIBRARY.text()
        HomeTab.CONNECT -> AppText.VR_MENU_PLAYBACK.text()
        HomeTab.SETTINGS -> AppText.VR_MENU_VIEWING.text()
    }
    fun tabHint(tab: HomeTab) = when (tab) {
        HomeTab.LIBRARY -> AppText.VR_MENU_HINT.text()
        HomeTab.CONNECT -> AppText.VR_MENU_BAR_HINT.text()
        HomeTab.SETTINGS -> AppText.VR_MENU_ADJUST_HINT.text()
    }
}
