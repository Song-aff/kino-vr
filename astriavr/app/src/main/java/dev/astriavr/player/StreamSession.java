package dev.astriavr.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S3b 阶段 3：一次完整的串流会话编排。
 *
 * <p>调用顺序：{@code attachSurface(VrView 的 VideoOutput.surface)} →
 * HTTPS 授权校验 → {@code /launch} 协商 → {@code MoonBridge.startConnection()}
 * （阻塞直到连接结束）→ 原生层通过 {@link StreamDecoder} 把 H.264/HEVC 帧
 * 解码到同一个 Surface → VrView 的 SurfaceTexture 自动驱动大屏银幕重绘。</p>
 *
 * <p>{@code startConnection} 与 {@code stopConnection} 不能并行调用，
 * 因此两者都放在 {@code synchronized (MoonBridge.class)} 里，与官方
 * NvConnection 的写法一致。</p>
 */
public final class StreamSession {

    public interface Listener {
        /** 主线程回调：阶段名 / 连接状态。 */
        void onState(String message);
        void onError(String message);
        void onVideoSize(int width, int height);
        void onTerminated(int errorCode);
        void onFramesDecoded(int frames);
    }

    public static final int WIDTH = 1920;
    public static final int HEIGHT = 1080;
    public static final int FPS = 60;
    public static final int BITRATE_KBPS = 20000;
    public static final int PACKET_SIZE = 1024;

    private static final String TAG = "VRStreamSession";

    private final Context context;
    private final String host;
    private final int httpsPort;
    private final int appId;
    private final int fallbackCodecMask;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile StreamDecoder decoder;
    private volatile boolean running;
    private volatile boolean terminatedNotified;
    private final AtomicBoolean cleaned = new AtomicBoolean(false);
    private Surface surface;

