use crate::config;
use crate::firewall;
use crate::state::{Shared, DEFAULT_MEDIA_PORT};
use crate::sunshine;
use crate::util::{
    is_video, json_escape, json_string_array, log, mime_for, open_browser, parse_form,
    percent_decode,
};
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::thread;

const UI_HTML: &str = include_str!("ui.html");
/// 递归扫描上限：避免误选整个盘符时一次拉回上万条目。
const MAX_LIST_FILES: usize = 3000;

struct Request {
    method: String,
    raw_target: String,
    headers: HashMap<String, String>,
    body: String,
}

fn read_request(stream: &mut TcpStream) -> io::Result<Option<Request>> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut line = String::new();
    if reader.read_line(&mut line)? == 0 { return Ok(None); }
    let mut parts = line.trim_end().split_whitespace();
    let method = parts.next().unwrap_or("GET").to_string();
    let raw_target = parts.next().unwrap_or("/").to_string();
    let mut headers = HashMap::new();
    loop {
        let mut header = String::new();
        if reader.read_line(&mut header)? == 0 { break; }
        if header == "\r\n" || header == "\n" || header.trim().is_empty() { break; }
        if let Some((k, v)) = header.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }
    let len = headers.get("content-length").and_then(|v| v.parse::<usize>().ok()).unwrap_or(0);
    let mut body = vec![0u8; len.min(64 * 1024)];
    if !body.is_empty() { reader.read_exact(&mut body)?; }
    Ok(Some(Request { method, raw_target, headers, body: String::from_utf8_lossy(&body).into_owned() }))
}

fn header(stream: &mut TcpStream, status: &str, content_type: &str, extra: &[(&str, String)]) -> io::Result<()> {
    let mut text = format!("HTTP/1.1 {}\r\nContent-Type: {}\r\n", status, content_type);
    for (k, v) in extra { text.push_str(&format!("{}: {}\r\n", k, v)); }
    text.push_str("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
    stream.write_all(text.as_bytes())
}

fn send_text(stream: &mut TcpStream, status: &str, body: &str, content_type: &str) -> io::Result<()> {
    header(stream, status, content_type, &[("Content-Length", body.len().to_string())])?;
    stream.write_all(body.as_bytes())
}

fn query_value(target: &str, key: &str) -> Option<String> {
    let (_, query) = target.split_once('?')?;
    for pair in query.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            if k == key { return Some(percent_decode(v)); }
        }
    }
    None
}

fn path_only(target: &str) -> &str { target.split('?').next().unwrap_or("/") }

// ---------------------------------------------------------------- 管理页 API

pub fn serve_admin(shared: Shared, listener: TcpListener) {
    for conn in listener.incoming() {
        let Ok(mut stream) = conn else { continue };
        let st = shared.clone();
        thread::spawn(move || { let _ = handle_admin(&mut stream, &st); });
    }
}

