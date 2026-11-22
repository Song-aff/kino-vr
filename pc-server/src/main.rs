//! VR 片源服务 · PC 端
//!
//! 双击即可运行：自动打开本地管理页，向局域网提供 HTTP Range 片源服务与 UDP 自动发现。
//! 零第三方依赖，纯 Rust 标准库，可在 windows-gnu (MinGW) 工具链编译。

// 无控制台黑窗；状态与异常写入 %APPDATA%\VRPlayerServer\server.log。
#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

mod audio;
mod config;
mod credentials;
mod discovery;
mod firewall;
mod http;
mod pairing;
mod state;
mod streamwatch;
mod sunshine;
mod util;

use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use state::{Shared, ADMIN_PORT, DEFAULT_MEDIA_PORT};
use util::{lan_candidates, log, open_browser};

fn main() {
    std::panic::set_hook(Box::new(|info| log(&format!("PANIC: {}", info))));

    // 提权子进程：只负责重建防火墙规则，随后立刻退出，不绑定任何端口。
    if std::env::args().any(|a| a == "--fix-firewall") {
        let st = firewall::apply(DEFAULT_MEDIA_PORT, discovery::DISCOVERY_PORT);
        log(&format!("提权子进程结束: admin={} applied={}", st.admin, st.applied));
        return;
    }

    // 诊断开关：验证 CoreAudio 静音通道可用，结果写日志目录，不启动服务。
    if std::env::args().any(|a| a == "--audio-selftest") {
        let result = audio::selftest();
        log(&format!("音量控制自检: {}", result));
        return;
    }

    let admin_url = format!("http://127.0.0.1:{}/", ADMIN_PORT);
    // --no-browser：供开机自启、自动化测试等场景使用，不弹浏览器。
    let no_browser = std::env::args().any(|a| a == "--no-browser");

    // 单实例：已有实例在跑时，双击 exe 只负责重新打开管理页。
    if already_running() {
        log("检测到已有实例，直接打开管理页");
        if !no_browser { open_browser(&admin_url); }
        return;
    }

    let admin = match TcpListener::bind(("127.0.0.1", ADMIN_PORT)) {
        Ok(l) => l,
        Err(e) => { log(&format!("管理页端口 {} 被占用，启动失败: {}", ADMIN_PORT, e)); return; }
    };
    let (media, media_port) = match bind_media(DEFAULT_MEDIA_PORT) {
        Ok(pair) => pair,
        Err(e) => { log(&format!("片源端口绑定失败: {}", e)); return; }
    };

    let cfg = config::reload_or_default();
    let candidates = lan_candidates();
    let ip = candidates.first().cloned().unwrap_or_else(|| "127.0.0.1".to_string());
    // 管理员运行时静默重建入站放行规则；非管理员不阻塞启动，管理页会给一键修复入口。
    let fw = firewall::apply(DEFAULT_MEDIA_PORT, discovery::DISCOVERY_PORT);
    let shared: Shared = state::shared(cfg, media_port, candidates, fw);
    http::refresh_file_count(&shared);

    {
        let cfg = shared.config.lock().unwrap();
        log(&format!(
            "服务启动 | 目录={} | 名称={} | IP={} | 片源端口={} | 管理页={} | 发现端口={}",
            cfg.root.display(), cfg.name, ip, media_port, admin_url, discovery::DISCOVERY_PORT
        ));
    }

    let d = shared.clone();
    thread::spawn(move || {
        if let Err(e) = discovery::start(d) { log(&format!("发现服务退出: {}", e)); }
    });
    let m = shared.clone();
    thread::spawn(move || http::serve_media(m, media));
    let w = shared.clone();
    thread::spawn(move || streamwatch::start(w));

    if !no_browser { open_browser(&admin_url); }
    http::serve_admin(shared, admin);
}

/// 8000 被占用时顺延，实际端口由 UDP 广播告知手机端，无需手填。
fn bind_media(start: u16) -> io::Result<(TcpListener, u16)> {
    for port in start..start + 20 {
        if let Ok(listener) = TcpListener::bind(("0.0.0.0", port)) {
            return Ok((listener, port));
        }
    }
    Err(io::Error::new(io::ErrorKind::AddrInUse, format!("{}-{} 全部被占用", start, start + 19)))
}

/// 探活管理页接口，避免把「端口恰好被别的程序占用」误判成已有实例。
fn already_running() -> bool {
    let Ok(mut stream) = TcpStream::connect(("127.0.0.1", ADMIN_PORT)) else { return false };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(600)));
    let _ = stream.write_all(
        b"GET /api/status HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
    );
    let mut buf = [0u8; 512];
    stream
        .read(&mut buf)
        .map(|n| String::from_utf8_lossy(&buf[..n]).contains("\"running\":true"))
        .unwrap_or(false)
}