    public StreamSession(Context context, String host, int httpsPort, int appId,
                         int fallbackCodecMask, Listener listener) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.httpsPort = httpsPort;
        this.appId = appId;
        this.fallbackCodecMask = fallbackCodecMask;
        this.listener = listener;
    }

    /** VrRenderer 的 onOutput 回调可能晚于连接请求，也可重复触发；内部做幂等。 */
    public synchronized void attachSurface(Surface output) {
        if (output == null || running || decoder != null) return;
        this.surface = output;
        running = true;
        Thread thread = new Thread(this::runConnection, "vr-stream-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void runConnection() {
        StreamIdentity identity = null;
        try {
            identity = StreamIdentity.get(context);
            post(() -> listener.onState("校验授权"));
            if (StreamApps.pairStatus(identity, host, httpsPort) != 1) {
                throw new IllegalStateException("此主机尚未授权本设备，请先在 Sunshine 的 PIN Pairing 页面完成配对");
            }

            StreamLaunch.ServerInfo info = StreamLaunch.serverInfo(identity, host, httpsPort);
            int codecMask = info.codecMask != 0 ? info.codecMask : fallbackCodecMask;

            int chosenApp = appId;
            if (chosenApp <= 0) {
                post(() -> listener.onState("读取应用列表"));
                List<StreamApps.App> apps = StreamApps.fetch(identity, host, httpsPort);
                chosenApp = apps.isEmpty() ? 881448767 : apps.get(0).id;
            }

            byte[] riKey = new byte[16];
            new SecureRandom().nextBytes(riKey);
            int riKeyId = new SecureRandom().nextInt() & 0x7FFFFFFF;

            post(() -> listener.onState("协商串流会话"));
            StreamLaunch.LaunchResult launch = StreamLaunch.launch(identity, host, httpsPort,
                    chosenApp, WIDTH, HEIGHT, FPS, riKey, riKeyId);

            StreamDecoder dec = new StreamDecoder(surface, bridgeListener());
            decoder = dec;
            MoonBridge.setHooks(dec);
            ByteBuffer iv = ByteBuffer.allocate(16);
            iv.putInt(riKeyId);

            post(() -> listener.onState("建立数据通道"));
            int rc;
            synchronized (MoonBridge.class) {
                if (!running) { cleanup(identity); return; }
                rc = MoonBridge.startConnection(
                        host,
                        info.appVersion,
                        info.gfeVersion,
                        launch.sessionUrl,
                        codecMask,
                        WIDTH, HEIGHT, FPS, BITRATE_KBPS,
                        PACKET_SIZE,
                        0,   // LAN 串流
                        MoonBridge.AUDIO_CONFIGURATION_STEREO,
                        MoonBridge.VIDEO_FORMAT_H264 | MoonBridge.VIDEO_FORMAT_H265,
                        6000, // 客户端刷新率 x100
                        riKey,
                        iv.array(),
                        0,   // videoCapabilities：不请求多 slice
                        MoonBridge.COLORSPACE_REC_709,
                        MoonBridge.COLOR_RANGE_LIMITED);
            }
            Log.i(TAG, "startConnection returned " + rc);
            // LiStartConnection() 只是异步启动原生后台线程，返回 0 代表「已启动」，
            // 不是「连接结束」。这里必须保持 hooks / 解码器存活，清理交给
            // connectionTerminated 回调或显式 stop()。早期版本把它当阻塞调用，
            // finally 立刻拆掉解码器并 /cancel，实测日志表现为刚连上就
            // "No video traffic was ever received from the host!"。
            if (rc != MoonBridge.CONN_STATUS_OKAY) {
                post(() -> listener.onError("起流失败（错误码 " + rc + "）"));
                cleanup(identity);
                notifyTerminated(rc);
            }
        } catch (Throwable e) {
            Log.e(TAG, "connection failed", e);
            post(() -> listener.onError(e.getMessage() == null ? e.toString() : e.getMessage()));
            cleanup(identity);
            notifyTerminated(-1);
        }
    }

    /** 幂等清理：停止解码器、注销回调、通知服务端结束会话。 */
    private void cleanup(StreamIdentity identity) {
        if (!cleaned.compareAndSet(false, true)) return;
        StreamDecoder dec = decoder;
        if (dec != null) {
            dec.markStopping();
            dec.cleanupVideo();
            dec.cleanupAudio();
        }
        MoonBridge.setHooks(null);
        StreamIdentity id = identity;
        if (id == null) {
            try { id = StreamIdentity.get(context); } catch (Exception ignored) {}
        }
        if (id != null) StreamLaunch.cancel(id, host, httpsPort);
        running = false;
    }

    /** 可在任意线程调用；中断原生连接并释放解码器。 */
    public void stop() {
        if (cleaned.get() && !running) return;
        running = false;
        Thread thread = new Thread(() -> {
            StreamDecoder dec = decoder;
            if (dec != null) dec.markStopping();
            try { MoonBridge.interruptConnection(); } catch (Throwable ignored) {}
            synchronized (MoonBridge.class) {
                try { MoonBridge.stopConnection(); } catch (Throwable ignored) {}
            }
            cleanup(null);
            notifyTerminated(0);
        }, "vr-stream-stop");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isRunning() { return running; }

    private StreamDecoder.Listener bridgeListener() {
        return new StreamDecoder.Listener() {
            @Override public void onState(String message) { post(() -> listener.onState(message)); }
            @Override public void onError(String message) { post(() -> listener.onError(message)); }
            @Override public void onVideoSize(int width, int height) {
                post(() -> listener.onVideoSize(width, height));
            }
            @Override public void onTerminated(int errorCode) {
                cleanup(null);
                notifyTerminated(errorCode);
            }
            @Override public void onFramesDecoded(int frames) {
                post(() -> listener.onFramesDecoded(frames));
            }
        };
    }

    private void notifyTerminated(int code) {
        if (terminatedNotified) return;
        terminatedNotified = true;
        post(() -> listener.onTerminated(code));
    }

    private void post(Runnable action) {
        if (Looper.myLooper() == main.getLooper()) action.run();
        else main.post(action);
    }
}
