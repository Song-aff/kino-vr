package dev.astriavr.player;

import android.content.Context;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * S3b 配对前置的真机回归：手写 DER 生成的客户端证书必须能被平台
 * CertificateFactory 正确解析、自签签名可验证、PEM hex 可用于 /pair，
 * 且能从磁盘重载（否则每次启动都会换身份、需要重新配对）。
 */
public class StreamIdentityTest extends TestCase {

    private static Context context() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object app = at.getMethod("currentApplication").invoke(null);
        assertNotNull("拿不到目标 APP Context", app);
        return (Context) app;
    }

    public void testSelfSignedCertificateIsValid() throws Exception {
        StreamIdentity id = StreamIdentity.get(context());
        X509Certificate cert = id.getCertificate();
        assertNotNull(cert);
        assertTrue("CN 应为 GameStream 客户端约定值",
                cert.getSubjectX500Principal().getName().contains(StreamIdentity.CLIENT_CN));
        // 自签验证：DER 编码错、签名错都会在这里抛异常
        cert.verify(cert.getPublicKey());
        assertNotNull("配对哈希需要证书签名字节", id.getSignatureBytes());
        assertTrue(id.getSignatureBytes().length > 100);
        assertEquals(2048, ((java.security.interfaces.RSAPublicKey) cert.getPublicKey())
                .getModulus().bitLength());
    }

    public void testPemHexIsUsableForPairing() throws Exception {
        StreamIdentity id = StreamIdentity.get(context());
        String hex = id.getPemHex();
        String pem = new String(StreamIdentity.fromHex(hex), "US-ASCII");
        assertTrue("clientcert 必须是 PEM 文本", pem.startsWith("-----BEGIN CERTIFICATE-----"));
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"));
        assertFalse("PEM 行尾必须是 LF", pem.contains("\r"));
        X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes("US-ASCII")));
        assertTrue("PEM 解析出的证书应与内存一致",
                Arrays.equals(parsed.getEncoded(), id.getCertificate().getEncoded()));
    }

    public void testPersistedIdentityReloads() throws Exception {
        StreamIdentity first = StreamIdentity.get(context());
        byte[] certDer = first.getCertificate().getEncoded();
        byte[] privDer = first.getPrivateKey().getEncoded();
        String uid = first.getUniqueId();
        assertEquals("uniqueid 必须是 16 位十六进制", 16, uid.length());
        assertTrue(uid.matches("[0-9A-F]{16}"));

        StreamIdentity.resetForTest();
        StreamIdentity second = StreamIdentity.get(context());
        assertTrue("重载后证书必须一致（否则每次启动要重新配对）",
                Arrays.equals(certDer, second.getCertificate().getEncoded()));
        assertTrue("重载后私钥必须一致",
                Arrays.equals(privDer, second.getPrivateKey().getEncoded()));
        assertEquals("重载后 uniqueid 必须一致", uid, second.getUniqueId());
    }

    public void testMutualTlsContextBuilds() throws Exception {
        StreamIdentity id = StreamIdentity.get(context());
        javax.net.ssl.SSLContext ssl = id.createSslContext(id.getCertificate());
        assertNotNull(ssl);
        assertNotNull(ssl.getSocketFactory());
        assertEquals("TLS", ssl.getProtocol());
    }
}
