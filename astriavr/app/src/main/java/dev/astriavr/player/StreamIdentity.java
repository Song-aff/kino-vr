package dev.astriavr.player;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * S3b 配对用的客户端身份：2048 位 RSA 密钥对 + 自签 X.509 证书 + uniqueid，
 * 以及配对完成后双向 TLS 所需的 {@link SSLContext}。
 *
 * <p>官方 Moonlight 用 BouncyCastle 生成证书；这里手写最小 DER 编码，
 * 保持项目「零新增依赖」。证书结构与官方 AndroidCryptoProvider 一致：
 * v3 / SHA256withRSA / CN=NVIDIA GameStream Client / 20 年有效。
 * Sunshine 的 /pair 要求 clientcert 传 <b>PEM 文本的 hex</b>（不是 DER）。</p>
 *
 * <p>证书生成后用平台 {@link CertificateFactory} 回读校验，避免把错误
 * DER 带进配对握手；配对哈希需要 {@link X509Certificate#getSignature()}。</p>
 */
public final class StreamIdentity {

    public static final String CLIENT_CN = "NVIDIA GameStream Client";

    private static final String FILE_CERT = "stream-client.crt";
    private static final String FILE_KEY = "stream-client.key";
    private static final String FILE_UID = "stream-uniqueid.txt";
    public static final String FILE_SERVER_CERT = "stream-server.crt";

    // sha256WithRSAEncryption 1.2.840.113549.1.1.11
    private static final byte[] OID_SHA256_RSA = {
            0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B
    };
    // commonName 2.5.4.3
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    private static StreamIdentity instance;

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final byte[] pemBytes;
    private final String uniqueId;
    private final File dir;

    private StreamIdentity(File dir, PrivateKey key, X509Certificate cert,
                           byte[] pem, String uniqueId) {
        this.dir = dir;
        this.privateKey = key;
        this.certificate = cert;
        this.pemBytes = pem;
        this.uniqueId = uniqueId;
    }

    public static synchronized StreamIdentity get(Context context) throws Exception {
        if (instance != null) return instance;
        File dir = context.getFilesDir();
        File certFile = new File(dir, FILE_CERT);
        File keyFile = new File(dir, FILE_KEY);
        File uidFile = new File(dir, FILE_UID);

        if (certFile.isFile() && keyFile.isFile() && uidFile.isFile()) {
            try {
                byte[] pem = readAll(certFile);
                byte[] keyBytes = readAll(keyFile);
                String uid = new String(readAll(uidFile), StandardCharsets.UTF_8).trim();
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(pem));
                PrivateKey key = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
                if (uid.length() == 16) {
                    instance = new StreamIdentity(dir, key, cert, pem, uid);
                    return instance;
                }
            } catch (Exception ignored) {
                // 损坏的身份文件 -> 重新生成（会导致需要重新配对，属预期行为）
            }
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair pair = kpg.generateKeyPair();

        byte[] certDer = buildSelfSignedCertificate(pair);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(certDer));
        // 回读校验：公钥一致 + 自签签名可验证
        cert.verify(pair.getPublic());

        byte[] pem = toPem(certDer);
        String uid = randomHex(16);
        writeAll(certFile, pem);
        writeAll(keyFile, pair.getPrivate().getEncoded());
        writeAll(uidFile, uid.getBytes(StandardCharsets.UTF_8));

        instance = new StreamIdentity(dir, pair.getPrivate(), cert, pem, uid);
        return instance;
    }

    /** 仅用于测试：丢掉内存缓存，强制从磁盘重载。 */
    static synchronized void resetForTest() {
        instance = null;
    }

    public X509Certificate getCertificate() { return certificate; }
    public PrivateKey getPrivateKey() { return privateKey; }
    public String getUniqueId() { return uniqueId; }
    public byte[] getSignatureBytes() { return certificate.getSignature(); }
    public byte[] getPemBytes() { return pemBytes; }

    /** /pair 的 clientcert 参数值：PEM 文本逐字节转大写 hex。 */
    public String getPemHex() {
        return toHex(pemBytes, true);
    }

    public static String toHex(byte[] data, boolean upper) {
        char[] digits = (upper ? "0123456789ABCDEF" : "0123456789abcdef").toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] fromHex(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4)
                    | Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    /** 保存配对成功后拿到的服务器证书（DER），供 HTTPS pin 使用。 */
    public void saveServerCert(byte[] der) throws Exception {
        writeAll(new File(dir, FILE_SERVER_CERT), der);
    }

    public X509Certificate loadServerCert() throws Exception {
        File f = new File(dir, FILE_SERVER_CERT);
        if (!f.isFile()) return null;
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(readAll(f)));
    }

    /** 用户主动「重新配对」时清掉旧凭据；Sunshine 重装 / 授权被清空后必须走这一步。 */
    public boolean clearServerCert() {
        return new File(dir, FILE_SERVER_CERT).delete();
    }

    /** 客户端证书 + 只信任已配对服务器证书的双向 TLS 上下文。 */
    public SSLContext createSslContext(X509Certificate serverCert) throws Exception {
        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        clientStore.load(null, null);
        clientStore.setKeyEntry("client", privateKey, new char[0],
                new Certificate[]{certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, new char[0]);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        if (serverCert != null) trustStore.setCertificateEntry("server", serverCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ssl;
    }

    // ------------------------------------------------------------------
    // 最小 X.509 v3 DER 编码
    // ------------------------------------------------------------------

    private static byte[] buildSelfSignedCertificate(KeyPair pair) throws Exception {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.add(Calendar.DAY_OF_YEAR, -1);
        Date notBefore = cal.getTime();
        cal.add(Calendar.YEAR, 20);
        cal.add(Calendar.DAY_OF_YEAR, 1);
        Date notAfter = cal.getTime();

        byte[] serialRaw = new byte[8];
        new SecureRandom().nextBytes(serialRaw);
        serialRaw[0] &= 0x7F;                 // 保证正数
        if (serialRaw[0] == 0) serialRaw[0] = 1;

        byte[] sigAlg = seq(concat(OID_SHA256_RSA, new byte[]{0x05, 0x00}));
        byte[] name = cnName();
        byte[] validity = seq(concat(utcTime(notBefore), utcTime(notAfter)));

        byte[] tbs = seq(concat(
                der(0xA0, derInteger(2)),                 // version v3 (explicit)
                derInteger(new BigInteger(serialRaw)),    // serialNumber
                sigAlg,                                   // signature
                name,                                     // issuer
                validity,                                 // validity
                name,                                     // subject
                pair.getPublic().getEncoded()             // SubjectPublicKeyInfo
        ));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        byte[] signatureValue = der(0x03, concat(new byte[]{0x00}, signature));
        return seq(concat(tbs, sigAlg, signatureValue));
    }

    private static byte[] cnName() {
        byte[] cnAttr = seq(concat(OID_CN,
                der(0x13, CLIENT_CN.getBytes(StandardCharsets.US_ASCII)))); // PrintableString
        return seq(der(0x31, cnAttr)); // Name = SEQUENCE OF RDN; RDN 必须是 SET(0x31)
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(0x17, fmt.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derInteger(long value) {
        return derInteger(BigInteger.valueOf(value));
    }

    private static byte[] derInteger(BigInteger value) {
        byte[] body = value.toByteArray(); // 已含符号位，符合 DER INTEGER
        return der(0x02, body);
    }

    private static byte[] der(int tag, byte[] body) {
        return concat(new byte[]{(byte) tag}, concat(derLength(body.length), body));
    }

    private static byte[] seq(byte[] body) {
        return der(0x30, body);
    }

    private static byte[] derLength(int len) {
        if (len < 128) return new byte[]{(byte) len};
        int bytes = 0;
        int v = len;
        while (v > 0) { bytes++; v >>>= 8; }
        byte[] out = new byte[bytes + 1];
        out[0] = (byte) (0x80 | bytes);
        for (int i = 0; i < bytes; i++) {
            out[out.length - 1 - i] = (byte) (len >>> (8 * i));
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
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

    private static byte[] toPem(byte[] der) {
        String b64 = java.util.Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return toHex(raw, true).substring(0, 16);
    }

    private static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
