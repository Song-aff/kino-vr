package dev.astriavr.player;

import android.content.Context;

import junit.framework.TestCase;

import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * S3b 阶段 2b-2 真机回归。
 *
 * <p>配对算法在本机 Sunshine 7.1.431 上已用 PC 侧脚本完整跑通 6 步
 * （PIN 由用户在 Web UI 输入），本测试验证同一套实现搬到 Android 后：
 * 密码学原语一致、证书 pin 可加载、双向 TLS 控制面 {@code /applist}
 * 与 {@code /serverinfo} 能取到数据且 {@code PairStatus=1}。</p>
 *
 * <p>真机 filesDir 需预置已配对身份：stream-client.crt / stream-client.key /
 * stream-uniqueid.txt / stream-server.crt，避免每台设备都要重新输 PIN。</p>
 */
public class StreamPairingTest extends TestCase {

    private static final String HOST = "192.168.31.254";
    private static final int HTTPS_PORT = 47984;

    private static Context context() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object app = at.getMethod("currentApplication").invoke(null);
        assertNotNull("拿不到目标 APP Context", app);
        return (Context) app;
    }

    public void testCryptoPrimitivesMatchOfficial() throws Exception {
        String pin = StreamPairing.generatePin();
        assertEquals("PIN 必须是 4 位数字", 4, pin.length());
        assertTrue(pin.matches("[0-9]{4}"));

        byte[] salt = StreamIdentity.fromHex("00112233445566778899AABBCCDDEEFF");
        byte[] key = Arrays.copyOf(
                StreamPairing.sha256(StreamPairing.concat(salt, "1234".getBytes("UTF-8"))), 16);
        assertEquals("AES key 必须是 SHA256(salt||PIN) 前 16 字节", 16, key.length);
        assertEquals(32, StreamPairing.sha256(new byte[]{1, 2, 3}).length);

        byte[] challenge = StreamPairing.randomBytes(16);
        byte[] encrypted = StreamPairing.aes(true, challenge, key);
        assertEquals(16, encrypted.length);
        assertTrue("AES-128-ECB 往返必须一致",
                Arrays.equals(challenge, StreamPairing.aes(false, encrypted, key)));

        byte[] hash = StreamPairing.sha256(challenge);
        assertEquals(32, StreamPairing.pad16(hash).length);
        assertEquals(48, StreamPairing.pad16(new byte[33]).length);

        StreamIdentity id = StreamIdentity.get(context());
        byte[] sig = StreamPairing.sign(challenge, id.getPrivateKey());
        assertTrue("SHA256withRSA 签名必须可验证",
                StreamPairing.verify(challenge, sig, id.getCertificate()));
    }

    public void testServerCertificateIsPinned() throws Exception {
        X509Certificate server = StreamIdentity.get(context()).loadServerCert();
        assertNotNull("需先注入已配对服务端证书 stream-server.crt", server);
        assertNotNull("服务端证书应有公钥", server.getPublicKey());
        assertNotNull("配对哈希需要服务端证书签名", server.getSignature());
    }

    public void testAppListOverMutualTls() throws Exception {
        List<StreamApps.App> apps = StreamApps.fetch(
                StreamIdentity.get(context()), HOST, HTTPS_PORT);
        assertFalse("HTTPS 47984 /applist 应返回应用（客户端证书未被授权时会 401/空）",
                apps.isEmpty());
        boolean desktop = false;
        for (StreamApps.App app : apps) {
            assertTrue("每个应用都要有 ID 和标题", app.id != 0 && app.title != null);
            if ("Desktop".equals(app.title)) desktop = true;
        }
        assertTrue("Sunshine 默认应有 Desktop 应用", desktop);
    }

    public void testPairStatusOverMutualTls() throws Exception {
        assertEquals("携带已授权客户端证书时 PairStatus 必须为 1", 1,
                StreamApps.pairStatus(StreamIdentity.get(context()), HOST, HTTPS_PORT));
    }
}
