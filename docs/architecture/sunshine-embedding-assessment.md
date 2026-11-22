# Sunshine 嵌入 PC 客户端：可行性复核（2026-11-15）

> 分支：`feature/ui-refine` ｜ 触发：用户要求重新评估"把 Sunshine 嵌入 PC 客户端"
> 关系：复核 `README.md`「Sunshine 集成到 PC 管理端的边界评估」中 **"只做 L1，不做 L3"** 的旧结论。
> 状态：**2026-11-16 用户确认「做 L2」，阶段 A 已实施并验证**，落地记录见第 7 节。
> 评估阶段的本机探测均为只读；实施阶段也未改动本机已装 Sunshine 的配置与凭据。
> 官方资料为非法律意见，涉及 GPL 合规时请自行确认。

---

## 0. 结论速览

| 层次 | 具体形态 | 可行性 | 主要障碍 | 建议 |
|---|---|---|---|---|
| L1 状态联动 | 启停检测、会话状态、自动静音 `/cancel` | **已实现** | 无 | 保持，补齐会话态展示 |
| L2 能力代理 | 管理页代发 `/api/pin` 自动配对、应用/客户端列表、只读配置 | **可行，中低难度** | Web UI 凭据、CSRF、Rust 端无 HTTPS 客户端 | **推荐做**（阶段 A） |
| L2b 进程托管 | 解压 `lite.zip` 到自有目录、自定义 conf 起独立实例 | 技术可行 | 与已装服务**端口冲突**、APK 端口常量需跟着改 | 仅做开关，默认关 |
| L2c UI 嵌入 | 把 Sunshine Web UI iframe / 反代进 8787 管理页 | **不推荐** | 自签证书在 iframe 内被浏览器直接阻断、401、跨源 CSRF、主题不一致 | 不做 |
| L3 分发嵌入 | 把 Sunshine 二进制打包进我们的 exe / 静默安装驱动 | 技术可行但**不建议** | GPLv3 义务、体积、驱动签名/UAC、版本迭代、与已装实例冲突 | **不做，维持旧结论** |

**一句话**：能"接管"和"代理"Sunshine，不适合"装进"我们的程序；把 L2 从旧评估的"中等／搁置"上调为推荐，但 L3 结论不变。

---

## 1. 本机实测基线（2026-11-15，只读）

| 项 | 实测值 |
|---|---|
| 安装路径 / 发布包 | `E:\Program Files\Sunshine`，版本 `2026.914.233613` commit `63d35f702ee9…` |
| 运行形态 | `SunshineService`，`Running / Automatic`，binPath `…\tools\sunshinesvc.exe`（服务模式，非用户进程） |
| 监听端口 | `0.0.0.0 : 47984 / 47989 / 47990 / 48010`（PID 20068） |
| 安装体积 | **94.7 MB**（`sunshine.exe` 67.6 MB + `tools` 24 MB + `assets`）；便携包 `lite.zip` 体积本次未取到（GitHub API 返回空），量级为数十 MB |
| 配置 | `config/sunshine.conf` 为空（全默认）；`config/sunshine_state.json` 含 `username=sunshine` + `salt` + `password`（加盐哈希，非明文） |
| 防火墙 | `VRPlayerServer-TCP/UDP`、`VRPlayerSunshine-TCP/UDP` 四条 `Enabled=True / Profile=Any` |
| 明文 47989 | `GET /serverinfo` → **200 XML**（`appversion=7.1.431.-1`、`currentgame=0`、`SUNSHINE_SERVER_FREE`） |
| 明文 47989 `/api/*` | `/api/pin`、`/api/config`、`/api/apps`、`/api/clients/list` 均返回 **HTTP 200 但 body 为 `<root status_code="404"/>`**（Sunshine 把未知路径包成 200）；**Web UI API 不在明文端口** |
| HTTPS 47990 | `GET /` 与 `/api/pin` → **401**（Basic Auth），与 README 旧记录一致 |
| CLI `--help` | 支持 `[path/to/configuration_file]`、`name=value` 覆盖任意配置项、`--creds user pass`、`--version`、`-0 Read PIN from stdin` |

