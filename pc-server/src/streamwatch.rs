//! Sunshine 会话监听与「串流时自动静音本机」联动。
//!
//! Sunshine 明文 47989 的 `/serverinfo` 会返回 `<currentgame>`：0 = 空闲，
//! 非 0 = 正在串流。这里每 2 秒探测一次（纯标准库 TCP，不引入 HTTP 客户端），
//! 状态翻转时调用 `audio` 静音 / 恢复系统主音量。
//!
//! 安全约束：
//! - 只有「原本未静音、且由我们静音」才会恢复，不覆盖用户自己的音量设置；
//! - 静音期间写一个哨兵文件，进程被强杀后下次启动会先自动恢复音量（自愈）；
//! - 探测失败（Sunshine 未运行 / 端口抖动）保持上次判断，避免音量来回抖动；
//! - 管理页关掉开关或点「恢复音量」会立即恢复，不等会话结束。

use crate::audio;
use crate::state::Shared;
use crate::util::{app_dir, log};
use std::fs;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::Ordering;
use std::thread;
use std::time::Duration;

const POLL: Duration = Duration::from_secs(2);
const STATE_FILE: &str = "mute-held";

fn held_file() -> PathBuf { app_dir().join(STATE_FILE) }

/// 返回 Sunshine 当前会话的 appid；0 表示空闲，None 表示探测失败。
fn current_game() -> Option<u64> {
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, 47989u16));
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_millis(250)).ok()?;
    stream.set_read_timeout(Some(Duration::from_millis(900))).ok()?;
    let req = concat!(
        "GET /serverinfo?uniqueid=56524c4159455201&uuid=00000000-0000-0000-0000-000000000001 HTTP/1.1\r\n",
        "Host: 127.0.0.1\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n"
    );
    stream.write_all(req.as_bytes()).ok()?;
    let mut buf = Vec::new();
    stream.read_to_end(&mut buf).ok()?;
    let text = String::from_utf8_lossy(&buf);
    let tag = "<currentgame>";
    let start = text.find(tag)? + tag.len();
    let end = text[start..].find("</currentgame>")? + start;
    text[start..end].trim().parse::<u64>().ok()
}

pub fn start(shared: Shared) {
    // 上次进程被强杀时来不及恢复音量：启动即自愈。
    if held_file().exists() {
        log("检测到上次异常退出遗留的静音，启动时自动恢复本机音量");
        audio::unmute();
        let _ = fs::remove_file(held_file());
    }
    shared.muted_by_us.store(false, Ordering::Relaxed);

    let mut streaming = false;
    let mut failures = 0u8;
    loop {
        let auto = shared.config.lock().map(|c| c.auto_mute).unwrap_or(false);

        // 用户中途在管理页关掉开关，或点了「恢复音量」：立即恢复，不等会话结束。
        if !auto && shared.muted_by_us.load(Ordering::Relaxed) {
            restore(&shared);
        }

        if let Some(game) = current_game() {
            failures = 0;
            let busy = game != 0;
            // L2：把会话状态暴露给管理页（自动静音逻辑与展示状态互不干扰）。
            set_session(&shared, if busy { "streaming" } else { "idle" });
            if busy && !streaming && auto {
                match audio::mute_if_unmuted() {
                    Some(false) => {
                        shared.muted_by_us.store(true, Ordering::Relaxed);
                        let _ = fs::write(held_file(), b"1");
                        log("串流开始：已静音本机主音量");
                    }
                    Some(true) => log("串流开始：用户原本已静音，保持不动"),
                    None => log("串流开始：音量控制不可用，跳过自动静音"),
                }
            } else if !busy && streaming {
                restore(&shared);
            }
            streaming = busy;
        } else {
            // 探测失败时音量判断保持原样（避免抖动），但连续 3 次失败才标为不可达。
            failures = failures.saturating_add(1);
            if failures >= 3 { set_session(&shared, "unreachable"); }
        }
        thread::sleep(POLL);
    }
}

/// 只在状态翻转时写入，避免每 2 秒无意义地抢锁。
fn set_session(shared: &Shared, value: &str) {
    let mut current = shared.session.lock().unwrap();
    if current.as_str() != value { *current = value.to_string(); }
}

/// 只有在确认「由我们静音」时才动系统音量，并清除哨兵文件。
pub fn restore(shared: &Shared) {
    if shared.muted_by_us.swap(false, Ordering::Relaxed) {
        let ok = audio::unmute();
        let _ = fs::remove_file(held_file());
        log(&format!("串流结束/手动恢复：已恢复本机主音量 (ok={})", ok));
    }
}
