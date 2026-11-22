package dev.astriavr.player;

import android.content.Context;

import junit.framework.TestCase;

/**
 * S3b 阶段 4：APP 内首次 PIN 配对的协议入口回归。
 *
 * <p>不触碰真机 filesDir 里已注入的授权身份（只读取，不删除服务端证书），
 * 也不真正向 Sunshine 发起挂起配对（那需要人工在 Web UI 输入 PIN）。
 * 覆盖：PIN 格式、客户端身份只读字段、不可达主机时 pair() 快速失败而非挂死。</p>
 */
public class StreamPairingFlowTest extends TestCase {

    private static Context context() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object app = at.getMethod("currentApplication").invoke(null);
        assertNotNull("拿不到目标 APP Context", app);
        return (Context) app;
    }

    public void testGeneratedPinIsFourDigits() {
        String pin = StreamPairing.generatePin();
        assertNotNull(pin);
        assertTrue("PIN 必须是 4 位数字，实际=" + pin, pin.matches("\\d{4}"));
    }

    public void testIdentityIsUsableWithoutTouchingServerCert() throws Exception {
        StreamIdentity id = StreamIdentity.get(context());
        assertNotNull(id);
        assertEquals("uniqueid 长度必须为 16（与 StreamIdentity 的校验一致）",
                16, id.getUniqueId().length());
        assertTrue("客户端证书 PEM hex 不能为空", id.getPemHex().length() > 100);
        assertNotNull("客户端私钥必须可用", id.getPrivateKey());
    }

    public void testPairFailsFastOnUnreachableHost() throws Exception {
        StreamIdentity id = StreamIdentity.get(context());
        long start = System.currentTimeMillis();
        StreamPairing.Result result = StreamPairing.pair(id, "127.0.0.1", 1, 1, "1234");
        long elapsed = System.currentTimeMillis() - start;
        assertNotNull(result);
        assertFalse("不可达主机不能返回已配对", result.isPaired());
        assertTrue("不可达主机应快速失败而不是挂起（耗时 " + elapsed + "ms）",
                elapsed < 15000);
    }
}