> **易混淆点**：README 多处写的「Sunshine 7.1.431」是 `/serverinfo` 的 **GameStream 协议 appversion**，不是产品版本。本机实际是 2026.914，已包含 2026.906 的一批破坏性变更。

---

## 2. "嵌入"先拆成三件事

用户口中的「嵌入」可以指三种完全不同的东西，混在一起评估会得出错误结论：

1. **UI 嵌入**：把 Sunshine 的 Web UI 放进我们的管理页（iframe / 反向代理）。
2. **能力嵌入**：我们的 8787 管理页提供"配对、状态、列表、配置"接口，底层转调 Sunshine。
3. **分发嵌入**：把 Sunshine 二进制随 `VR片源服务.exe` 一起交付，用户不用单独安装。

三者难度、法律含义、维护成本完全不同。第 3 项才是旧评估说的 L3。

---

## 3. 逐层判断

### 3.1 UI 嵌入 —— 不做

- Sunshine Web UI 是 **HTTPS + 自签证书**。顶层标签页可以点"继续访问"，**iframe 内的子资源没有这个交互入口**，主流浏览器直接阻断。
- 47990 全站 Basic Auth（本机实测 401），iframe 还要过一次登录。
- 2026.914 起 Web UI 有 **CSRF 保护**，跨源（`http://127.0.0.1:8787` → `https://127.0.0.1:47990`）默认不在允许列表。
- 视觉上是另一套主题，与刚定下的 KINO 黑橙拼色冲突。
- 唯一干净的做法是"我们的前端 + 我们的 API 代理"，也就是 3.2，而不是嵌它的页面。

### 3.2 能力代理 —— 推荐（这是真正值得做的"嵌入"）

**已经具备的地基**

- `streamwatch.rs` 每 2 秒用纯标准库 TCP 读 `http://127.0.0.1:47989/serverinfo` 的 `<currentgame>`，驱动串流自动静音；这条通道无需 TLS、无需凭据，可直接扩展到会话状态展示、串流中/空闲、`/cancel` 提示。
- `http.rs` 已有 `/api/sunshine`、`/api/sunshine/open`、`/api/sunshine/guide`，加接口不需要改架构；`util::run_hidden()` 可直接起外部进程。
- APK 端 `StreamPairing.java` 已实现 Gen7 六步配对并显示 4 位 PIN，**只差"谁把 PIN 交给 Sunshine"这一步**。

**自动配对的三条路径（新增发现）**

| 路径 | 结论 | 依据 |
|---|---|---|
| `POST /api/pin` + Basic Auth | ✅ **首选** | 社区实测可用：`curl -k -u user:pass -H "Content-Type: application/json" -X POST https://localhost:47990/api/pin -d '{"pin":"1234","name":"VRPlayer"}'`。凭据由用户在管理页录入一次，存 `%APPDATA%\VRPlayerServer\`，不写日志、不覆盖用户现有凭据 |
| `--creds` + 自定义 conf | ✅ 仅用于**我们托管的独立实例** | 本机 `--help` 确认 `--creds username password`、`[/path/to/configuration_file]`、`name=value` 覆盖任意配置项（含 `port` / `csrf_allowed_origins` / `credentials_file`）。旧评估担心的"改 `sunshine.conf` 路径跨版本"可由 `name=value` 绕过。**但绝对不要拿它去重设用户已装实例的密码** |
| `-0` / `--pin-stdin` | ❌ 不要依赖 | 本机 CLI 仍列出该 flag，但上游 issue #3868（2025-05 起，0.23.1 之后）确认配对时 `Invalid uniqueid (Error 400)`，维护者明确建议改用 REST API，2026 版未见修复 |

**CSRF**：官方 `configuration.md` 确认 `csrf_allowed_origins` 默认放行 `https://localhost` / `https://127.0.0.1` / `https://[::1]` 及 UI 端口变体，"来自允许 origin 的请求可不带 CSRF token 访问状态变更接口"。服务端进程发起的请求可带 `Origin: https://localhost:47990`；若实测仍被拦，再在自有实例上用 `csrf_allowed_origins` 显式放行 `http://127.0.0.1:8787`（仅自有实例，不改用户配置）。**需实测确认。**

