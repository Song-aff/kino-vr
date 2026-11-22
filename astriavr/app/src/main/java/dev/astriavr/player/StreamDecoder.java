package dev.astriavr.player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * S3b 阶段 3b：把官方 libmoonlight-core.so 的解包结果送进 MediaCodec，
 * 解码输出直接写到 {@link VrView} 的 {@code VideoOutput.surface}（背后是
 * OES SurfaceTexture）。因此 VrView 的双眼 / 大屏银幕 shader 不需要任何改动：
 * SurfaceTexture 一旦有新帧就会自己 requestFrame()。
 *
 * <p>与官方 MediaCodecDecoderRenderer 的差异：只保留 H.264 / HEVC 的
 * Annex-B 输入路径（Moonlight 送来的 decodeUnitData 已带 start code，
 * 官方也是原样 queue，不做 AVCC 转换）、同步输入 + 独立 drain 线程输出，
 * 去掉了 SPS patch、参考帧失效、HDR、自适应播放与性能覆盖层。</p>
 */
public final class StreamDecoder implements MoonBridge.Hooks {

    public interface Listener {
        void onState(String message);
        void onVideoSize(int width, int height);
        void onError(String message);
        void onTerminated(int errorCode);
        void onFramesDecoded(int frames);
    }

    private static final String TAG = "VRStreamDecoder";
    private static final int INPUT_TIMEOUT_US = 10000;

    private final Surface surface;
    private final Listener listener;
    private final ArrayList<byte[]> vpsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> spsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> ppsBuffers = new ArrayList<>();

    private volatile MediaCodec codec;
    private volatile AudioTrack audio;
    private volatile boolean stopping;
    private volatile int frameCount;
    private Thread drainThread;
    private int lastFrameNumber = -1;
    private boolean submittedCsd;
    private String mime = "video/avc";

