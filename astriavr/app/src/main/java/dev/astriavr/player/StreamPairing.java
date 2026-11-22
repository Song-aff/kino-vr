package dev.astriavr.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * S3b 阶段 2b-2：Sunshine / GameStream Gen7 完整 PIN 配对状态机。
 *
 * <p>协议逐行核对官方 moonlight-android {@code PairingManager.java} 与
 * LizardByte/Sunshine {@code nvhttp.cpp}，并在本机 Sunshine 7.1.431 上实测通过：
 * {@code getservercert}（阻塞等待用户在 Web UI 输 4 位 PIN）→ {@code clientchallenge}
 * → {@code serverchallengeresp} → {@code clientpairingsecret} →
 * {@code pairchallenge}（HTTPS 47984，双向 TLS）。</p>
 *
 * <p>纯 Java 平台 API，零新增依赖：AES-128-ECB 零填充用 {@code Cipher}，
 * 证书/签名用 {@code java.security}，XML 用正则（Sunshine 响应是扁平单行 XML）。
 * 不依赖 BouncyCastle、OkHttp 或 Android UI 类，便于 JVM 与 instrumentation 双端回归。</p>
 *
 * <p>调用约定：{@link #pair} 是阻塞方法，第一步会挂起直到服务端确认 PIN，
 * 必须在子线程调用；PIN 由 {@link #generatePin()} 生成并先展示给用户。</p>
 */
public final class StreamPairing {

    public enum State { PAIRED, PIN_WRONG, ALREADY_IN_PROGRESS, FAILED }

    public static final class Result {
        public final State state;
        public final X509Certificate serverCert;
        public final String detail;

        Result(State state, X509Certificate serverCert, String detail) {
            this.state = state;
            this.serverCert = serverCert;
            this.detail = detail;
        }

        public boolean isPaired() { return state == State.PAIRED; }
    }

    private static final Pattern TAG = Pattern.compile("<([A-Za-z0-9_]+)>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern STATUS_MESSAGE = Pattern.compile("status_message=\"([^\"]*)\"");
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int STEP_TIMEOUT = 10000;

    private StreamPairing() {}

    /** 4 位数字 PIN，展示给用户在 Sunshine Web UI 输入。 */
    public static String generatePin() {
        SecureRandom r = new SecureRandom();
        return String.format(Locale.US, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10), r.nextInt(10), r.nextInt(10));
    }

    /**
     * 执行一次完整配对。{@code pin} 必须是展示给用户、且用户在服务端输入的同一个值。
     * Sunshine 只在 {@code clientpairingsecret} 校验通过后才把客户端证书写入授权列表。
     */
    public static Result pair(StreamIdentity id, String host, int httpPort,
                              int httpsPort, String pin) {
        try {
            return pairInternal(id, host, httpPort, httpsPort, pin);
        } catch (Exception e) {
            return new Result(State.FAILED, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Result pairInternal(StreamIdentity id, String host, int httpPort,
                                       int httpsPort, String pin) throws Exception {
        byte[] salt = randomBytes(16);
        byte[] aesKey = Arrays.copyOf(sha256(concat(salt, pin.getBytes("UTF-8"))), 16);

        // step 1: 挂起等待用户在 Sunshine Web UI 输入 PIN。
        String common = common(id);
        // 会话最多存活 5 分钟（Sunshine nvhttp.cpp 的配对会话过期时间），
        // 设同等读超时：既覆盖整个人工输入窗口，也避免用户取消后线程永久挂起。
        String step1 = httpGet("http://" + host + ":" + httpPort + "/pair?" + common
                + "&devicename=VRPlayer&updateState=1&phrase=getservercert"
                + "&salt=" + StreamIdentity.toHex(salt, true)
                + "&clientcert=" + id.getPemHex(), 300000, null);
        if (!"1".equals(xml(step1, "paired"))) return failed(step1, "getservercert");

        String plainCertHex = xml(step1, "plaincert");
        if (plainCertHex == null) {
            // 另一个客户端正在配对，服务端返回空证书
            return new Result(State.ALREADY_IN_PROGRESS, null, "服务端已有配对会话在进行中");
        }
        byte[] serverPem = StreamIdentity.fromHex(plainCertHex);
        X509Certificate serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(serverPem));
        // 服务端证书只在内存中用于本次质询；此前在这里就落盘会导致中途失败时
        // 留下半吊子凭据，APP 下次误判「已配对」却拿不到授权。
        SSLContext ssl = id.createSslContext(serverCert);

        // step 2: 客户端随机质询（AES 加密）
        byte[] randomChallenge = randomBytes(16);
        String step2 = httpGet("http://" + host + ":" + httpPort + "/pair?" + common
                + "&devicename=VRPlayer&updateState=1&clientchallenge="
                + StreamIdentity.toHex(aes(true, randomChallenge, aesKey), true), STEP_TIMEOUT, null);
        if (!"1".equals(xml(step2, "paired"))) return failed(step2, "clientchallenge");

        byte[] decrypted = aes(false, StreamIdentity.fromHex(xml(step2, "challengeresponse")), aesKey);
        byte[] serverResponse = Arrays.copyOfRange(decrypted, 0, 32);
        byte[] serverChallenge = Arrays.copyOfRange(decrypted, 32, 48);

        // step 3: 用「服务端质询 + 客户端证书签名 + 客户端密钥」应答
        byte[] clientSecret = randomBytes(16);
        byte[] responseHash = sha256(concat(serverChallenge, id.getSignatureBytes(), clientSecret));
        String step3 = httpGet("http://" + host + ":" + httpPort + "/pair?" + common
                + "&devicename=VRPlayer&updateState=1&serverchallengeresp="
                + StreamIdentity.toHex(aes(true, pad16(responseHash), aesKey), true), STEP_TIMEOUT, null);
        if (!"1".equals(xml(step3, "paired"))) return failed(step3, "serverchallengeresp");

        byte[] pairingSecret = StreamIdentity.fromHex(xml(step3, "pairingsecret"));
        byte[] serverSecret = Arrays.copyOfRange(pairingSecret, 0, 16);
        byte[] serverSignature = Arrays.copyOfRange(pairingSecret, 16, pairingSecret.length);
        if (!verify(serverSecret, serverSignature, serverCert)) {
            return new Result(State.FAILED, null, "服务端签名校验失败（疑似中间人）");
        }
        byte[] expected = sha256(concat(randomChallenge, serverCert.getSignature(), serverSecret));
        if (!MessageDigest.isEqual(expected, serverResponse)) {
            return new Result(State.PIN_WRONG, null, "PIN 不正确");
        }

        // step 4: 把客户端密钥及其签名交给服务端，这一步才真正写入授权客户端
        byte[] signedSecret = concat(clientSecret, sign(clientSecret, id.getPrivateKey()));
        String step4 = httpGet("http://" + host + ":" + httpPort + "/pair?" + common
                + "&devicename=VRPlayer&updateState=1&clientpairingsecret="
                + StreamIdentity.toHex(signedSecret, true), STEP_TIMEOUT, null);
        if (!"1".equals(xml(step4, "paired"))) return failed(step4, "clientpairingsecret");

        // step 5: 官方仍会做一次 HTTPS 质询，用于让服务端把连接标记为已配对
        String step5 = httpGet("https://" + host + ":" + httpsPort + "/pair?" + common
                + "&devicename=VRPlayer&updateState=1&phrase=pairchallenge", STEP_TIMEOUT, ssl);
        if (!"1".equals(xml(step5, "paired"))) return failed(step5, "pairchallenge");

        // 全部 6 步通过后才持久化服务端证书，作为 APP 侧「已配对」的唯一依据。
        id.saveServerCert(serverCert.getEncoded());
        return new Result(State.PAIRED, serverCert, "配对完成");
    }

    private static Result failed(String body, String step) {
        Matcher m = STATUS_MESSAGE.matcher(body == null ? "" : body);
        String msg = m.find() ? m.group(1) : "";
        return new Result(State.FAILED, null, step + " 失败 " + msg);
    }

    private static String common(StreamIdentity id) {
        return "uniqueid=" + id.getUniqueId() + "&uuid=" + UUID.randomUUID();
    }

    /** 供 {@link StreamApps} 复用：HTTP 或带客户端证书 + 证书 pin 的 HTTPS GET。 */
    static String httpGet(String url, int readTimeoutMs, SSLContext ssl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                if (ssl != null) https.setSSLSocketFactory(ssl.getSocketFactory());
                // 已 pin 服务器证书，Sunshine 自签证书的 CN 不会是局域网 IP
                https.setHostnameVerifier((hostname, session) -> true);
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("GET");
            // Sunshine 的 /pair 会在响应后主动断连；Android OkHttp 连接池复用该连接时
            // 会让下一次 TLS 握手挂住，因此控制面一律不复用连接。
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code + " 无响应体");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    static String xml(String body, String tag) {
        if (body == null) return null;
        Matcher m = TAG.matcher(body);
        while (m.find()) {
            if (tag.equals(m.group(1))) return m.group(2);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 密码学原语（与官方 PairingManager 等价）
    // ------------------------------------------------------------------

    static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        new SecureRandom().nextBytes(out);
        return out;
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    static byte[] pad16(byte[] data) {
        int rounded = (data.length + 15) & ~15;
        return Arrays.copyOf(data, rounded);   // AESLightEngine 的零填充语义
    }

    static byte[] aes(boolean encrypt, byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    static byte[] sign(byte[] data, java.security.PrivateKey key) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(data);
        return sig.sign();
    }

    static boolean verify(byte[] data, byte[] signature, X509Certificate cert) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(cert.getPublicKey());
        sig.update(data);
        return sig.verify(signature);
    }
}
