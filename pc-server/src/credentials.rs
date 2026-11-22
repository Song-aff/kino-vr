//! Sunshine Web UI 凭据的本地持久化（L2）。
//!
//! 边界：
//! - 只存本机 `%APPDATA%\VRPlayerServer\sunshine-creds.txt`，绝不写日志、
//!   绝不出现在任何 API 响应里（对外只暴露 `credsSet` 布尔值）；
//! - 用户可随时在管理页「清除凭据」删除文件；
//! - **不调用 `sunshine --creds`**，也就是绝不修改用户已装 Sunshine 的现有密码。
//!
//! 已知取舍：文件为明文。理由是本程序监听 127.0.0.1 且只在本机使用，
//! 引入 DPAPI / CryptoAPI 会打破「Rust 标准库零依赖 + windows-gnu 可编译」的约束。
//! 若后续要对外分发，这一步应优先换成 Windows DPAPI。

use crate::util::app_dir;
use std::fs;
use std::io;
use std::path::PathBuf;

const FILE: &str = "sunshine-creds.txt";

pub struct Credentials {
    pub user: String,
    pub pass: String,
}

fn file() -> PathBuf { app_dir().join(FILE) }

pub fn is_set() -> bool { load().is_some() }

pub fn load() -> Option<Credentials> {
    let text = fs::read_to_string(file()).ok()?;
    let mut user = String::new();
    let mut pass = String::new();
    for line in text.lines() {
        if let Some((k, v)) = line.split_once('=') {
            match k.trim() {
                "user" => user = v.trim().to_string(),
                "pass" => pass = v.to_string(),
                _ => {}
            }
        }
    }
    if user.is_empty() || pass.is_empty() { None } else { Some(Credentials { user, pass }) }
}

pub fn save(user: &str, pass: &str) -> io::Result<()> {
    let clean_user: String = user.trim().chars().filter(|c| *c != '\r' && *c != '\n').collect();
    let clean_pass: String = pass.chars().filter(|c| *c != '\r' && *c != '\n').collect();
    if clean_user.is_empty() || clean_pass.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "用户名或密码为空"));
    }
    fs::write(file(), format!("user={}\npass={}\n", clean_user, clean_pass))
}

pub fn clear() -> io::Result<()> {
    let path = file();
    if path.exists() { fs::remove_file(path) } else { Ok(()) }
}

/// curl config 文件里的值转义：反斜杠与双引号必须转义，换行已在 save 时剔除。
pub fn curl_escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 4);
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            c => out.push(c),
        }
    }
    out
}
