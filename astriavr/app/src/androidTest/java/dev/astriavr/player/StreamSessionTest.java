package dev.astriavr.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;

/**
 * S3b 阶段 3 端到端真机回归：不经过 UI 点击，直接把 VrView 同款的
 * SurfaceTexture-backed Surface 交给 StreamSession，验证
 * HTTPS /launch → MoonBridge.startConnection → RTP 解包 → MediaCodec 帧回调
 * 这条链路真的能跑起来。
 *
 * <p>结束时会 stop() + /cancel，避免把 Sunshine 留在「正在串流」。</p>
 */
public class StreamSessionTest extends TestCase {

    private static final String HOST = "192.168.31.254";
    private static final int HTTPS_PORT = 47984;
    private static final int DESKTOP_APP_ID = 881448767;

    private static Context context() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object app = at.getMethod("currentApplication").invoke(null);
        assertNotNull("拿不到目标 APP Context", app);
        return (Context) app;
    }

    public void testNativeStreamProducesDecodedFrames() throws Exception {
        // 回归套件里上一条 /launch 可能刚 cancel，等服务端释放 ENet 端口。
        Thread.sleep(600);
        SurfaceTexture texture = new SurfaceTexture(0);
        Surface surface = new Surface(texture);
        final CountDownLatch signal = new CountDownLatch(1);
        final AtomicInteger frames = new AtomicInteger();
        final AtomicReference<String> error = new AtomicReference<>("");
        final AtomicReference<String> state = new AtomicReference<>("init");
        final AtomicInteger videoWidth = new AtomicInteger();

        StreamSession session = new StreamSession(context(), HOST, HTTPS_PORT,
                DESKTOP_APP_ID, 0x0301, new StreamSession.Listener() {
            @Override public void onState(String message) { state.set(message); }
            @Override public void onError(String message) {
                error.set(message);
                signal.countDown();
            }
            @Override public void onVideoSize(int width, int height) {
                videoWidth.set(width);
                Log.i("VRStreamSessionTest", "setupVideo " + width + "x" + height);
            }
            @Override public void onTerminated(int code) {
                Log.i("VRStreamSessionTest", "terminated code=" + code);
                signal.countDown();
            }
            @Override public void onFramesDecoded(int count) {
                frames.set(count);
                signal.countDown();
            }
        });

        try {
            session.attachSurface(surface);
            assertTrue("40 秒内应完成 native 起流（state=" + state.get()
                            + " error=" + error.get() + "）",
                    signal.await(40, TimeUnit.SECONDS));
            assertTrue("必须收到至少一个解码帧（state=" + state.get()
                            + " error=" + error.get() + " width=" + videoWidth.get() + "）",
                    frames.get() > 0);
            assertTrue("解码器应按协商分辨率初始化", videoWidth.get() >= 1280);
            Log.i("VRStreamSessionTest", "OK frames=" + frames.get() + " width=" + videoWidth.get());
        } finally {
            session.stop();
            Thread.sleep(1200);
            surface.release();
            texture.release();
        }
    }
}
