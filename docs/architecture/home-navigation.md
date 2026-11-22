# ADR-001：Home / Player 两级导航与可 VR 化的契约层

- 状态：已实施（H0 起）
- 分支：`feature/ui-refine`
- 前置基线：`v0.4-streaming-s3b-4`

## 1. 背景

v0.4 及之前，`MainActivity` 是唯一 LAUNCHER，`onCreate` 直接铺好 VrView / 顶栏 / 底栏；
设置页、播放列表页、连接设备对话框都是**盖在播放器上的全屏覆盖层**。去选片会被当成
「播放中途暂停」（`player.pause()` / `tracker.stop()` / `savePosition()`），
`playlist_open`、`settings_open` 与播放状态一起塞在同一个 Activity 的 savedInstanceState 里。

目标形态（对标 SkyBox）：**库 / 配置 / 设备是首页，选定之后才进入播放页，退出播放回到首页**；
播放页内可以不退出直接换片。下一个阶段是**把这些菜单 VR 化并适配手柄**。

因此本 ADR 的核心不是「菜单怎么摆」，而是：**这轮的实现方式不能把下一阶段堵死**。

## 2. 决策

### 2.1 两级 Activity

| 角色 | 类 | 职责 |
|---|---|---|
| 首页 | `HomeActivity`（LAUNCHER） | 片源库 / 连接中心 / 全局设置，产出 `PlayerRoute` |
| 播放页 | `MainActivity`（去掉 LAUNCHER，保留 `exported=true`） | 播放、观看参数、串流会话、头显内换片 |

不重写 2700 行播放逻辑，不搬 GL/Surface/传感器/StreamSession，`RegressionTests.kt`
里指向 `MainActivity` 的 27 个反射字段与 11 个方法签名保持不变。
`PlayerLauncher.goHome()` 保证播放页 `finish()` 一定回到首页。

### 2.2 契约层（纯 Kotlin，零 `android.view` 依赖）

| 文件 | 内容 |
|---|---|
| `PlayerRoute.kt` | 进入播放页的唯一意图：`Video` / `Stream`。纯 data，不含 Context / Bundle |
| `HomeContract.kt` | `HomeTab` / `HomeItem` / `HomeState` / `HomeInput` / `HomeEffect` / `HomeController` / `HomeHost` / `HomeRenderer` / `HomePlatform` |
| `PlayerLauncher.kt` | Route ↔ Intent 的**唯一**转换点；`goHome()` |

分层关系：

```
输入（触摸 / D-pad / 手柄 / 未来凝视）
        │ 统一映射为 HomeInput.Navigate / Confirm / Back
        ▼
HomeController  ── 纯 Kotlin 状态机，只做 tab / focus / items 转换
        │ 输出 HomeEffect
        ▼
HomeHost ──┬── HomeRenderer.render(state)   ← 2D View / 未来 GL 双眼场景
           └── HomePlatform.launch/command  ← Activity / 未来 VR 宿主
```

**关键点**：`HomeItem` 只有 `id / title / subtitle / badge / intent`，没有 View、Drawable、
ClickListener。VR 化时新增 `VrHomeRenderer : HomeRenderer`，把同一份 `state.items` 画进
`VrView` 的左右眼 viewport；`HomeController`、`HomeHost`、`PlayerRoute` 一行不改。

### 2.3 输入语义

`HomeDirection.LEFT/RIGHT` = 切一级板块，`UP/DOWN` = 板块内移动焦点，`CONFIRM` 触发
`HomeItem.intent`。现有资产可以直接接进来，不需要重写：

| 现有类 | 现有语义 | 映射到 |
|---|---|---|
| `PlaylistInput.kt` | `onMenu()` / `onNavigate(LEFT/RIGHT/CONFIRM)` | `HomeInput.Back` / `Navigate` / `Confirm` |
| `GamepadInput.kt` + `GamepadState.java` | D-pad / 摇杆 / hat | `HomeInput.Navigate` |
| `VrPlaylistView.kt` | `move()` / `selection()`，已双眼绘制 | VR 化后的库列表 |

H0 只把接口留好并在 2D 首页接入 D-pad，**不提前改播放页的手柄映射**（播放页行为不变，
避免破坏 `RegressionTests` 里 `PlaylistInput` 相关断言）。

## 3. 明确不采用的方式（以及为什么）

