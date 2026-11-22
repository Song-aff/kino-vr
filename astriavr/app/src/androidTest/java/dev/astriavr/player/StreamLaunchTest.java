package dev.astriavr.player;

import android.content.Context;

import com.limelight.nvstream.jni.MoonBridge;

import java.security.SecureRandom;

import junit.framework.TestCase;

/**
 * S3b 阶段 3a 真机回归：HTTPS 起流协商。
 *
 * <p>真机 filesDir 预置已配对身份；Sunshine 必须在线。这里只做控制面只读
 * 探测与参数校验，真实 /launch 起流由 StreamSessionTest 单独验证，避免
 * 同一进程内重复建会话导致 Sunshine 的 ENet 控制流断开。</p>
 */
public class StreamLaunchTest extends TestCase {

    private static final String HOST = "192.168.31.254";
    private static final int HTTPS_PORT = 47984;

    private static Context context() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object app = at.getMethod("currentApplication").invoke(null);
        assertNotNull("拿不到目标 APP Context", app);
        return (Context) app;
    }

    public void testServerInfoHasGfeVersionAndCodecMask() throws Exception {
        StreamIdentity id = StreamIdentity.get(context());
        StreamLaunch.ServerInfo info = StreamLaunch.serverInfo(id, HOST, HTTPS_PORT);
        assertNotNull("appversion 不能为空", info.appVersion);
        assertFalse("appversion 不能为空串", info.appVersion.isEmpty());
        assertNotNull("GfeVersion 不能为空", info.gfeVersion);
        assertFalse("GfeVersion 不能为空串", info.gfeVersion.isEmpty());
        assertTrue("ServerCodecModeSupport 至少要有 H.264/HEVC 位", info.codecMask != 0);
    }

    /**
     * 纯参数校验，不真实 /launch。真实会话只由 StreamSessionTest 发起一次：
     * 实测同一进程内连续两次 launch+cancel 会让 Sunshine 的 ENet 控制流
     * 在第二次启动时立刻断开（Control stream received unexpected disconnect event）。
     */
    public void testLaunchQueryKeepsRiKeyAndDisablesEncryptedRtsp() {
        byte[] riKey = new byte[16];
        new SecureRandom().nextBytes(riKey);
        int riKeyId = new SecureRandom().nextInt() & 0x7FFFFFFF;
        String q = StreamLaunch.buildLaunchQuery(881448767,
                StreamSession.WIDTH, StreamSession.HEIGHT, StreamSession.FPS, riKey, riKeyId);

        assertTrue("必须带 appid", q.contains("appid=881448767"));
        assertTrue("必须带协商分辨率/帧率", q.contains("mode=1920x1080x60"));
        assertTrue("必须带 rikey", q.contains("rikey=" + StreamIdentity.toHex(riKey, true)));
        assertTrue("必须带 rikeyid", q.contains("rikeyid=" + riKeyId));
        assertFalse("不能声明 corever，否则 Sunshine 返回 rtspenc:// 且本机解密失败",
                q.contains("corever="));
        assertTrue("native 库确实报告了 corever，说明剥离逻辑是必要的",
                MoonBridge.getLaunchUrlQueryParameters().contains("corever="));
    }

    public void testNativeLaunchParameters() {
        String params = MoonBridge.getLaunchUrlQueryParameters();
        assertNotNull("原生库必须能提供 launch 能力参数", params);
        assertFalse("launch 能力参数不能为空", params.isEmpty());
        android.util.Log.i("VRStreamLaunchTest", "native launch params: " + params);
    }
}
