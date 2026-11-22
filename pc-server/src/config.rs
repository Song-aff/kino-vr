use crate::util::{app_dir, log};
use std::path::PathBuf;

#[derive(Clone, Debug)]
pub struct Config {
    pub root: PathBuf,
    pub name: String,
    /// 手动指定的广播 IP；空串表示用自动探测到的局域网 IP。
    pub advertise_ip: String,
    /// 串流会话开始时自动静音本机主音量、结束时恢复。
    pub auto_mute: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            root: default_root(), name: default_name(),
            advertise_ip: String::new(), auto_mute: true,
        }
    }
}

fn default_root() -> PathBuf {
    // 优先「视频」库，其次用户主目录，最后 exe 所在目录。
    if let Ok(home) = std::env::var("USERPROFILE") {
        let videos = PathBuf::from(&home).join("Videos");
        if videos.is_dir() { return videos; }
        let home_path = PathBuf::from(&home);
        if home_path.is_dir() { return home_path; }
    }
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
}

fn default_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "我的电脑".to_string())
}

fn file() -> PathBuf { app_dir().join("config.txt") }

pub fn load() -> Config {
    let mut config = Config::default();
    let Ok(text) = std::fs::read_to_string(file()) else { return config };
    for line in text.lines() {
        let (key, value) = match line.split_once('=') {
            Some(pair) => pair,
            None => continue,
        };
        match key.trim() {
            "root" => {
                let path = PathBuf::from(value.trim());
                if path.is_dir() { config.root = path; }
            }
            "name" => {
                let name = value.trim().to_string();
                if !name.is_empty() { config.name = sanitize_name(&name); }
            }
            "ip" => {
                let ip = value.trim();
                if ip.is_empty() || ip.parse::<std::net::Ipv4Addr>().is_ok() {
                    config.advertise_ip = ip.to_string();
                }
            }
            "automute" => config.auto_mute = value.trim() != "0",
            _ => {}
        }
    }
    config
}

pub fn save(config: &Config) -> std::io::Result<()> {
    let text = format!(
        "root={}\nname={}\nip={}\nautomute={}\n",
        config.root.display(), sanitize_name(&config.name), config.advertise_ip,
        if config.auto_mute { 1 } else { 0 }
    );
    std::fs::write(file(), text)
}

/// 广播包用 '|' 分隔，名称里的分隔符必须剔除。
pub fn sanitize_name(name: &str) -> String {
    name.chars()
        .filter(|c| *c != '|' && *c != '\n' && *c != '\r')
        .take(48)
        .collect()
}

pub fn reload_or_default() -> Config {
    let c = load();
    if !c.root.is_dir() {
        log(&format!("配置目录不存在，回退默认: {}", c.root.display()));
        let mut fallback = Config::default();
        fallback.name = c.name;
        fallback.advertise_ip = c.advertise_ip;
        fallback.auto_mute = c.auto_mute;
        return fallback;
    }
    c
}
