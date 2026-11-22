use std::fs::OpenOptions;
use std::io::Write;
use std::net::{ToSocketAddrs, UdpSocket};
use std::path::PathBuf;

/// 应用数据目录：%APPDATA%\VRPlayerServer（失败时退回临时目录）。
pub fn app_dir() -> PathBuf {
    let base = std::env::var("APPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir());
    let dir = base.join("VRPlayerServer");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

/// GUI 子系统下没有控制台，状态与错误写日志便于排障。
pub fn log(line: &str) {
    let path = app_dir().join("server.log");
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "[{}] {}", timestamp(), line);
    }
}

pub fn timestamp() -> String {
    // 精确到秒即可，避免引入 chrono。
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // 本地时间转换（仅用于日志展示，UTC+8 固定偏移足够）。
    let local = secs + 8 * 3600;
    let day = local / 86400;
    let rem = local % 86400;
    let (y, m, d) = civil_from_days(day as i64);
    format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", y, m, d, rem / 3600, (rem % 3600) / 60, rem % 60)
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = mp + if mp < 10 { 3 } else { -9 };
    (y + if m <= 2 { 1 } else { 0 }, m, d)
}

pub fn epoch_millis() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// 枚举本机 IPv4，只保留 RFC1918 私有网段（+ 链路本地兜底）。
///
/// 不能用「UDP connect 公网地址取 local_addr」的方式：装了 Clash/TUN 类虚拟网卡时，
/// 默认路由会指向 198.18.x.x（benchmark 保留段），拿到的不是手机能访问的局域网 IP。
pub fn lan_candidates() -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    if let Ok(host) = std::env::var("COMPUTERNAME") {
        if let Ok(addrs) = (host.as_str(), 0u16).to_socket_addrs() {
            for addr in addrs {
                if let std::net::IpAddr::V4(v4) = addr.ip() {
                    if is_lan_ip(v4.octets()) {
                        let s = v4.to_string();
                        if !out.contains(&s) { out.push(s); }
                    }
                }
            }
        }
    }
    // getaddrinfo 一无所获时，退回路由表出口地址（仍过滤掉非私有段）。
    if out.is_empty() {
        for probe in ["223.5.5.5:80", "119.29.29.29:80"] {
            if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
                if sock.connect(probe).is_ok() {
                    if let Ok(local) = sock.local_addr() {
                        if let std::net::IpAddr::V4(v4) = local.ip() {
                            if is_lan_ip(v4.octets()) { out.push(v4.to_string()); break; }
                        }
                    }
                }
            }
        }
    }
    // 家用路由器 192.168 优先，其次 10 / 172 / 169.254。
    out.sort_by_key(|s| {
        let o: Vec<u8> = s.split('.').filter_map(|p| p.parse().ok()).collect();
        match (o.first().copied(), o.get(1).copied()) {
            (Some(192), Some(168)) => 0,
            (Some(10), _) => 1,
            (Some(172), Some(b)) if (16..=31).contains(&b) => 2,
            _ => 3,
        }
    });
    out
}

fn is_lan_ip(o: [u8; 4]) -> bool {
    o[0] == 10
        || (o[0] == 192 && o[1] == 168)
        || (o[0] == 172 && (16..=31).contains(&o[1]))
        || (o[0] == 169 && o[1] == 254)
}

/// percent-decode，'+' 视为空格（form-urlencoded 兼容）。
pub fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(h), Some(l)) = (hex(bytes[i + 1]), hex(bytes[i + 2])) {
                out.push(h * 16 + l);
                i += 3;
                continue;
            }
        }
        out.push(if bytes[i] == b'+' { b' ' } else { bytes[i] });
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

pub fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

pub fn json_string_array(items: &[String]) -> String {
    let parts: Vec<String> = items.iter().map(|s| format!("\"{}\"", json_escape(s))).collect();
    format!("[{}]", parts.join(","))
}

/// 解析 form-urlencoded 为键值对。
pub fn parse_form(body: &str) -> Vec<(String, String)> {
    body.split('&')
        .filter(|p| !p.is_empty())
        .map(|p| {
            let mut it = p.splitn(2, '=');
            let k = percent_decode(it.next().unwrap_or(""));
            let v = percent_decode(it.next().unwrap_or(""));
            (k, v)
        })
        .collect()
}

pub fn mime_for(path: &str) -> &'static str {
    let ext = path.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    match ext.as_str() {
        "mp4" | "m4v" => "video/mp4",
        "mkv" => "video/x-matroska",
        "webm" => "video/webm",
        "mov" => "video/quicktime",
        "ts" => "video/mp2t",
        "avi" => "video/x-msvideo",
        "mp3" => "audio/mpeg",
        "srt" => "text/plain; charset=utf-8",
        "vtt" => "text/vtt; charset=utf-8",
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        _ => "application/octet-stream",
    }
}

/// 只暴露常见视频容器，避免管理页/APK 列表混入无关文件。
pub fn is_video(name: &str) -> bool {
    let ext = name.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    matches!(ext.as_str(), "mp4" | "m4v" | "mkv" | "webm" | "mov" | "ts" | "avi" | "mp3")
}

/// GUI 子系统下无控制台，执行外部命令必须带 CREATE_NO_WINDOW，否则会闪黑窗。
pub fn run_hidden(program: &str, args: &[&str]) -> Option<std::process::Output> {
    let mut cmd = std::process::Command::new(program);
    cmd.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    cmd.output().ok()
}

/// 与 `run_hidden` 相同，但通过 stdin 传入内容。
///
/// L2 用它把 Sunshine Web UI 凭据交给 `curl --config -`：凭据不会出现在
/// 进程命令行参数里（同机其他进程可通过命令行窥探），也不落临时文件。
pub fn run_hidden_with_stdin(program: &str, args: &[&str], stdin: &[u8]) -> Option<std::process::Output> {
    use std::io::Write;
    use std::process::{Command, Stdio};
    let mut cmd = Command::new(program);
    cmd.args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let mut child = cmd.spawn().ok()?;
    if let Some(mut pipe) = child.stdin.take() {
        let _ = pipe.write_all(stdin);
        // 必须显式关闭：curl --config - 要读到 EOF 才继续。
        drop(pipe);
    }
    child.wait_with_output().ok()
}

/// 用系统默认浏览器打开链接（管理页 / Sunshine Web UI / 下载页共用）。
pub fn open_browser(url: &str) {
    if run_hidden("cmd", &["/C", "start", "", url]).is_none() {
        log(&format!("打开浏览器失败（请手动访问 {}）", url));
    }
}