fn handle_admin(stream: &mut TcpStream, shared: &Shared) -> io::Result<()> {
    let Some(req) = read_request(stream)? else { return Ok(()) };
    let path = path_only(&req.raw_target).to_string();
    match (req.method.as_str(), path.as_str()) {
        ("GET", "/") | ("GET", "/index.html") => {
            send_text(stream, "200 OK", UI_HTML, "text/html; charset=utf-8")
        }
        ("GET", "/api/status") => send_text(stream, "200 OK", &status_json(shared), "application/json; charset=utf-8"),
        ("GET", "/api/files") => send_text(stream, "200 OK", &json_string_array(&list_videos(&current_root(shared))), "application/json; charset=utf-8"),
        ("GET", "/api/browse") => {
            let requested = query_value(&req.raw_target, "path");
            send_text(stream, "200 OK", &browse_json(shared, requested), "application/json; charset=utf-8")
        }
        ("POST", "/api/config") => {
            let form = parse_form(&req.body);
            let mut cfg = shared.config.lock().unwrap().clone();
            let mut problem: Option<String> = None;
            for (k, v) in form {
                match k.as_str() {
                    "root" => {
                        let p = PathBuf::from(v.trim());
                        if p.is_dir() { cfg.root = p; }
                        else { problem = Some(format!("目录不存在：{}", v)); }
                    }
                    "name" => {
                        let n = config::sanitize_name(v.trim());
                        if !n.is_empty() { cfg.name = n; }
                    }
                    "ip" => {
                        let ip = v.trim();
                        if ip.is_empty() { cfg.advertise_ip = String::new(); }
                        else if ip.parse::<std::net::Ipv4Addr>().is_ok() { cfg.advertise_ip = ip.to_string(); }
                        else { problem = Some(format!("IP 格式无效：{}", ip)); }
                    }
                    "automute" => cfg.auto_mute = v.trim() != "0",
                    _ => {}
                }
            }
            if let Some(msg) = problem {
                return send_text(stream, "400 Bad Request", &format!("{{\"error\":\"{}\"}}", json_escape(&msg)), "application/json; charset=utf-8");
            }
            if let Err(e) = config::save(&cfg) { log(&format!("保存配置失败: {}", e)); }
            let auto_mute = cfg.auto_mute;
            let summary = format!("配置已更新: {} / {}", cfg.root.display(), cfg.name);
            *shared.config.lock().unwrap() = cfg;
            // 关掉开关时立即恢复，不等当前串流会话结束。
            if !auto_mute { crate::streamwatch::restore(shared); }
            refresh_file_count(shared);
            log(&summary);
            send_text(stream, "200 OK", &status_json(shared), "application/json; charset=utf-8")
        }
        ("POST", "/api/fix-firewall") => {
            // 管理员运行的服务直接用当前令牌重建规则；否则提权启动自身子进程（弹一次 UAC）。
            let st = if firewall::is_admin() {
                firewall::apply(DEFAULT_MEDIA_PORT, crate::discovery::DISCOVERY_PORT)
            } else {
                firewall::elevate_and_fix()
            };
            shared.set_firewall(st);
            send_text(stream, "200 OK", &status_json(shared), "application/json; charset=utf-8")
        }
        ("GET", "/api/sunshine") => send_text(stream, "200 OK", &sunshine_json(shared), "application/json; charset=utf-8"),
        ("POST", "/api/sunshine/open") => {
            // 未运行时不弹浏览器（打开也是失败页），把状态与提示交回管理页。
            let st = sunshine::detect(&shared.firewall.lock().unwrap().clone());
            if st.running { open_browser(&st.web_url); }
            send_text(stream, "200 OK", &st.json(), "application/json; charset=utf-8")
        }
        ("POST", "/api/sunshine/guide") => {
            let form = parse_form(&req.body);
            let target = form.iter().find(|(k, _)| k == "target").map(|(_, v)| v.as_str()).unwrap_or("");
            // 版本感知：2026.906+ 不再引导安装 ViGEmBus，改看 Virtual HID Driver 说明。
            let url = match target {
                "vigem" => sunshine::VIGEM_URL,
                "input" => {
                    let fw = shared.firewall.lock().unwrap().clone();
                    sunshine::input_guide_url(sunshine::detect(&fw).modern)
                }
                _ => sunshine::DOWNLOAD_URL,
            };
            open_browser(url);
            send_text(stream, "200 OK", "{\"ok\":true}", "application/json; charset=utf-8")
        }
        ("POST", "/api/sunshine/pair") => {
            // L2：把 APP 上显示的 4 位 PIN 代发给 Sunshine Web UI。
            let form = parse_form(&req.body);
            let pin = form.iter().find(|(k, _)| k == "pin").map(|(_, v)| v.trim()).unwrap_or("");
            let result = crate::pairing::submit_pin(shared, pin);
            send_text(stream, "200 OK", &result.json(), "application/json; charset=utf-8")
        }
        ("POST", "/api/sunshine/credentials") => {
            let form = parse_form(&req.body);
            let user = form.iter().find(|(k, _)| k == "user").map(|(_, v)| v.as_str()).unwrap_or("");
            let pass = form.iter().find(|(k, _)| k == "pass").map(|(_, v)| v.as_str()).unwrap_or("");
            match crate::credentials::save(user, pass) {
                Ok(_) => {
                    // 只记事件，绝不记用户名 / 密码。
                    log("Sunshine Web UI 凭据已保存（内容不记录）");
                    send_text(stream, "200 OK", &status_json(shared), "application/json; charset=utf-8")
                }
                Err(e) => send_text(stream, "400 Bad Request",
                    &format!("{{\"error\":\"{}\"}}", json_escape(&e.to_string())),
                    "application/json; charset=utf-8"),
            }
        }
        ("POST", "/api/sunshine/credentials/clear") => {
            let _ = crate::credentials::clear();
            log("Sunshine Web UI 凭据已清除");
            send_text(stream, "200 OK", &status_json(shared), "application/json; charset=utf-8")
        }
        ("POST", "/api/restore-volume") => {
            crate::streamwatch::restore(shared);
            send_text(stream, "200 OK", &status_json(shared), "application/json; charset=utf-8")
        }
        ("POST", "/api/quit") => {
            let _ = send_text(stream, "200 OK", "{\"ok\":true}", "application/json; charset=utf-8");
            crate::streamwatch::restore(shared);
            log("用户从管理页停止服务");
            std::thread::sleep(std::time::Duration::from_millis(150));
            std::process::exit(0);
        }
        _ => send_text(stream, "404 Not Found", "Not found", "text/plain; charset=utf-8"),
    }
}

