package dev.astriavr.player;

import com.limelight.nvstream.jni.MoonBridge;

import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.SSLContext;

/**
 * S3b 阶段 3a：Sunshine / Moonlight 的 HTTPS 起流协商。
 *
 * <p>对照官方 {@code NvHTTP.launchApp()}：{@code /launch} 必须走 HTTPS 47984，
 * 携带配对时登记的客户端证书，并把本次会话的 AES-128 输入加密密钥（rikey）
 * 与 rikeyid 告诉服务端；返回的 {@code gamesession=1} 表示会话已建立，
 * {@code sessionUrl0} 是 RTSP 会话 URL（Sunshine 回的是 127.0.0.1，
 * 实际数据通道仍由 startConnection 的 address 参数决定，不能拿它当目标 IP）。</p>
 *
 * <p>本机 Sunshine 7.1.431 实测：{@code /launch} 返回
 * {@code <gamesession>1</gamesession><sessionUrl0>rtsp://127.0.0.1:48010</sessionUrl0>}，
 * {@code /cancel} 返回 {@code <cancel>1</cancel>} 且 currentgame 归零。</p>
 */
public final class StreamLaunch {

    public static final class ServerInfo {
        public final String appVersion;
        public final String gfeVersion;
        public final int codecMask;

        ServerInfo(String appVersion, String gfeVersion, int codecMask) {
            this.appVersion = appVersion;
            this.gfeVersion = gfeVersion;
            this.codecMask = codecMask;
        }
    }

    public static final class LaunchResult {
        public final int gameSession;
        public final String sessionUrl;

        LaunchResult(int gameSession, String sessionUrl) {
            this.gameSession = gameSession;
            this.sessionUrl = sessionUrl;
        }
    }

    private StreamLaunch() {}

    private static String common(StreamIdentity id) {
        return "uniqueid=" + id.getUniqueId() + "&uuid=" + UUID.randomUUID();
    }

    private static SSLContext ssl(StreamIdentity id) throws Exception {
        X509Certificate serverCert = id.loadServerCert();
        if (serverCert == null) {
            throw new IllegalStateException("没有已 pin 的服务器证书，请先完成 PIN 配对");
        }
        return id.createSslContext(serverCert);
    }

    /** HTTPS serverinfo：起流需要 GfeVersion 与服务端编码能力掩码。 */
    public static ServerInfo serverInfo(StreamIdentity id, String host, int httpsPort) throws Exception {
        String body = StreamPairing.httpGet(
                "https://" + host + ":" + httpsPort + "/serverinfo?" + common(id),
                10000, ssl(id));
        String appVersion = value(body, "appversion", "7.1.431.-1");
        String gfeVersion = value(body, "GfeVersion", "3.23.0.74");
        int codecMask = 0;
        String raw = StreamPairing.xml(body, "ServerCodecModeSupport");
        if (raw != null) {
            try { codecMask = Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return new ServerInfo(appVersion, gfeVersion, codecMask);
    }

    /**
     * 发起一次串流会话。riKey 是 16 字节 AES 密钥，riKeyId 是它的 32 位标识；
     * 两者同时用于 {@code MoonBridge.startConnection()} 的 riAesKey / riAesIv。
     */
    public static LaunchResult launch(StreamIdentity id, String host, int httpsPort,
                                      int appId, int width, int height, int fps,
                                      byte[] riKey, int riKeyId) throws Exception {
        // 快速重连时上一次会话可能仍在服务端做异步清理，实测会表现为
        // ENet 控制流立刻断开（Control stream received unexpected disconnect event）。
        // 先 cancel 掉自己残留的会话，实测 Sunshine 是同步清理 currentgame。
        cancel(id, host, httpsPort);
        String body = StreamPairing.httpGet(
                "https://" + host + ":" + httpsPort + "/launch?" + common(id) + "&"
                        + buildLaunchQuery(appId, width, height, fps, riKey, riKeyId),
                30000, ssl(id));
        String rawSession = StreamPairing.xml(body, "gamesession");
        int gameSession = 0;
        if (rawSession != null) {
            try { gameSession = Integer.parseInt(rawSession.trim()); } catch (NumberFormatException ignored) {}
        }
        if (gameSession == 0) {
            String status = StreamPairing.xml(body, "status_message");
            throw new IllegalStateException("服务端拒绝起流：" + (status == null ? body : status));
        }
        return new LaunchResult(gameSession, StreamPairing.xml(body, "sessionUrl0"));
    }

    /** 供真机回归直接核对参数：不含 uniqueid/uuid，也不含会触发 rtspenc 的 corever。 */
    static String buildLaunchQuery(int appId, int width, int height, int fps,
                                   byte[] riKey, int riKeyId) {
        StringBuilder q = new StringBuilder();
        q.append("appid=").append(appId);
        q.append("&mode=").append(width).append('x').append(height).append('x').append(fps);
        q.append("&additionalStates=1&sops=1");
        q.append("&rikey=").append(StreamIdentity.toHex(riKey, true));
        q.append("&rikeyid=").append(riKeyId);
        q.append("&localAudioPlayMode=0&surroundAudioInfo=196610");
        q.append("&remoteControllersBitmap=0&gcmap=0&gcpersist=0");
        // 原生库会补 &corever=1，Sunshine 7.1.431 据此启用 AES-GCM 加密 RTSP
        // （返回 rtspenc://）。本机 moonlight-core v12.2 实测解密 RTSP 响应失败
        // （logcat: Failed to decrypt RTSP response / RTSP OPTIONS request failed: -1），
        // 因此不声明该能力，让服务端回退到明文 rtsp://；局域网内与官方早期
        // 行为一致。其他原生能力参数（若有）仍保留。
        try {
            String nativeParams = MoonBridge.getLaunchUrlQueryParameters();
            if (nativeParams != null) {
                nativeParams = nativeParams.replaceAll("&corever=\\d+", "");
                if (!nativeParams.isEmpty()) {
                    if (!nativeParams.startsWith("&")) q.append('&');
                    q.append(nativeParams);
                }
            }
        } catch (Throwable ignored) {}
        return q.toString();
    }

    /** 结束服务端会话；不调用会让 Sunshine 一直处于「正在串流」而无法再次 launch。 */
    public static void cancel(StreamIdentity id, String host, int httpsPort) {
        try {
            StreamPairing.httpGet(
                    "https://" + host + ":" + httpsPort + "/cancel?" + common(id),
                    8000, ssl(id));
        } catch (Exception ignored) {}
    }

    private static String value(String body, String tag, String fallback) {
        String raw = StreamPairing.xml(body, tag);
        return raw == null || raw.trim().isEmpty() ? fallback : raw.trim();
    }
}
