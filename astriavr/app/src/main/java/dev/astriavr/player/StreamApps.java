package dev.astriavr.player;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

/**
 * S3b 阶段 2b-2：配对后的 HTTPS 47984 控制面（双向 TLS）。
 *
 * <p>{@code /applist} 与 {@code /launch} 必须走 HTTPS 并携带配对时登记的客户端证书，
 * 明文 47989 端口只用于 {@code /serverinfo} 发现与 PIN 配对。服务端证书用配对时
 * 保存的 {@code stream-server.crt} 做 pin，因此忽略自签证书 CN 与局域网 IP 不匹配。</p>
 */
public final class StreamApps {

    public static final class App {
        public final int id;
        public final String title;
        public final boolean hdrSupported;

        App(int id, String title, boolean hdrSupported) {
            this.id = id;
            this.title = title;
            this.hdrSupported = hdrSupported;
        }
    }

    private static final Pattern APP_BLOCK = Pattern.compile("<App>(.*?)</App>", Pattern.DOTALL);

    private StreamApps() {}

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

    /** 取 Sunshine 应用列表；能取到即证明客户端证书已被服务端授权。 */
    public static List<App> fetch(StreamIdentity id, String host, int httpsPort) throws Exception {
        String body = StreamPairing.httpGet(
                "https://" + host + ":" + httpsPort + "/applist?" + common(id),
                10000, ssl(id));
        ArrayList<App> apps = new ArrayList<>();
        Matcher m = APP_BLOCK.matcher(body);
        while (m.find()) {
            String block = m.group(1);
            String rawId = StreamPairing.xml(block, "ID");
            if (rawId == null) continue;
            apps.add(new App(Integer.parseInt(rawId.trim()),
                    String.valueOf(StreamPairing.xml(block, "AppTitle")),
                    "1".equals(StreamPairing.xml(block, "IsHdrSupported"))));
        }
        return apps;
    }

    /** 走 HTTPS 的 serverinfo：只有携带已授权客户端证书时 PairStatus 才为 1。 */
    public static int pairStatus(StreamIdentity id, String host, int httpsPort) throws Exception {
        String body = StreamPairing.httpGet(
                "https://" + host + ":" + httpsPort + "/serverinfo?" + common(id),
                10000, ssl(id));
        String raw = StreamPairing.xml(body, "PairStatus");
        return raw == null ? 0 : Integer.parseInt(raw.trim());
    }
}