| 方案 | 否决理由 |
|---|---|
| Fragment / Jetpack Navigation | 导航图与 View 树绑定，无法绘制进 GL 双 viewport；VR 化要推倒重写 |
| Jetpack Compose | 引入新 UI 栈；Compose 的 `AndroidComposeView` 同样进不了自定义 GLSurfaceView 的左右眼渲染，且会让现有 2700 行 View 与新栈长期并存 |
| 单 Activity + 两个根容器切换 | 生命周期、返回栈、savedInstanceState 全靠手写状态机，且播放态与浏览态互相污染；VR 化时无法只替换渲染层 |
| 把 Home 做成 Dialog / 覆盖层 | 就是旧架构，只会把「播放器当首页」的问题再复刻一遍 |
| 在 `HomeItem` 里放 View / lambda | 数据与渲染耦合，GL renderer 拿不到可画的内容 |
| 把业务逻辑写进 `OnClickListener` | 手柄/凝视输入无法复用，下阶段必须重写 |
| 新增 ViewModel / DI / Room 等框架 | 当前数据层（`PlaylistStore` / `CoverRepository` / `ServiceDiscovery` / `StreamControl` / `StreamIdentity` / `StreamPairing`）全是 Context-only 的纯数据类，无引入必要；多一个框架就多一层 VR 化包袱 |

一句话判据：**任何要求「UI 必须挂在 Android View 树上」的技术选型，这轮都不选。**

## 4. 已知边界与不变量

- `LIBRARY` 板块当前用富交互的 `PlaylistPage`（CursorAdapter、封面、拖拽排序），它没有走
  `HomeItem` 列表；VR 化时这一格换成 `VrPlaylistView`，`HomeController` 的 tab/focus 逻辑不变。
  这是**有意为之**：不为抽象而重写一个已经真机验证过的复杂列表。
- 观看参数（IPD / 投影 / 畸变 / 亮度 / 银幕大小与方向 / 倍速）**留在播放页设置**，因为要边看边调；
  Home 只放全局配置（语言、片源、连接、串流、关于）。
- `StreamSession` / `MoonBridge` / `MediaCodec` 只在 `MainActivity`，Home 不持有 GL 上下文、
  Surface、传感器；两个 Activity 各自释放，不会出现双 GL 上下文争用。
- 语言切换仍走 `AppLanguage.select()` + `recreate()`。播放页那套「保存 7 个 bundle key 再恢复播放」
  只属于播放页；Home 无播放态，recreate 代价很低。
- `MainActivity` 保留 `openPlaylistPage()` / `openSettingsPage()` / `openNetworkUrl()` 等覆盖层入口，
  作为兼容路径与播放页内快捷操作，Home 不依赖它们。
- `RegressionTests.kt` 直接 `startActivitySync(Intent(context, MainActivity::class.java))` 启动播放页，
  因此 `MainActivity` 必须保持 `exported=true`，且不能要求必须带 Route extra。

## 5. 后续阶段路线

| 期别 | 内容 | 复用本 ADR 的哪一层 |
|---|---|---|
| H0 | Home 骨架 + LAUNCHER 迁移 + 契约层 | 全部 |
| H1 | 播放列表 / 连接中心进 Home，选中即进 Player | `PlayerRoute` / `HomeEffect.Launch` |
| H2 | 全局配置进 Home，观看参数留 Player | `HomeItem` / `HomeEffect.Command` |
| H3 | 播放页内换片（复用 `VrPlaylistView`）+ 返回保留 Home 状态 | `HomeState` 序列化 |
| **H4（下阶段）** | **菜单 VR 化 + 手柄适配** | 新增 `VrHomeRenderer : HomeRenderer`；输入映射 `GamepadInput`/`PlaylistInput` → `HomeInput`；Controller / Host / Route 不动 |

H4 的落地顺序建议：先做「头显内快捷面板」（复用 `VrPlaylistView.drawEye` 的双眼绘制），
再做完整 VR Home；不要一上来把 `HomeActivity` 的 Android View 树整体搬进 GL。

## 6. 验证要求

- 契约层用 instrumentation 纯逻辑测试覆盖：Route 往返、tab 切换、focus 环绕、Back 语义、
  effect 分发；不需要亮屏。
- UI 套件（`RegressionTests`）仍要求手机解锁亮屏，否则 `startActivitySync` 会挂起
  （见 README「真机 instrumentation 回归」）。
- 串流 19 项回归不因新增 Activity 而变化。
