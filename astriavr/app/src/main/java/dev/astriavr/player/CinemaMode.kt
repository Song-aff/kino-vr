package dev.astriavr.player

/**
 * 影院模式配置（L0 骨架，尚未接入渲染）。
 *
 * 设计约束：
 * 1. 本文件不修改 VrView / VrRenderer / MainActivity 的任何现有代码路径。
 *    默认 enabled = false，打包后行为与 v0.3 基线完全一致。
 * 2. 视点固定在中间座位，只保留头部姿态（现有 HeadTracker 无位置追踪），
 *    不做镜头位移或选座飞行动画，避免 VR 眩晕。
 * 3. 银幕只贴 2D mono 平面片源；VR180/360/SBS 片源不适用。准入判定在接入
 *    渲染时再对准 VideoProjection.resolve() 之后的实际参数，此处先不实现。
 */
data class CinemaMode(
    val enabled: Boolean = false,
    val companionSlots: Int = 0,
    val ambientLevel: Float = 0.12f,
    val screenDistanceMeters: Float = 8f,
    val screenWidthMeters: Float = 12f,
) {
    init {
        require(companionSlots in 0..MAX_COMPANION_SLOTS) {
            "companionSlots must be 0..$MAX_COMPANION_SLOTS"
        }
        require(ambientLevel in 0f..1f) { "ambientLevel must be 0..1" }
        require(screenDistanceMeters > 0f && screenWidthMeters > 0f) {
            "screen size and distance must be positive"
        }
    }

    /** 中间座位固定；陪看模型只能落在左右邻座。 */
    enum class Seat(val offsetMeters: Float) {
        LEFT_2(-1.6f), LEFT_1(-0.8f), RIGHT_1(0.8f), RIGHT_2(1.6f)
    }

    /** 按勾选数量取邻座：先左一，再右一，然后左侧第二、右侧第二。 */
    fun activeSeats(): List<Seat> = when (companionSlots) {
        0 -> emptyList()
        1 -> listOf(Seat.LEFT_1)
        2 -> listOf(Seat.LEFT_1, Seat.RIGHT_1)
        3 -> listOf(Seat.LEFT_2, Seat.LEFT_1, Seat.RIGHT_1)
        else -> listOf(Seat.LEFT_2, Seat.LEFT_1, Seat.RIGHT_1, Seat.RIGHT_2)
    }

    companion object {
        const val MAX_COMPANION_SLOTS = 4
        val DEFAULT = CinemaMode()
    }
}