/// 先克隆出 owned 值再释放锁，避免嵌套调用重复加锁导致死锁。
fn current_root(shared: &Shared) -> PathBuf {
    shared.config.lock().unwrap().root.clone()
}

fn status_json(shared: &Shared) -> String {
    let (name, root, manual_ip, auto_mute) = {
        let cfg = shared.config.lock().unwrap();
        (cfg.name.clone(), cfg.root.clone(), cfg.advertise_ip.clone(), cfg.auto_mute)
    };
    let muted_by_us = shared.muted_by_us.load(Ordering::Relaxed);
    let files = list_videos(&root);
    // 管理页每次刷新顺带更新广播包里的片源数，配置变更后也能及时同步。
    shared.file_count.store(files.len(), Ordering::Relaxed);
    let fw_status = shared.firewall.lock().unwrap().clone();
    let fw = fw_status.json();
    let sunshine = sunshine_json(shared);
    let ip = if manual_ip.is_empty() { shared.auto_ip.clone() } else { manual_ip.clone() };
    let url = format!("http://{}:{}/", ip, shared.media_port);
    format!(
        "{{\"running\":true,\"name\":\"{}\",\"root\":\"{}\",\"ip\":\"{}\",\"manualIp\":\"{}\",\"candidates\":{},\"mediaPort\":{},\"discoveryPort\":{},\"url\":\"{}\",\"fileCount\":{},\"files\":{},\"firewall\":{},\"sunshine\":{},\"autoMute\":{},\"mutedByUs\":{}}}",
        json_escape(&name),
        json_escape(&root.to_string_lossy()),
        json_escape(&ip),
        json_escape(&manual_ip),
        json_string_array(&shared.auto_candidates),
        shared.media_port,
        crate::discovery::DISCOVERY_PORT,
        json_escape(&url),
        files.len(),
        json_string_array(&files),
        fw,
        sunshine,
        auto_mute,
        muted_by_us
    )
}

