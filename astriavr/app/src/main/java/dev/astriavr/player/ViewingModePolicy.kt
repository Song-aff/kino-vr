package dev.astriavr.player

/**
 * 4.11：打开片源时的**观看模式归一策略**（纯 Kotlin，零 Android 依赖，可单测）。
 *
 * 用户的用法只有一种：戴头显看 3D 双眼。旧版本却让「文件名/元数据里没找到
 * VR 标记」等价于「普通视频」，于是自动 changeViewingMode(2) 跳到
 * FlatVideoView；一旦切过去，VrView / Canvas 菜单纹理 / 4.10 的参数浮层
 * 全部不在渲染树里，戴镜后按任何手柄键都唤不出菜单，只能把手机从盒子里
 * 拿出来，回 2D 设置页把模式切回来 —— 这就是「得拿下来切换」的根因。
 *
 * 现在自动识别**完全不参与观看模式决策**：
 *  - 默认恒为 0 = 双透镜 VR / 3D 双眼；
 *  - 2D 普通视频、大屏银幕、单透镜只要不是用户本次手动选的，一律归一回 0；
 *  - 大屏（VR 影院、串流、参数页 A 切换）、单透镜、普通视频仍是 2D 设置页
 *    或参数页里的显式选项，由 manualViewingChoice 保护，不会被这里覆盖；
 *  - 片源的投影/布局解析（VideoProjection.resolve）保留不变，识别不准时
 *    戴镜按「参数」键改即可。
 *
 * @return 需要归一到的 viewing mode；null 表示已经是默认 3D 双眼，保持不动。
 */
object ViewingModePolicy {
    const val DUAL_LENS_VR = 0

    fun decide(ordinaryVideo: Boolean, screenMode: Boolean, phoneMode: Boolean): Int? =
        if (ordinaryVideo || screenMode || phoneMode) DUAL_LENS_VR else null
}