    public StreamDecoder(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // 视频
    // ------------------------------------------------------------------

    @Override
    public int setupVideo(int format, int width, int height, int redrawRate) {
        mime = mimeFor(format);
        try {
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(mime, width, height);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, redrawRate);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            }
            MediaCodec created = MediaCodec.createDecoderByType(mime);
            created.configure(mediaFormat, surface, null, 0);
            created.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            created.start();
            codec = created;
            submittedCsd = false;
            clearCsd();
            listener.onVideoSize(width, height);
            startDrain();
            Log.i(TAG, "decoder ready: " + mime + " " + width + "x" + height + "@" + redrawRate);
            return MoonBridge.DR_OK;
        } catch (Exception e) {
            Log.e(TAG, "setupVideo failed", e);
            listener.onError("视频解码器创建失败：" + e.getMessage());
            return MoonBridge.DR_NEED_IDR;
        }
    }

    @Override
    public void startVideo() {}

    @Override
    public void stopVideo() {}

    @Override
    public void cleanupVideo() {
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) {}
            try { c.release(); } catch (Exception ignored) {}
        }
        if (drainThread != null) {
            drainThread.interrupt();
            drainThread = null;
        }
    }

    @Override
    public int submitDecodeUnit(byte[] data, int length, int type, int frameNumber,
                                int frameType, char hostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs) {
        if (stopping) return MoonBridge.DR_OK;
        MediaCodec c = codec;
        if (c == null) return MoonBridge.DR_NEED_IDR;
        try {
            // IDR 帧开头会依次送来 VPS / SPS / PPS，先攒起来再一起作为 codec-config 提交。
            if (frameNumber != lastFrameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
                clearCsd();
                submittedCsd = false;
            }
            lastFrameNumber = frameNumber;

            if (type == MoonBridge.BUFFER_TYPE_VPS) {
                vpsBuffers.add(copy(data, length));
                return MoonBridge.DR_OK;
            }
            if (type == MoonBridge.BUFFER_TYPE_SPS) {
                spsBuffers.add(copy(data, length));
                return MoonBridge.DR_OK;
            }
            if (type == MoonBridge.BUFFER_TYPE_PPS) {
                ppsBuffers.add(copy(data, length));
                return MoonBridge.DR_OK;
            }

            if (!submittedCsd && hasCsd()) {
                if (!queue(c, joinCsd(), 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                    return MoonBridge.DR_NEED_IDR;
                }
                submittedCsd = true;
            }
            if (!queue(c, copy(data, length), timestampUs(), 0)) {
                return MoonBridge.DR_NEED_IDR;
            }
            frameCount++;
            if (frameCount == 1 || frameCount % 60 == 0) {
                listener.onFramesDecoded(frameCount);
            }
            return MoonBridge.DR_OK;
        } catch (Exception e) {
            Log.w(TAG, "submitDecodeUnit failed", e);
            return MoonBridge.DR_NEED_IDR;
        }
    }

    private boolean queue(MediaCodec c, byte[] payload, long timestampUs, int flags) {
        int index = c.dequeueInputBuffer(INPUT_TIMEOUT_US);
        if (index < 0) return false;
        ByteBuffer buffer = c.getInputBuffer(index);
        if (buffer == null) return false;
        buffer.clear();
        buffer.put(payload);
        c.queueInputBuffer(index, 0, payload.length, timestampUs, flags);
        return true;
    }

    private void startDrain() {
        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                MediaCodec c = codec;
                if (c == null) break;
                try {
                    int out = c.dequeueOutputBuffer(info, INPUT_TIMEOUT_US);
                    if (out >= 0) {
                        c.releaseOutputBuffer(out, true);
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "output format: " + c.getOutputFormat());
                    }
                } catch (IllegalStateException e) {
                    break;
                }
            }
        }, "vr-stream-drain");
        drainThread.start();
    }

    // ------------------------------------------------------------------
    // 音频：moonlight-common-c 已完成 Opus 解码，回调里拿到的是 PCM
    // ------------------------------------------------------------------

    @Override
    public int setupAudio(int audioConfiguration, int sampleRate, int samplesPerFrame) {
        try {
            int minBuffer = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minBuffer, samplesPerFrame * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            audio = track;
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "setupAudio failed", e);
            return -1;
        }
    }

    @Override
    public void startAudio() {}

    @Override
    public void stopAudio() {}

    @Override
    public void cleanupAudio() {
        AudioTrack t = audio;
        audio = null;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void playSample(short[] pcm) {
        AudioTrack t = audio;
        if (t != null && !stopping) t.write(pcm, 0, pcm.length);
    }

    // ------------------------------------------------------------------
    // 连接生命周期
    // ------------------------------------------------------------------

    @Override public void stageStarting(String stageName) { listener.onState(stageName); }
    @Override public void stageComplete(String stageName) {}
    @Override public void stageFailed(String stageName, int portFlags, int errorCode) {
        listener.onError(stageName + " 失败（错误码 " + errorCode + "）");
    }
    @Override public void connectionStarted() { listener.onState("已连接"); }
    @Override public void connectionTerminated(int errorCode) { listener.onTerminated(errorCode); }
    @Override public void connectionStatusUpdate(int status) {}
    @Override public void setHdrMode(boolean enabled, byte[] metadata) {}

    /** 停止前调用，避免 drain 线程与 MediaCodec 释放互相等待。 */
    public void markStopping() {
        stopping = true;
    }

    public int frameCount() { return frameCount; }

    // ------------------------------------------------------------------

    private static String mimeFor(int format) {
        if ((format & (MoonBridge.VIDEO_FORMAT_H265 | MoonBridge.VIDEO_FORMAT_H265_MAIN10)) != 0) {
            return "video/hevc";
        }
        if ((format & (MoonBridge.VIDEO_FORMAT_AV1_MAIN8 | MoonBridge.VIDEO_FORMAT_AV1_MAIN10)) != 0) {
            return "video/av01";
        }
        return "video/avc";
    }

    private void clearCsd() {
        vpsBuffers.clear();
        spsBuffers.clear();
        ppsBuffers.clear();
    }

    private boolean hasCsd() {
        return !vpsBuffers.isEmpty() || !spsBuffers.isEmpty() || !ppsBuffers.isEmpty();
    }

    private byte[] joinCsd() {
        List<byte[]> all = new ArrayList<>();
        all.addAll(vpsBuffers);
        all.addAll(spsBuffers);
        all.addAll(ppsBuffers);
        int total = 0;
        for (byte[] part : all) total += part.length;
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : all) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] copy(byte[] src, int length) {
        byte[] out = new byte[length];
        System.arraycopy(src, 0, out, 0, length);
        return out;
    }

    private static long timestampUs() {
        return System.nanoTime() / 1000L;
    }
}
