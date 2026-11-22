//! Windows 防火墙自愈。
//!
//! 背景（真机实测）：Windows 防火墙的入站 Allow 规则带 profile 作用域，
//! 若规则是 Public 而 WLAN 被识别为 Private，规则不生效，手机发来的 TCP SYN
//! 会被直接丢弃 —— 表现为「UDP 自动发现成功，但拉取片源列表超时」。
//!
//! 因此：以管理员运行本程序时，静默重建两条固定命名、profile=Any 的入站放行规则；
//! 非管理员运行时不阻塞启动，管理页给出提示并可一键提权修复。

use crate::util::{log, run_hidden};

/// 规则名用纯 ASCII，避免 netsh 在不同代码页下的中文参数解析问题。
pub const RULE_TCP: &str = "VRPlayerServer-TCP";
pub const RULE_UDP: &str = "VRPlayerServer-UDP";
pub const RULE_SUNSHINE_TCP: &str = "VRPlayerSunshine-TCP";
pub const RULE_SUNSHINE_UDP: &str = "VRPlayerSunshine-UDP";

#[derive(Clone, Debug)]
pub struct Status {
    pub admin: bool,
    pub applied: bool,
    pub detail: String,
}

impl Status {
    pub fn not_admin() -> Self {
        Status {
            admin: false,
            applied: false,
            detail: "未以管理员运行，本次未自动放行防火墙".to_string(),
        }
    }
    pub fn json(&self) -> String {
        format!(
            "{{\"admin\":{},\"applied\":{},\"detail\":\"{}\"}}",
            self.admin,
            self.applied,
            crate::util::json_escape(&self.detail)
        )
    }
}

fn netsh(args: &[&str]) -> bool {
    matches!(run_hidden("netsh", args), Some(out) if out.status.success())
}

/// `net session` 需要管理员令牌；非管理员返回退出码 2 且 stderr 为拒绝访问。
/// 比链接 advapi32 声明 CheckTokenMembership 更省事，且 windows-gnu 工具链零风险。
pub fn is_admin() -> bool {
    matches!(run_hidden("net", &["session"]), Some(out) if out.status.success())
}

/// 幂等重建单条入站放行规则：先删后加，端口范围 / profile 每次启动刷新。
fn rebuild(name: &str, protocol: &str, ports: &str) -> bool {
    let name_arg = format!("name={}", name);
    let proto_arg = format!("protocol={}", protocol);
    let port_arg = format!("localport={}", ports);
    // delete 在规则不存在时会失败，忽略即可；add 的返回码才是判定依据。
    let _ = netsh(&["advfirewall", "firewall", "delete", "rule", &name_arg]);
    netsh(&[
        "advfirewall", "firewall", "add", "rule", &name_arg,
        "dir=in", "action=allow", &proto_arg, &port_arg, "profile=any",
    ])
}

/// 幂等重建规则：先删后加，端口范围 / profile 随本次启动刷新。
/// TCP 覆盖 8000-8019 是因为片源端口被占用时会自动顺延。
pub fn apply(media_port_start: u16, discovery_port: u16) -> Status {
    if !is_admin() {
        let s = Status::not_admin();
        log(&format!("防火墙: {}", s.detail));
        return s;
    }
    let tcp_range = format!("{}-{}", media_port_start, media_port_start + 19);
    let udp_port = discovery_port.to_string();
    // 片源服务两条 + Sunshine 串流两条，四条全成功才算 applied。
    // 即使 Sunshine 尚未安装也先放行端口，用户装完即可用，不必再跑一次管理员。
    let media_tcp = rebuild(RULE_TCP, "TCP", &tcp_range);
    let media_udp = rebuild(RULE_UDP, "UDP", &udp_port);
    let stream_tcp = rebuild(RULE_SUNSHINE_TCP, "TCP", crate::sunshine::TCP_RANGE);
    let stream_udp = rebuild(RULE_SUNSHINE_UDP, "UDP", crate::sunshine::UDP_RANGE);

    let applied = media_tcp && media_udp && stream_tcp && stream_udp;
    let detail = if applied {
        format!(
            "已自动放行入站：片源 TCP {} / UDP {}，串流 TCP {} / UDP {}（profile=Any）",
            tcp_range, udp_port, crate::sunshine::TCP_RANGE, crate::sunshine::UDP_RANGE
        )
    } else {
        format!(
            "防火墙规则写入失败（片源 TCP={} UDP={}，串流 TCP={} UDP={}），请手动检查",
            media_tcp, media_udp, stream_tcp, stream_udp
        )
    };
    log(&format!("防火墙: {}", detail));
    Status { admin: true, applied, detail }
}

/// 管理页「一键修复」：提权启动自身的 --fix-firewall 子进程（会弹一次 UAC）。
/// 子进程是管理员，会执行 apply() 后立刻退出，不影响当前正在运行的服务。
pub fn elevate_and_fix() -> Status {
    let Ok(exe) = std::env::current_exe() else {
        return Status { admin: false, applied: false, detail: "无法定位程序路径".to_string() };
    };
    let path = exe.to_string_lossy().replace('\'', "''");
    let ps = format!(
        "Start-Process -FilePath '{}' -ArgumentList '--fix-firewall' -Verb RunAs -Wait -WindowStyle Hidden",
        path
    );
    let ok = matches!(
        run_hidden("powershell", &["-NoProfile", "-Command", &ps]),
        Some(out) if out.status.success()
    );
    let status = if ok {
        Status {
            admin: true,
            applied: true,
            detail: format!(
                "已通过管理员子进程放流入站规则：片源 TCP 8000-8019 / UDP 47821，串流 TCP {} / UDP {}（profile=Any）",
                crate::sunshine::TCP_RANGE, crate::sunshine::UDP_RANGE
            ),
        }
    } else {
        Status {
            admin: false,
            applied: false,
            detail: "提权被取消或失败，请右键「以管理员身份运行」本程序".to_string(),
        }
    };
    log(&format!("防火墙一键修复: {}", status.detail));
    status
}
