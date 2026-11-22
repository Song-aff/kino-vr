# 品牌决策：KINO VR（2026-11-15）

> 状态：**已采纳并落地**（4.6 起 APK label / 图标 / 主题全部生效）。
> 本文只记录命名依据，防止后续迭代在无上下文时把名字改回功能描述型。
> 关联提交：`f95e158`（4.6 品牌）、`38d6cf8`（4.7）、`17643a5`（4.8）。

## 1. 约束（用户给定）

1. UI 重构后是**完全不同的产品**，不得沿用旧名 `AstriaVR` / `VR 播放器` / `手机 VR 播放器`。
2. 主题改**黑橙拼色**。
3. LOGO 要类似 ProHub 那种**字母分块拼色**，不是图形徽章。
4. 需要能复用在：顶栏 wordmark、启动图标、播放页角标、VR 菜单纹理、APK label。

最终选项：**克制的橙 + `KINO VR`**（`KIN` 深色 / `O` 与 `VR` 橙，`|` 分隔）。

## 2. 词义与联想

- `Kino` 在德语 / 俄语 / 北欧语里即「电影院」，词根为希腊语 *kinēin*（运动）→ *Kinematograph*。产品定位是「私人放映」：VR 视频、影院大屏、串流当投影，比 `VR Player / VR Box` 这类功能描述贴切。
- 顺带与 VR 的 motion / 头部追踪呼应，但不做成"运动"主题，避免喧宾夺主。
- 电影史里 `Kino` 常指艺术影院与独立放映（Kino International、Kino Lorber 一类），有质感但不生僻。

## 3. 为什么这四个字母适合拼色

| 观察 | 结果 |
|---|---|
| `K / I / N` 全是直线、硬边 | 与既定像素字体（Press Start 2P）、硬边阴影、0 圆角一致 |
| `O` 是唯一的闭合圆形 | 天然能单独做成橙色实心块 —— `KINO\|VR` 的拼色是字母结构给的，不是硬凑 |
| 4 字母 / 两音节 / 全大写近似对称 | 头显里距离远、视角歪、纹理只有 1024×512 时仍一眼可读 |
| 短词 + 高对比色块 | 顶栏、启动图标、播放页角标、VR 菜单纹理可共用同一套画法 |

## 4. 刻意排除的方向

- 带 `VR / Player / Box` 后缀的直白名：像通用工具，也容易被读成 SkyBox 的仿品；
- 星空 / 宇宙 / 星座系：旧名 Astria 的影子，与"完全重构"相悖；
- 纯造词：戴镜时念不出来、不好搜、拼色也没有结构可依。

顺带的好处：中文无生僻字，`KINO VR` 组合短且可区分，无旧品牌包袱。

## 5. 落地位置（改动清单）

| 文件 | 内容 |
|---|---|
| `UiStyle.kt` | token 改 KINO：背景 `0xFF0B0C0E`、卡片 `#191B1F`、强调 `#FF7A18`、危险 `#FF4D4F`、`BRAND_INK #111214`；新增 `brand()` 拼色 wordmark、`selected()` 橙实心底 |
| `StopLogoView.kt` | `ImageView(astria_wordmark)` → `FrameLayout + UiStyle.brand()`，保留双击停止 |
| `res/drawable*`、`mipmap-anydpi/ic_launcher.xml` | 删旧 LOGO，新增 `kino_icon_{background,foreground,monochrome,full}.xml` |
| `AppText.java`、`VrApplication.kt` | 清除 `AstriaVR / VR 播放器`（关于、崩溃页、剪贴板 label、双击提示），版本头同步 KINO VR |
| `HomeActivity.kt` / `HomeNavigationTest.kt` | 顶栏改拼色 wordmark，测试断言随之更新 |
| `VrMenuRenderer.kt` | VR 菜单纹理绘制 `KINO\|VR` 拼色标 |
| `ui-prototype.html` | 离线原型同步黑橙 / KINO |
| `README.md` | 记录 4.6 品牌变更与内部标识保留清单 |

## 6. 刻意不改的内部标识

**这些不是品牌，改了会丢用户数据或破坏既有配对，禁止借"重构"顺手改**：

- 包名 `dev.astriavr.player`、目录 `astriavr`；
- SharedPreferences `astriavr-settings`；
- `PlayerLauncher` 的 `astriavr.route.*` extras key；
- Sunshine 配对证书 `stream-server.crt`；
- PC 端 `VR片源服务.exe` 文件名（影响已建防火墙规则与快捷方式）。

## 7. 可替换性

品牌目前只存在于代码、资源与 APK label，**没有对外发布、没有商标与域名绑定**。若后续想换成更工业 / 设备感的命名，`brand()` / 图标资源 / `AppText` / `VrMenuRenderer` 四处是完整替换面，替换成本约等于 4.6 的规模；但需同步提升 `versionCode` 并出新文件名 APK，避免覆盖已有基线。
