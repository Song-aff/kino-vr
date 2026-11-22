use crate::config::Config;
use crate::firewall::Status;
use std::sync::atomic::{AtomicBool, AtomicUsize};
use std::sync::{Arc, Mutex};

pub struct State {
    pub config: Mutex<Config>,
    pub media_port: u16,
    /// 自动探测到的局域网 IP（可能被用户在管理页覆盖）。
    pub auto_ip: String,
    pub auto_candidates: Vec<String>,
    /// 随 UDP ANNOUNCE 广播的片源数；目录变更 / 状态刷新时更新，避免每次广播扫盘。
    pub file_count: AtomicUsize,
    /// 防火墙自愈结果，管理页展示；一键修复后可刷新。
    pub firewall: Mutex<Status>,
    /// 当前系统主静音是否由本程序触发（决定是否允许恢复）。
    pub muted_by_us: AtomicBool,
    /// Sunshine 会话状态：unknown / idle / streaming / unreachable，由 streamwatch 每 2 秒刷新。
    pub session: Mutex<String>,
    /// 最近一次 PIN 代发结果（完整 JSON 字符串，初值 "null"），供 /api/status 内嵌。
    pub pair_result: Mutex<String>,
}

pub type Shared = Arc<State>;

impl State {
    /// 对外广播 / 状态展示用的 IP。注意调用方不得在持有 config 锁时调用本方法。
    pub fn advertise_ip(&self) -> String {
        let cfg = self.config.lock().unwrap();
        if cfg.advertise_ip.is_empty() { self.auto_ip.clone() } else { cfg.advertise_ip.clone() }
    }

    pub fn set_firewall(&self, status: Status) {
        *self.firewall.lock().unwrap() = status;
    }
}

pub fn shared(config: Config, media_port: u16, candidates: Vec<String>, fw: Status) -> Shared {
    let auto_ip = candidates.first().cloned().unwrap_or_else(|| "127.0.0.1".to_string());
    Arc::new(State {
        config: Mutex::new(config),
        media_port,
        auto_ip,
        auto_candidates: candidates,
        file_count: AtomicUsize::new(0),
        firewall: Mutex::new(fw),
        muted_by_us: AtomicBool::new(false),
        session: Mutex::new("unknown".to_string()),
        pair_result: Mutex::new("null".to_string()),
    })
}

pub const ADMIN_PORT: u16 = 8787;
pub const DEFAULT_MEDIA_PORT: u16 = 8000;