/// 在只读探测结果上叠加 L2 运行态：会话状态、最近一次 PIN 结果、是否已录凭据。
/// 凭据本身绝不出现在响应里，只给布尔值。
fn sunshine_json(shared: &Shared) -> String {
    let fw = shared.firewall.lock().unwrap().clone();
    let base = sunshine::detect(&fw).json();
    let session = shared.session.lock().unwrap().clone();
    let pair_result = shared.pair_result.lock().unwrap().clone();
    let creds_set = crate::credentials::is_set();
    let extra = format!(
        ",\"session\":\"{}\",\"pairResult\":{},\"credsSet\":{}",
        json_escape(&session), pair_result, creds_set
    );
    // base 以 '}' 结尾；去掉后追加字段再封口，保持合法 JSON 对象。
    format!("{}{}}}", base.trim_end_matches('}'), extra)
}

fn browse_json(shared: &Shared, requested: Option<String>) -> String {
    let current = requested
        .filter(|p| !p.trim().is_empty())
        .map(PathBuf::from)
        .filter(|p| p.is_dir())
        .unwrap_or_else(|| shared.config.lock().unwrap().root.clone());
    let parent = current.parent().map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
    let mut dirs: Vec<String> = Vec::new();
    if let Ok(entries) = fs::read_dir(&current) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() && !is_hidden(&path) {
                if let Some(name) = path.file_name() {
                    dirs.push(name.to_string_lossy().into_owned());
                }
            }
        }
    }
    dirs.sort_by_key(|s| s.to_lowercase());
    let items: Vec<String> = dirs.iter().map(|n| {
        let full = current.join(n).to_string_lossy().into_owned();
        format!("{{\"name\":\"{}\",\"path\":\"{}\"}}", json_escape(n), json_escape(&full))
    }).collect();
    format!(
        "{{\"current\":\"{}\",\"parent\":\"{}\",\"dirs\":[{}]}}",
        json_escape(&current.to_string_lossy()),
        json_escape(&parent),
        items.join(",")
    )
}

fn is_hidden(path: &Path) -> bool {
    path.file_name()
        .map(|n| n.to_string_lossy().starts_with('.'))
        .unwrap_or(false)
}

// ---------------------------------------------------------------- 片源 HTTP Range 服务

pub fn serve_media(shared: Shared, listener: TcpListener) {
    for conn in listener.incoming() {
        let Ok(mut stream) = conn else { continue };
        let st = shared.clone();
        thread::spawn(move || { let _ = handle_media(&mut stream, &st); });
    }
}

fn handle_media(stream: &mut TcpStream, shared: &Shared) -> io::Result<()> {
    let Some(req) = read_request(stream)? else { return Ok(()) };
    let path = path_only(&req.raw_target).to_string();
    if path == "/api/list" {
        return send_text(stream, "200 OK", &json_string_array(&list_videos(&current_root(shared))), "application/json; charset=utf-8");
    }
    if path == "/api/status" {
        return send_text(stream, "200 OK", &status_json(shared), "application/json; charset=utf-8");
    }
    if path == "/" || path.is_empty() {
        return send_text(stream, "200 OK", "VR player source server", "text/plain; charset=utf-8");
    }
    if req.method != "GET" && req.method != "HEAD" {
        return send_text(stream, "405 Method Not Allowed", "Method not allowed", "text/plain; charset=utf-8");
    }
    let root = shared.config.lock().unwrap().root.clone();
    let Some(file) = resolve_file(&root, &path) else {
        return send_text(stream, "403 Forbidden", "Forbidden", "text/plain; charset=utf-8");
    };
    send_file(stream, &file, req.headers.get("range").map(|s| s.as_str()), req.method == "HEAD")
}

/// 路径穿越防护：canonicalize 后必须仍在根目录之下。
fn resolve_file(root: &Path, raw_path: &str) -> Option<PathBuf> {
    let decoded = percent_decode(raw_path);
    let rel = decoded.trim_start_matches('/').replace('\\', "/");
    if rel.contains("..") { return None; }
    let candidate = root.join(rel);
    let canon_root = root.canonicalize().ok()?;
    let canon = candidate.canonicalize().ok()?;
    if !canon.starts_with(&canon_root) || !canon.is_file() { return None; }
    Some(canon)
}

