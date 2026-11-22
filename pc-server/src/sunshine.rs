//! Sunshine（Moonlight 串流服务端）状态检测与引导。
//!
//! 设计边界：本程序**不自动下载/安装第三方软件**。Sunshine 是系统服务，
//! 手柄虚拟设备依赖内核驱动，必须由用户确认安装。这里只负责检测、状态展示
//! 与一键引导；端口放行交给管理员启动时的 `firewall::apply()` 静默完成。
//!
//! L2 补充：PIN 代发在 `pairing.rs`，凭据在 `credentials.rs`，本文件仍只做只读探测。
//! 2026.906 起上游把输入后端从 ViGEmBus 换成 libvirtualhid / Virtual HID Driver
//! （完整手柄回传需单独装驱动 + 机器许可证），因此检测要做版本感知。

use crate::firewall;
use crate::util::{json_escape, run_hidden};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Duration;

/// 官方 Advanced Usage 端口表（基准端口 47989）：
/// TCP 47984 配对 / 47989 HTTP API / 47990 Web UI / 48010 RTSP
/// UDP 47998 视频 / 47999 控制 / 48000 音频 / 48002 麦克风
/// 用连续范围建规则，避免漏端口；区间全部落在 Sunshine 私有段内。
pub const TCP_RANGE: &str = "47984-48010";
pub const UDP_RANGE: &str = "47998-48010";
pub const WEB_PORT: u16 = 47990;
pub const DOWNLOAD_URL: &str = "https://github.com/LizardByte/Sunshine/releases/latest";
pub const VIGEM_URL: &str = "https://github.com/nefarius/ViGEmBus/releases/latest";
/// 2026.906+ 的 Virtual HID Driver 说明在官方排障页，不再引导用户装 ViGEmBus。
pub const INPUT_GUIDE_MODERN: &str =
    "https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2troubleshooting.html";
/// 版本更新判断阈值：2026.906 是输入后端切换的版本。
const MODERN_CUTOFF: (u32, u32) = (2026, 906);

pub fn input_guide_url(modern: bool) -> &'static str {
    if modern { INPUT_GUIDE_MODERN } else { VIGEM_URL }
}

#[derive(Clone, Debug)]
pub struct Status {
    pub installed: bool,
    pub exe: String,
    pub running: bool,
    /// 兼容字段：是否检测到旧版 ViGEmBus 驱动（新版仅作有限回退，不代表手柄可用）。
    pub vigem: bool,
    pub ports_open: bool,
    pub web_url: String,
    pub hint: String,
    /// 产品版本标签，如 `2026.914`；探测不到为 `unknown`。
    pub version: String,
    /// 是否 >= 2026.906（输入后端改为 Virtual HID Driver）。
    pub modern: bool,
    /// virtualhid / vigembus / missing / unknown
    pub input_backend: String,
    pub input_hint: String,
}

impl Status {
    pub fn json(&self) -> String {
        format!(
            "{{\"installed\":{},\"exe\":\"{}\",\"running\":{},\"vigem\":{},\"portsOpen\":{},\"webUrl\":\"{}\",\"hint\":\"{}\",\"tcpRange\":\"{}\",\"udpRange\":\"{}\",\"version\":\"{}\",\"modern\":{},\"inputBackend\":\"{}\",\"inputHint\":\"{}\"}}",
            self.installed,
            json_escape(&self.exe),
            self.running,
            self.vigem,
            self.ports_open,
            json_escape(&self.web_url),
            json_escape(&self.hint),
            TCP_RANGE,
            UDP_RANGE,
            json_escape(&self.version),
            self.modern,
            json_escape(&self.input_backend),
            json_escape(&self.input_hint)
        )
    }
}

