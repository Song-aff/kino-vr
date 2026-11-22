use crate::config;
use crate::state::Shared;
use crate::util::{epoch_millis, log};
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// 双方约定的固定发现端口与特殊标识。版本号便于以后协议升级时互不干扰。
pub const DISCOVERY_PORT: u16 = 47821;
pub const MAGIC: &str = "VRPLAYER_V1";

pub fn announce(shared: &Shared) -> String {
    let (name, ip) = {
        let cfg = shared.config.lock().unwrap();
        let ip = if cfg.advertise_ip.is_empty() { shared.auto_ip.clone() } else { cfg.advertise_ip.clone() };
        (config::sanitize_name(&cfg.name), ip)
    };
    // 第 7 段是片源数（v0.3 新增，旧版 APK 只取前 6 段，向后兼容）。
    format!(
        "{}|ANNOUNCE|{}|{}|{}|{}|{}",
        MAGIC, name, ip, shared.media_port, epoch_millis(),
        shared.file_count.load(Ordering::Relaxed)
    )
}

/// 绑定到真实局域网网卡的发送 socket。
///
/// PC 装了 Clash 之类 TUN 虚拟网卡时，metric 0 的默认路由会抢走从 0.0.0.0
/// 发出的 UDP 回包，导致「手机 PROBE 收得到、PC 应答手机收不到」的单向不通。
/// 把源地址固定为 WLAN IP 可强制 Windows 从该网卡发包。绑定失败则退回主 socket。
fn bind_lan_sender(shared: &Shared) -> Option<UdpSocket> {
    let ip = shared.advertise_ip();
    match UdpSocket::bind((ip.as_str(), 0)) {
        Ok(s) => {
            let _ = s.set_broadcast(true);
            log(&format!("发现包同时经网卡 {} 发送（绕过 TUN 默认路由）", ip));
            Some(s)
        }
        Err(e) => {
            log(&format!("绑定网卡 {} 失败，仅用主 socket: {}", ip, e));
            None
        }
    }
}

/// 绑定发现端口：周期性广播 + 响应 APK 的主动探测（单播应答）。
/// 只使用 UDP broadcast，不使用 multicast —— 后者会让 Android 端被迫申请额外权限。
pub fn start(shared: Shared) -> io::Result<()> {
    let socket = Arc::new(UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT))?);
    socket.set_broadcast(true)?;
    socket.set_read_timeout(Some(Duration::from_millis(400)))?;
    log(&format!("UDP 发现服务已监听 0.0.0.0:{}", DISCOVERY_PORT));

    let lan_tx = Arc::new(bind_lan_sender(&shared));

    // 广播线程：全局广播 + 定向 /24 广播，并额外用绑定网卡的 socket 重发一次。
    let tx = socket.clone();
    let lan = lan_tx.clone();
    let st = shared.clone();
    thread::spawn(move || loop {
        let packet = announce(&st).into_bytes();
        let global = SocketAddr::from(([255, 255, 255, 255], DISCOVERY_PORT));
        let _ = tx.send_to(&packet, global);
        if let Ok(a) = st.advertise_ip().parse::<std::net::Ipv4Addr>() {
            let o = a.octets();
            let directed = SocketAddr::from(([o[0], o[1], o[2], 255], DISCOVERY_PORT));
            let _ = tx.send_to(&packet, directed);
            if let Some(s) = lan.as_ref() {
                let _ = s.send_to(&packet, directed);
            }
        }
        thread::sleep(Duration::from_secs(2));
    });

    // 接收线程：APK 发 PROBE 时立即单播回复，不必等下一个广播周期。
    let mut buf = [0u8; 1024];
    let mut last_log = std::time::Instant::now();
    loop {
        match socket.recv_from(&mut buf) {
            Ok((len, src)) => {
                let msg = String::from_utf8_lossy(&buf[..len]);
                if msg.trim() == format!("{}|PROBE", MAGIC) {
                    let reply = announce(&shared).into_bytes();
                    let mut ok = false;
                    match socket.send_to(&reply, src) {
                        Ok(_) => ok = true,
                        Err(e) => log(&format!("应答 PROBE 失败 {}: {}", src, e)),
                    }
                    // 冗余单播：源地址固定为真实网卡 IP，避开 TUN 劫持。
                    if let Some(s) = lan_tx.as_ref() {
                        if s.send_to(&reply, src).is_ok() { ok = true; }
                    }
                    if ok && last_log.elapsed() > Duration::from_secs(5) {
                        log(&format!("收到 PROBE 来自 {}，已单播应答", src));
                        last_log = std::time::Instant::now();
                    }
                }
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => {}
            // Windows 会把「广播目标不监听该端口」的 ICMP 不可达报成 WSAECONNRESET，
            // 属于局域网扫描的正常噪音，忽略即可，避免每轮广播都刷日志。
            Err(e) if e.raw_os_error() == Some(10054) => {}
            Err(e) => log(&format!("UDP 接收异常: {}", e)),
        }
    }
}