/// 启动时算一次片源数，供 UDP ANNOUNCE 使用（避免每 2 秒广播都扫盘）。
pub fn refresh_file_count(shared: &Shared) {
    let root = current_root(shared);
    shared.file_count.store(list_videos(&root).len(), Ordering::Relaxed);
}

/// 递归列出片源目录下所有视频，返回**相对路径**（如 `DG1112/abc.mp4`）。
/// APK 连接设备时拉这个列表并按路径拼 URL；服务端 resolve_file 已做 canonicalize 防穿越。
fn list_videos(root: &Path) -> Vec<String> {
    let mut files = Vec::new();
    walk_videos(root, root, 0, &mut files);
    files.sort_by_key(|s| s.to_lowercase());
    files
}

fn walk_videos(root: &Path, dir: &Path, depth: u8, out: &mut Vec<String>) {
    if depth > 6 || out.len() >= MAX_LIST_FILES { return; }
    let Ok(entries) = fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if is_hidden(&path) { continue; }
        if path.is_dir() {
            walk_videos(root, &path, depth + 1, out);
        } else if path.is_file() {
            let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            if is_video(&name) {
                let rel = path.strip_prefix(root).unwrap_or(&path)
                    .to_string_lossy().replace('\\', "/");
                out.push(rel);
            }
        }
        if out.len() >= MAX_LIST_FILES { break; }
    }
}

enum Range {
    Full,
    Slice(u64, u64),
    Invalid,
}

fn parse_range(value: Option<&str>, size: u64) -> Range {
    let Some(raw) = value else { return Range::Full };
    let Some(spec) = raw.trim().strip_prefix("bytes=") else { return Range::Invalid };
    let Some((a, b)) = spec.split_once('-') else { return Range::Invalid };
    if a.is_empty() && b.is_empty() { return Range::Invalid; }
    let (start, end) = if a.is_empty() {
        let Ok(suffix) = b.parse::<u64>() else { return Range::Invalid };
        if suffix == 0 || size == 0 { return Range::Invalid; }
        (size.saturating_sub(suffix), size - 1)
    } else {
        let Ok(s) = a.parse::<u64>() else { return Range::Invalid };
        let e = if b.is_empty() { size.saturating_sub(1) } else { b.parse::<u64>().unwrap_or(u64::MAX) };
        (s, e.min(size.saturating_sub(1)))
    };
    if start > end || start >= size { Range::Invalid } else { Range::Slice(start, end) }
}

fn send_file(stream: &mut TcpStream, path: &Path, range_header: Option<&str>, head_only: bool) -> io::Result<()> {
    let size = fs::metadata(path)?.len();
    let ctype = mime_for(&path.to_string_lossy());
    match parse_range(range_header, size) {
        Range::Invalid => {
            let cr = format!("bytes */{}", size);
            header(stream, "416 Range Not Satisfiable", "text/plain; charset=utf-8",
                &[("Content-Range", cr), ("Content-Length", "0".to_string()), ("Accept-Ranges", "bytes".to_string())])
        }
        Range::Full => {
            header(stream, "200 OK", ctype,
                &[("Content-Length", size.to_string()), ("Accept-Ranges", "bytes".to_string())])?;
            if head_only { return Ok(()); }
            let mut file = File::open(path)?;
            io::copy(&mut file, stream).map(|_| ())
        }
        Range::Slice(start, end) => {
            let len = end - start + 1;
            let cr = format!("bytes {}-{}/{}", start, end, size);
            header(stream, "206 Partial Content", ctype,
                &[("Content-Range", cr), ("Content-Length", len.to_string()), ("Accept-Ranges", "bytes".to_string())])?;
            if head_only { return Ok(()); }
            let mut file = File::open(path)?;
            file.seek(SeekFrom::Start(start))?;
            io::copy(&mut file.take(len), stream).map(|_| ())
        }
    }
}