/// 常见安装位置。找不到不等于没装（用户可能装在自定义目录），
/// 因此 `running` 单独用端口探测，安装过但路径特殊时仍能识别为「已就绪」。
fn install_candidates() -> Vec<PathBuf> {
    let mut out = Vec::new();
    for key in ["PROGRAMFILES", "PROGRAMFILES(X86)"] {
        if let Ok(base) = std::env::var(key) {
            out.push(PathBuf::from(base).join("Sunshine").join("sunshine.exe"));
        }
    }
    if let Ok(local) = std::env::var("LOCALAPPDATA") {
        out.push(PathBuf::from(local).join("Programs").join("Sunshine").join("sunshine.exe"));
    }
    if let Ok(base) = std::env::var("PROGRAMDATA") {
        out.push(PathBuf::from(base)
            .join("chocolatey").join("lib").join("sunshine")
            .join("tools").join("sunshine.exe"));
    }
    out
}

/// 固定路径没命中时，从运行中的进程取真实安装路径。
/// 管理页 5 秒轮询，用 OnceLock 只查一次，避免反复启动 PowerShell。
static PROCESS_EXE: OnceLock<String> = OnceLock::new();
fn running_exe() -> String {
    PROCESS_EXE
        .get_or_init(|| {
            let ps = "Get-Process -Name sunshine -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Path";
            match run_hidden("powershell", &["-NoProfile", "-Command", ps]) {
                Some(out) if out.status.success() => {
                    String::from_utf8_lossy(&out.stdout).trim().to_string()
                }
                _ => String::new(),
            }
        })
        .clone()
}

/// 产品版本只读探测：读 Sunshine exe 的 PE 版本信息，不启动进程、不写它的日志。
/// 管理页每 5 秒刷新，用 OnceLock 缓存，避免反复起 PowerShell。
static VERSION: OnceLock<Option<(u32, u32)>> = OnceLock::new();
fn parsed_version() -> Option<(u32, u32)> {
    *VERSION.get_or_init(read_version)
}

fn read_version() -> Option<(u32, u32)> {
    let exe = install_candidates()
        .into_iter()
        .find(|p| p.is_file())
        .or_else(service_sunshine_exe)?;
    let literal = exe.to_string_lossy().replace('\'', "''");
    let ps = format!("(Get-Item -LiteralPath '{}').VersionInfo.ProductVersion", literal);
    let out = run_hidden("powershell", &["-NoProfile", "-Command", &ps])?;
    if !out.status.success() { return None; }
    let text = String::from_utf8_lossy(&out.stdout);
    let mut it = text.trim().split('.');
    let year = it.next()?.trim().parse::<u32>().ok()?;
    let seq = it.next()?.trim().parse::<u32>().ok()?;
    Some((year, seq))
}

/// 自定义安装目录（本机 `E:\Program Files\Sunshine`）不在标准候选路径里，
/// 从已注册服务的 binPath 反推安装根，避免把新版误判成「版本未知」。
fn service_sunshine_exe() -> Option<PathBuf> {
    let ps = "(Get-CimInstance Win32_Service -Filter \"Name='SunshineService'\").PathName";
    let out = run_hidden("powershell", &["-NoProfile", "-Command", ps])?;
    if !out.status.success() { return None; }
    let raw = String::from_utf8_lossy(&out.stdout);
    let path = PathBuf::from(raw.trim().trim_matches('"'));
    let exe = path.parent()?.parent()?.join("sunshine.exe");
    if exe.is_file() { Some(exe) } else { None }
}

fn version_label() -> String {
    match parsed_version() {
        Some((y, m)) => format!("{}.{}", y, m),
        None => "unknown".to_string(),
    }
}

fn is_modern() -> bool {
    matches!(parsed_version(), Some((y, m))
        if y > MODERN_CUTOFF.0 || (y == MODERN_CUTOFF.0 && m >= MODERN_CUTOFF.1))
}

/// 规则可能由上一次管理员运行建好，而本次进程是普通权限启动的。
/// 只看 `fw.applied` 会把「规则早已存在」误报成「未放行」，故实查一次（缓存）。
static RULES_PRESENT: OnceLock<bool> = OnceLock::new();
fn rules_present() -> bool {
    *RULES_PRESENT.get_or_init(|| {
        let ps = format!(
            "$a = Get-NetFirewallRule -DisplayName '{}' -ErrorAction SilentlyContinue; \
             $b = Get-NetFirewallRule -DisplayName '{}' -ErrorAction SilentlyContinue; \
             if ($a -and $b -and $a.Enabled -and $b.Enabled) {{ 'yes' }} else {{ 'no' }}",
            firewall::RULE_SUNSHINE_TCP, firewall::RULE_SUNSHINE_UDP
        );
        matches!(run_hidden("powershell", &["-NoProfile", "-Command", &ps]),
            Some(out) if out.status.success()
                && String::from_utf8_lossy(&out.stdout).trim() == "yes")
    })
}