**HTTPS 客户端**：`pc-server` 是 Rust 标准库零依赖 + windows-gnu 构建，没有 TLS 客户端。不引第三方 crate 的解法：

- 用 Win11 自带 `curl.exe`（本次所有探测均已通过它完成）以 `run_hidden("curl", …)` 代发，`-k` 跳过自签证书、`-u` 带凭据；
- 或用 PowerShell `Invoke-WebRequest` + 忽略证书校验回调；
- 引入 rustls / reqwest 会打破"零依赖、MinGW 可编译"的既定约束，不推荐。

### 3.2b 独立实例托管 —— 可选，默认关

CLI 允许 `sunshine.exe <自有 conf 路径> port=… credentials_file=…`，所以理论上能把 `Sunshine-Windows-AMD64-lite.zip` 解压到 `%APPDATA%\VRPlayerServer\sunshine\` 起一个完全不碰 `Program Files` 的实例。

**但本机已经跑着 `SunshineService` 并占用 47984/47989/47990/48010**，二选一：

- 停/禁用用户已装服务 → 侵入性强、 UAC、破坏用户原有 Moonlight 配对；
- 换基准端口（如 `port=48989`）→ 防火墙 `47984-48010` 规则段、APK 端端口常量、Moonlight mDNS 发现全部要跟着改，等于把串流链路重做一遍。

因此**推荐"接管已装实例"（只代理 API），不另起实例**。lite.zip 托管留作"用户机器没装 Sunshine 时"的可选开关，默认关闭。

### 3.3 分发嵌入 —— 不做（维持旧结论，理由更硬）

| 风险 | 说明 |
|---|---|
| 许可 | Sunshine 为 **GPL-3.0**。通过 CLI / HTTP 调用外部的、独立安装的 Sunshine 一般不构成衍生作品；但**把二进制随我们的发行物一起分发**就要履行 GPLv3 的源码提供、许可证随附、不得附加限制等义务，且官方 legal 页提示编码器另涉 MPEG-LA 等专利许可。非法律意见，但足以让"顺手打包"变得不划算 |
| 体积 | 现在单文件 exe ~500 KB；本机解压后 94.7 MB，打包后仍是几十 MB 量级，且必须是**多文件目录**（DLL + assets + tools），不再是"双击一个 exe" |
| 驱动 / UAC | 2026.906 起 Windows 安装走 WiX `.msi`（NSIS `.exe` 已停用），服务注册、驱动安装都要管理员；与"静默无感"目标冲突 |
| 冲突 | 用户已装 `E:\Program Files\Sunshine` 并注册服务，再塞一份必然双实例抢端口 |
| 更新 | 版本节奏 `2026.906 → 914 → 1017 → 1102 → 1103`；2026.906 一次修了 5 个 GHSA 关键安全项，内置版本会立刻滞后，还得我们自己背安全更新责任 |
| 边界 | 违反 `sunshine.rs` 文件头写死的「不自动下载/安装第三方软件」产品边界 |

---

## 4. 新版 Sunshine（2026.906+）对我们已有代码的冲击

| 变化 | 影响 |
|---|---|
| ViGEmBus 不再捆绑/自动安装，改 `libvirtualhid`；Windows 完整手柄回传需**单独安装 Virtual HID Driver + 付费机器许可证**（驱动 ≥ 2026.905.2300.20），旧 ViGEmBus 仅作有限 Xbox360/DS4 回退 | ⚠️ **`src/sunshine.rs` 检测 `ViGEmBus.sys`、`src/ui.html` 的「安装手柄驱动」按钮指向 ViGEmBus releases 已过时**；这直接影响后续 S4 手柄计划的成本与可行性，需先确认用户是否接受付费驱动，再设计手柄方案 |
| Web UI 加入 CSRF 保护 | 代理 API 要处理 origin / token（见 3.2） |
| 便携包改名 `Sunshine-Windows-{arch}-lite.zip`；NSIS exe 停用，改 WiX msi | `DOWNLOAD_URL` 用 `releases/latest` 仍有效（未写死文件名），但任何写死包名的自动化都要改 |
| Web UI 不再开机自动打开 | 我们的 `/api/sunshine/open` 显式打开反而更必要 |
| Windows 最低 Win11 | 用户机器满足 |
| 端口基准 `port = 47989`，Web UI = 基准 +1 | 现有 `TCP_RANGE 47984-48010` / `UDP_RANGE 47998-48010` 仍覆盖默认段 |
| 服务模式下 `Windows.Graphics.Capture` 抓屏受限 | 当前服务模式真机已出图，不改动这块 |

---

## 5. 推荐默认项与落地清单

> 默认：**阶段 A 做能力代理 + 修正过时的手柄检测；阶段 B 可选；L3 不做。**
> 全程不改 APK、不改用户已装 Sunshine 的配置与凭据、不引入 Rust 第三方依赖。

**阶段 A（建议先做，低风险）**

1. `src/sunshine.rs`：手柄检测由 `ViGEmBus.sys` 改为版本感知提示（新版提示 Virtual HID Driver / 许可证，旧版保留 ViGEmBus 判定）；`Status.hint` 同步。
2. `src/ui.html`：串流卡片「安装手柄驱动」改为「查看输入驱动状态」，引导页按版本路由，不再承诺能一键装驱动。
3. 新增 `POST /api/sunshine/pair`（body `pin`）：管理页把 APP 上显示的 4 位 PIN 填一次，Rust 端用系统 `curl.exe` 代发 `POST https://127.0.0.1:47990/api/pin`；返回结果映射到 `/api/status` 的 `sunshine.pairResult`。
4. 新增凭据录入：管理页一次输入 Web UI 用户名/密码，写入 `%APPDATA%\VRPlayerServer\`（独立文件、不进日志、不进 `/api/status` 响应）；**提供"忘记/清除"入口**，绝不调用 `--creds` 改用户现有密码。
5. `streamwatch.rs` 把会话状态（空闲 / 串流中 / 探测失败）暴露到管理页，复用现有 2 秒轮询。
6. 更新 `README.md` 旧评估段落，链接本文；`selftest.js` 补 3 项断言（47989 只读可达、47990 未授权返回 401、pair 接口参数校验）。

**阶段 B（可选，需单独确认）**

- `/api/apps` 应用列表、`/api/clients/unpair` 取消授权、`/api/config` 只读、`/cancel` 结束会话代理；
- "本机未安装 Sunshine 时用 lite.zip 托管独立实例"开关（默认关），必须换端口段并同步 APK 端口常量与防火墙规则；
- 手柄方案：先确认是否接受 Virtual HID Driver 的付费许可，再决定 S4 走哪条输入回传路径。

**明确不做**：把 Sunshine 二进制打进 `VR片源服务.exe`；静默装驱动；改用户现有凭据；`-0` stdin 配对；iframe 嵌 Web UI。

**验证步骤（可复现）**

```
# 只读基线
curl -s http://127.0.0.1:47989/serverinfo                 # 200 + <currentgame>
curl -s -o /dev/null -w "%{http_code}" -k https://127.0.0.1:47990/api/pin   # 401
"E:\Program Files\Sunshine\sunshine.exe" --help           # 确认 creds / name=value / -0
Get-Service SunshineService ; Get-NetTCPConnection -State Listen | ? LocalPort -in 47984,47989,47990,48010
```

**仍需人工**：用真实 Web UI 凭据跑一次 `POST /api/pin` 端到端（APP 显示 PIN → 管理页提交 → 自动起流）；确认 CSRF origin 是否被放行；确认新版手柄驱动许可是否可接受。

---

## 6. 与旧评估的差异说明

旧评估（README 594-600 行）判定 L2 为"中等：`/api/pin` 需 Basic Auth + CSRF token，改 `sunshine.conf` 需重启且路径跨版本"，因此在当时选择只做 L1 —— 这个判断在本机 401 实测下是成立的。

本次复核新增三条信息，使 L2 上调为推荐：

1. CLI 的 `--creds` 与 `name=value` 覆盖任意配置项 ⇒ 配置路径/重启问题可绕过（仅限自有实例）；
2. Win11 自带 `curl.exe` ⇒ 不打破 Rust 零依赖即可发 HTTPS + Basic Auth；
3. 官方 `csrf_allowed_origins` / 默认 localhost origin 放行 ⇒ CSRF 可显式处理。

同时新增两条**上调风险**：2026.906+ 手柄回传转为付费驱动；`-0` stdin 配对已被上游确认损坏。L3 的 GPLv3 / 体积 / 更新滞后结论不变，且理由更充分。

---

## 7. 阶段 A 实施记录（2026-11-16）

**范围**：严格按第 5 节阶段 A，**未做阶段 B / L3**，APK 零改动。

| 项 | 落地 |
|---|---|
| 凭据 | `credentials.rs`：明文存 `%APPDATA%\VRPlayerServer\sunshine-creds.txt`，不写日志、不进 API 响应（只给 `credsSet`），提供 `POST /api/sunshine/credentials/clear`；**未调用 `--creds`**，用户现有密码不变 |
| PIN 代发 | `pairing.rs`：`curl.exe -k --noproxy * -K - -X POST … /api/pin`，凭据走 stdin 的 curl config（不进命令行参数），带 `Origin: https://localhost:47990`；`util.rs` 新增 `run_hidden_with_stdin()` |
| 会话状态 | `streamwatch.rs` 复用 2 秒轮询写 `idle / streaming / unreachable`，连续 3 次探测失败才标不可达；`state.rs` 新增 `session` / `pair_result` |
| 版本感知 | `sunshine.rs` 用服务 binPath 反推自定义安装目录 → 读 exe 的 PE `ProductVersion`（OnceLock 缓存）；≥ `2026.906` 判 `modern`，输入后端 `virtualhid`，引导按钮改「查看输入驱动状态」并指向官方排障页 |
| 接口 / UI | `http.rs` 新增 3 个 POST；`/api/status`、`/api/sunshine` 内嵌 `session / pairResult / credsSet / version / modern / inputBackend / inputHint`；`ui.html` 新增 PIN 输入、凭据录入/清除、会话与输入后端卡片 |
| 测试 | `selftest.js` 新增 6 项 L2 断言；`Cargo.toml` 升 `0.3.0`，`dist/VR片源服务.exe` 更新为 527,872 B |

