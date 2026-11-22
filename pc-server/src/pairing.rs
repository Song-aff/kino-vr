//! L2：把 APP 上显示的 4 位 PIN 代发到 Sunshine Web UI 的 `/api/pin`。
//!
//! 为什么走系统 curl.exe：
//! - `pc-server` 是 Rust 标准库零依赖 + windows-gnu 构建，没有 TLS 客户端；
//! - Win11 自带 curl.exe，`-k` 可跳过 Sunshine 自签证书，`-u` 可带 Basic Auth；
//! - 不引入 rustls / reqwest，构建约束不变。
//!
//! 安全处理：
//! - 凭据通过 `curl --config -` 的 stdin 传入，不出现在命令行参数里；
//! - `--noproxy "*"` 避免本机代理 / Clash TUN 把 127.0.0.1 的请求劫走；
//! - 只使用录入的凭据，**不调用 `sunshine --creds`** 修改用户现有密码；
//! - 日志只记结果与 HTTP 码，从不记 PIN、用户名、密码或响应体。

use crate::credentials;
use crate::state::Shared;
use crate::util::{json_escape, log, run_hidden_with_stdin};

const PIN_URL: &str = "https://127.0.0.1:47990/api/pin";
/// Sunshine 2026.914 起 Web UI 有 CSRF 保护；默认放行 localhost 变体，
/// 这里显式声明来源，避免被当成跨源状态变更请求拒绝。
const ORIGIN: &str = "https://localhost:47990";

pub struct PairResult {
    pub ok: bool,
    pub code: u16,
    pub message: String,
    pub detail: String,
}

impl PairResult {
    pub fn json(&self) -> String {
        format!(
            "{{\"ok\":{},\"code\":{},\"message\":\"{}\",\"detail\":\"{}\"}}",
            self.ok,
            self.code,
            json_escape(&self.message),
            json_escape(&self.detail)
        )
    }
}

fn fail(code: u16, message: &str, detail: &str) -> PairResult {
    PairResult {
        ok: false,
        code,
        message: message.to_string(),
        detail: detail.chars().take(240).collect(),
    }
}

/// 提交一次配对，并把结果写入共享状态供 `/api/status` 展示。
pub fn submit_pin(shared: &Shared, pin: &str) -> PairResult {
    let result = submit(pin);
    let json = result.json();
    *shared.pair_result.lock().unwrap() = json.clone();
    // 只记结果，绝不记 PIN / 凭据 / 响应体。
    log(&format!(
        "PIN 代发: ok={} http={} {}",
        result.ok, result.code, result.message
    ));
    result
}

fn submit(pin: &str) -> PairResult {
    if pin.len() != 4 || !pin.bytes().all(|b| b.is_ascii_digit()) {
        return fail(0, "配对码必须是 4 位数字", "");
    }
    let Some(creds) = credentials::load() else {
        return fail(0, "请先在管理页录入 Sunshine Web UI 凭据", "");
    };

    // curl config 语法：引号内的 \ 与 " 需要转义，换行已在 save 时剔除。
    let config = format!(
        "user = \"{}:{}\"\n",
        credentials::curl_escape(&creds.user),
        credentials::curl_escape(&creds.pass)
    );
    let body = format!("{{\"pin\":\"{}\",\"name\":\"VRPlayer\"}}", pin);
    let ctype = "Content-Type: application/json";
    let origin_header = format!("Origin: {}", ORIGIN);
    let write_out = "\n__VR_HTTP__%{http_code}";
    let args = [
        "-s", "-k", "--noproxy", "*", "--connect-timeout", "3", "--max-time", "10",
        "-K", "-", "-X", "POST",
        "-H", ctype, "-H", origin_header.as_str(),
        "-d", body.as_str(), "-w", write_out, PIN_URL,
    ];

    let Some(out) = run_hidden_with_stdin("curl", &args, config.as_bytes()) else {
        return fail(0, "调用系统 curl.exe 失败（Win11 应自带，请检查 PATH）", "");
    };
    let text = String::from_utf8_lossy(&out.stdout).into_owned();
    let (response_body, code) = match text.rsplit_once("__VR_HTTP__") {
        Some((b, c)) => (b.trim(), c.trim().parse::<u16>().unwrap_or(0)),
        None => (text.trim(), 0),
    };
    let mut detail: String = response_body.chars().take(240).collect();
    if code == 0 && detail.is_empty() {
        detail = String::from_utf8_lossy(&out.stderr).chars().take(240).collect();
    }

    match code {
        200 | 201 => {
            // Sunshine 成功返回 JSON status:true；失败多为 {"status":false,...}。
            let rejected = response_body.contains("\"status\":false")
                || response_body.contains("\"status\": false");
            if rejected {
                fail(code, "Sunshine 拒绝了配对（PIN 已过期 / 已有配对在进行）", &detail)
            } else {
                PairResult {
                    ok: true,
                    code,
                    message: "配对码已提交，APP 端应已完成配对".to_string(),
                    detail,
                }
            }
        }
        401 => fail(code, "Web UI 凭据不正确，请重新录入", &detail),
        403 => fail(
            code,
            "被 CSRF 拦截：请在 sunshine.conf 的 csrf_allowed_origins 加入 http://127.0.0.1:8787 并重启 Sunshine",
            &detail,
        ),
        0 => fail(code, "无法连接 Sunshine Web UI（未运行 / 端口不通 / curl 异常）", &detail),
        other => fail(
            other,
            &format!("Sunshine 返回 HTTP {}", other),
            &detail,
        ),
    }
}
