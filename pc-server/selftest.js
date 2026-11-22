/**
 * PC 端片源服务回归自测（零依赖，Node 18+）。
 * 前置：先启动 dist/VR片源服务.exe（或 --no-browser）。
 * 用法：node pc-server/selftest.js
 *
 * 覆盖：管理页 / 状态 / 防火墙状态 / Sunshine 串流状态 / L2 能力代理（会话态、凭据不外泄、
 *       版本感知输入后端、PIN 参数校验、47989 只读、47990 未授权） / 递归片源列表 /
 *       HTTP HEAD 200 / Range 206 / 416 / 路径穿越 403 / 子目录相对路径 / UDP 设备发现（含片源数）。
 *
 * 注意：全量请求一律用 HEAD，绝不把真实大文件读进内存。
 */
const http = require('http');
const https = require('https');
const dgram = require('dgram');

const ADMIN = 'http://127.0.0.1:8787';
let pass = 0, fail = 0;

function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
  ok ? pass++ : fail++;
}

function get(url, headers = {}, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = http.get(url, { headers, method, timeout: 8000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}

/** 与 APK 端 LanService.urlFor 一致：按段编码，保留 '/' 作为路径分隔符。 */
function encodePath(rel) { return rel.split('/').map(encodeURIComponent).join('/'); }

/** form-urlencoded POST（L2 的配对码 / 凭据接口走这种请求体）。 */
function post(url, params = {}) {
  return new Promise((resolve, reject) => {
    const body = new URLSearchParams(params).toString();
    const opts = {
      method: 'POST', timeout: 8000,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body) },
    };
    const req = http.request(url, opts, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.end(body);
  });
}

/** Sunshine Web UI 是自签证书，这里只探 HTTP 状态码，不验证证书链。 */
function getTls(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { rejectUnauthorized: false, timeout: 5000 }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}

(async () => {
  let status;
  try {
    const page = await get(ADMIN + '/');
    check('管理页可访问', page.status === 200 && page.body.includes('VR 片源服务'));

    const raw = await get(ADMIN + '/api/status');
    status = JSON.parse(raw.body.toString());
    check('状态接口', raw.status === 200 && status.running === true);
    check('局域网 IP 为私有地址', /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(status.ip),
      `ip=${status.ip}`);
    check('防火墙状态字段', status.firewall && typeof status.firewall.admin === 'boolean'
      && typeof status.firewall.applied === 'boolean',
      `admin=${status.firewall?.admin} applied=${status.firewall?.applied}`);
    check('自动静音开关状态字段', typeof status.autoMute === 'boolean'
      && typeof status.mutedByUs === 'boolean',
      `autoMute=${status.autoMute} mutedByUs=${status.mutedByUs}`);
    const restore = await get(ADMIN + '/api/restore-volume', {}, 'POST');
    check('恢复音量接口', restore.status === 200, `status=${restore.status}`);

    const ssRes = await get(ADMIN + '/api/sunshine');
    const ss = JSON.parse(ssRes.body.toString());
    check('Sunshine 状态接口', ssRes.status === 200 && typeof ss.installed === 'boolean'
      && typeof ss.running === 'boolean' && typeof ss.vigem === 'boolean'
      && typeof ss.portsOpen === 'boolean' && typeof ss.hint === 'string' && ss.hint.length > 0,
      `installed=${ss.installed} running=${ss.running} ports=${ss.portsOpen}`);
    check('状态接口内嵌串流端口范围', status.sunshine
      && status.sunshine.tcpRange === '47984-48010'
      && status.sunshine.udpRange === '47998-48010',
      `tcp=${status.sunshine?.tcpRange} udp=${status.sunshine?.udpRange}`);

    // ---- L2 能力代理：只读状态 / 凭据不外泄 / 参数校验 / 外部端口 ----
    check('L2 会话状态与凭据标记',
      typeof status.sunshine?.session === 'string'
      && typeof status.sunshine?.credsSet === 'boolean'
      && (status.sunshine?.pairResult === null || typeof status.sunshine?.pairResult === 'object'),
      `session=${status.sunshine?.session} credsSet=${status.sunshine?.credsSet}`);
    check('L2 凭据不出现在状态响应里',
      !('user' in status.sunshine) && !('pass' in status.sunshine)
      && !raw.body.toString().includes('sunshine-creds'),
      '仅暴露 credsSet 布尔值');
    check('L2 版本感知输入后端',
      typeof ss.modern === 'boolean' && typeof ss.version === 'string'
      && typeof ss.inputBackend === 'string' && typeof ss.inputHint === 'string',
      `version=${ss.version} modern=${ss.modern} backend=${ss.inputBackend}`);
    const badPin = await post(ADMIN + '/api/sunshine/pair', { pin: '12x4' });
    const badPinBody = JSON.parse(badPin.body.toString());
    check('L2 配对码参数校验',
      badPin.status === 200 && badPinBody.ok === false && badPinBody.code === 0
      && /4 位/.test(badPinBody.message),
      `status=${badPin.status} msg=${badPinBody.message}`);

    // 只在 Sunshine 实际运行时探外部端口，无 Sunshine 的机器不误报失败。
    if (ss.running) {
      const info = await get('http://127.0.0.1:47989/serverinfo');
      check('L2 明文 47989 /serverinfo 只读可达',
        info.status === 200 && info.body.toString().includes('<currentgame>'),
        `status=${info.status}`);
      const pinPage = await getTls('https://127.0.0.1:47990/api/pin');
      check('L2 Web UI 47990 未授权被拒',
        pinPage.status === 401 || pinPage.status === 403, `status=${pinPage.status}`);
    } else {
      console.log('SKIP  Sunshine 外部端口探测（本机 Sunshine 未运行）');
    }

    const media = `http://127.0.0.1:${status.mediaPort}`;
    const list = JSON.parse((await get(media + '/api/list')).body.toString());
    check('片源列表接口', Array.isArray(list) && list.length === status.fileCount,
      `${list.length} 个片源`);

    const nested = list.filter((f) => f.includes('/'));
    check('递归列出子目录片源', nested.length > 0,
      nested.length ? `含 ${nested.length} 个子目录文件，样例 ${nested[0]}` : '（该目录没有子目录片源）');

    if (list.length) {
      const target = encodePath(list[0]);
      const head = await get(media + '/' + target, {}, 'HEAD');
      check('HTTP HEAD 200 + Accept-Ranges', head.status === 200 && !!head.headers['accept-ranges'],
        `content-length=${head.headers['content-length']}`);

      const partial = await get(media + '/' + target, { Range: 'bytes=0-99' });
      check('HTTP 206 + Content-Range',
        partial.status === 206 && /^bytes 0-\d+\/\d+$/.test(partial.headers['content-range'] || '') && partial.body.length === 100,
        partial.headers['content-range'] || '');

      const bad = await get(media + '/' + target, { Range: 'bytes=999999999999-' });
      check('越界 416', bad.status === 416);

      if (nested.length) {
        const sub = await get(media + '/' + encodePath(nested[0]), { Range: 'bytes=0-99' });
        check('子目录相对路径可取片', sub.status === 206 && sub.body.length === 100, nested[0]);
      }
    } else {
      console.log('SKIP  Range 相关（片源目录为空）');
    }

    const traversal = await get(media + '/..%2F..%2Fsecret.txt');
    check('路径穿越 403', traversal.status === 403);
  } catch (e) {
    check('HTTP 接口组', false, e.message);
  }

  // UDP 主动探测：APK 走的就是这条路径（广播可能被路由器抑制，单播应答必须可用）。
  await new Promise((resolve) => {
    const sock = dgram.createSocket('udp4');
    let done = false;
    const finish = (ok, detail) => {
      if (done) return;
      done = true;
      check('UDP 设备发现 PROBE → ANNOUNCE', ok, detail);
      try { sock.close(); } catch (_) {}
      resolve();
    };
    sock.on('message', (msg) => {
      const text = msg.toString();
      if (text.startsWith('VRPLAYER_V1|ANNOUNCE')) {
        const parts = text.split('|');
        // 必须用包内 IP，不能用 UDP 源地址（VPN/虚拟网卡会污染源地址）。
        // v0.3 第 7 段是片源数，应与 /api/status 的 fileCount 一致。
        const ok = parts.length >= 7 && /^\d+\.\d+\.\d+\.\d+$/.test(parts[3])
          && Number(parts[6]) === status?.fileCount;
        finish(ok, text);
      }
    });
    sock.on('error', () => finish(false, 'socket error'));
    sock.bind(0, () => {
      sock.setBroadcast(true);
      sock.send(Buffer.from('VRPLAYER_V1|PROBE'), status?.discoveryPort || 47821, '255.255.255.255');
    });
    setTimeout(() => finish(false, '3s 内无应答'), 3000);
  });

  console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
  process.exit(fail ? 1 : 0);
})();