/// 只测 TCP 监听，不发起 TLS 握手（Web UI 用自签证书）。
fn port_open(port: u16) -> bool {
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    TcpStream::connect_timeout(&addr, Duration::from_millis(150)).is_ok()
}

fn vigem_present() -> bool {
    if let Ok(windir) = std::env::var("WINDIR") {
        if PathBuf::from(windir)
            .join("System32").join("drivers").join("ViGEmBus.sys")
            .is_file()
        {
            return true;
        }
    }
    // 驱动文件不在标准位置时，用服务查询兜底。
    matches!(run_hidden("sc", &["query", "ViGEmBus"]), Some(out) if out.status.success()
        && String::from_utf8_lossy(&out.stdout).contains("ViGEmBus"))
}

/// `fw.applied` 来自启动时 / 一键修复后的防火墙自愈结果（4 条规则全建成功才算 true）。
/// 复用它避免每次状态刷新都跑 4 次 netsh 子进程。
pub fn detect(fw: &firewall::Status) -> Status {
    let mut exe_path = install_candidates()
        .into_iter()
        .find(|p| p.is_file())
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default();
    let running = port_open(WEB_PORT) || port_open(47989);
    // 端口在监听即证明已安装：自定义目录 / 便携版不该被误判成「未安装」。
    if exe_path.is_empty() && running {
        exe_path = running_exe();
    }
    let installed = !exe_path.is_empty() || running;
    let vigem = vigem_present();
    let ports_open = fw.applied || rules_present();
    let web_url = format!("https://127.0.0.1:{}/", WEB_PORT);
    let version = version_label();
    let modern = is_modern();
    let input_backend = String::from(if modern {
        "virtualhid"
    } else if vigem {
        "vigembus"
    } else if version == "unknown" {
        "unknown"
    } else {
        "missing"
    });
    let input_hint = String::from(if modern {
        "2026.906+ 输入后端改为 Virtual HID Driver：完整手柄回传需单独安装驱动并购买机器许可证；已装的 ViGEmBus 仅作 Xbox360/DS4 回退。"
    } else if vigem {
        "ViGEmBus 已安装，旧版 Sunshine 的手柄回传可用。"
    } else if version == "unknown" {
        "未能读取 Sunshine 版本，输入后端状态未知；请点「查看输入驱动状态」到官方页确认。"
    } else {
        "未检测到 ViGEmBus 驱动，旧版 Sunshine 的手柄回传暂不可用。"
    });
    let hint = if !installed && !running {
        "未检测到 Sunshine。点「下载 Sunshine」安装（Windows 版需 Win11），装完刷新本页即可。"
    } else if !running {
        "Sunshine 已安装但未运行。请从开始菜单或托盘启动它，启动后本页会自动更新。"
    } else if !ports_open {
        "Sunshine 正在运行，但串流端口尚未放行。以管理员身份运行本程序会自动处理，或点「一键修复防火墙」。"
    } else if modern {
        "Sunshine 已就绪（2026.906+）。手柄回传改用 Virtual HID Driver（需驱动 + 机器许可），见下方说明；APP 的 4 位配对码可在本页直接提交。"
    } else if !vigem {
        "Sunshine 已就绪；未检测到 ViGEmBus 驱动，手柄回传暂不可用，可点「查看输入驱动状态」确认。"
    } else {
        "Sunshine 已就绪：APP 显示的 4 位配对码可在本页直接提交，无需再开 Web UI 的 PIN 页。"
    }
    .to_string();
    Status {
        installed, exe: exe_path, running, vigem, ports_open, web_url, hint,
        version, modern, input_backend, input_hint,
    }
}
