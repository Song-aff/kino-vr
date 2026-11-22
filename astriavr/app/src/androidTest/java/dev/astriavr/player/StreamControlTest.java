package dev.astriavr.player;

import junit.framework.TestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * S3b 阶段 1 真机回归：用真机 APK 里的真实 Kotlin 代码向局域网 Sunshine
 * 发起 /serverinfo 握手，验证网络连通、明文 HTTP、XML 解析与能力位。
 *
 * 不依赖 UI 点击 —— MIUI 会拦截 adb 的 INJECT_EVENTS 注入，
 * 所以通过 `am instrument` 直接跑，绕开界面层。
 * 运行：assembleDebugAndroidTest -> 安装 test APK -> am instrument。
 */
public class StreamControlTest extends TestCase {

    /** 与 PC 管理服务 /api/status 的 ip 一致；换网络时同步改这里。 */
    private static final String HOST_IP = "192.168.31.254";

    public void testServerInfoHandshake() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<StreamHost> result = new AtomicReference<StreamHost>();
        StreamControl.INSTANCE.probe(HOST_IP, StreamControl.HTTP_PORT, host -> {
            result.set(host);
            latch.countDown();
            return kotlin.Unit.INSTANCE;
        });
        assertTrue("握手超时：手机连不上 " + HOST_IP + ":47989", latch.await(8, TimeUnit.SECONDS));
        StreamHost h = result.get();
        assertNotNull("Sunshine 未响应 /serverinfo", h);
        assertFalse("hostname 不应为空", h.getName().isEmpty());
        assertTrue("应广播 HEVC 能力", h.getSupportsHevc());
        assertEquals("HTTPS 端口应为 47984", 47984, h.getHttpsPort());
        assertFalse("codecLabel 不应为未知", h.getCodecLabel().contains("未知"));
        System.out.println("S3B-HANDSHAKE: name=" + h.getName()
                + " codec=" + h.getCodecLabel()
                + " paired=" + h.getPaired()
                + " state=" + h.getState()
                + " version=" + h.getAppVersion());
    }
}