**验证**：`cargo build --release` 零错误；`selftest.js` **22/22 PASS**（含 6 项 L2）；
本机识别 `version=2026.914 / modern=true / inputBackend=virtualhid / session=idle`；
端到端六步（无凭据提示 → 保存错误凭据 → `credsSet=true` 且响应无用户名/密码 →
代发 PIN 得 **HTTP 401** 可读提示 → 清除后 `credsSet=false` 且文件删除 → 页面新元素齐全）全通；
`server.log` 抽查无 PIN / 用户名 / 密码。

**认证后 CSRF 仍未验证**：401 只证明链路打通，没证明凭据正确时 `Origin` 能被 Sunshine 放行。
真凭据联调若返回 403，按 `pairing.rs` 的提示在 `sunshine.conf` 加
`csrf_allowed_origins = http://127.0.0.1:8787` 并重启 Sunshine（**这是用户手动改自己的配置，
不是程序代改**）。

**凭据明文是已知取舍**：本机自用、无外联，换取 Rust 零依赖；若要对外分发，
应优先换成 Windows DPAPI（`credentials.rs` 顶部已注明）。

**下一步（阶段 B，未实施）**：`/api/apps` 列表、`/api/clients/unpair`、`/api/config` 只读、
`/cancel` 代理；"本机未装 Sunshine 时用 lite.zip 托管独立实例"开关（默认关，需换端口段并同步 APK 常量）。
