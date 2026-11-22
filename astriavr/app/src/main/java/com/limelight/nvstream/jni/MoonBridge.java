package com.limelight.nvstream.jni;

/**
 * S3b 阶段 2：Moonlight 官方 GPLv3 原生库（libmoonlight-core.so）的 JNI 桥接类。
 *
 * <p>JNI 符号是按「包名 + 类名 + 方法名」静态绑定的，本类必须与官方保持
 * 完全一致的包名和类名，否则 .so 里的 Java_com_limelight_nvstream_jni_MoonBridge_*
 * 符号无法解析。二进制取自 moonlight-android v12.2 的 app-nonRoot-release.apk，
 * 仅提取 arm64-v8a；它只依赖 libc/libm/libdl/liblog，且只反查本一个类。</p>
 *
 * <p>与官方实现的区别：不引入 NvConnectionListener / VideoDecoderRenderer /
 * AudioRenderer 那套类层次。原生层只会回调本类的 bridgeDr* / bridgeAr* / bridgeCl*
 * 静态方法（签名已逐条从 .so 的 JNI 描述符核对），这里统一转发给可插拔的
 * {@link Hooks}，由 APK 侧自己实现 MediaCodec 解码并输出到大屏银幕。</p>
 *
 * <p><b>混淆注意</b>：任何开启 minify 的构建都必须 keep 本类全部成员，
 * 否则原生层 GetStaticMethodID 会失败。</p>
 */
public final class MoonBridge {

    private MoonBridge() {}

    // ---- 视频 / 连接常量（取自官方 MoonBridge.java，与 Limelight.h 一致）----
    public static final int VIDEO_FORMAT_H264 = 0x0001;
    public static final int VIDEO_FORMAT_H265 = 0x0100;
    public static final int VIDEO_FORMAT_H265_MAIN10 = 0x0200;
    public static final int VIDEO_FORMAT_AV1_MAIN8 = 0x1000;
    public static final int VIDEO_FORMAT_AV1_MAIN10 = 0x2000;
    public static final int BUFFER_TYPE_PICDATA = 0;
    public static final int BUFFER_TYPE_SPS = 1;
    public static final int BUFFER_TYPE_PPS = 2;
    public static final int BUFFER_TYPE_VPS = 3;
    public static final int FRAME_TYPE_PFRAME = 0;
    public static final int FRAME_TYPE_IDR = 1;
    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;
    public static final int COLORSPACE_REC_709 = 1;
    public static final int COLOR_RANGE_LIMITED = 0;
    public static final int CONN_STATUS_OKAY = 0;
    public static final int CONN_STATUS_POOR = 1;
    public static final int ML_ERROR_GRACEFUL_TERMINATION = 0;
    public static final int ML_PORT_FLAG_ALL = 0xFFFFFFFF;
    public static final int AUDIO_CONFIGURATION_STEREO = 0x000302CA;

    public static int CAPABILITY_SLICES_PER_FRAME(byte slices) {
        return slices << 24;
    }

    static {
        System.loadLibrary("moonlight-core");
        init();
    }

    /** 原生层回调的可插拔实现；未设置时所有回调安全空转。 */
    public interface Hooks {
        int setupVideo(int videoFormat, int width, int height, int redrawRate);
        void startVideo();
        void stopVideo();
        void cleanupVideo();
        int submitDecodeUnit(byte[] data, int length, int type, int frameNumber,
                             int frameType, char hostProcessingLatency,
                             long receiveTimeUs, long enqueueTimeUs);

        int setupAudio(int audioConfiguration, int sampleRate, int samplesPerFrame);
        void startAudio();
        void stopAudio();
        void cleanupAudio();
        void playSample(short[] pcm);

        void stageStarting(String stageName);
        void stageComplete(String stageName);
        void stageFailed(String stageName, int portFlags, int errorCode);
        void connectionStarted();
        void connectionTerminated(int errorCode);
        void connectionStatusUpdate(int status);
        void setHdrMode(boolean enabled, byte[] metadata);
    }

    private static volatile Hooks hooks = null;

    public static void setHooks(Hooks value) {
        hooks = value;
    }

    public static Hooks getHooks() {
        return hooks;
    }

    // ------------------------------------------------------------------
    // 原生层回调。方法名 / 参数签名必须与 .so 里缓存的 JNI 描述符逐字一致：
    //   (IIII)I  ()V  ([BIIIICJJ)I  (III)I  ([S)V  (I)V  (II)V
    //   (SSS)V   (Z[B)V  (SBS)V  (SBBB)V
    // ------------------------------------------------------------------

    public static int bridgeDrSetup(int videoFormat, int width, int height, int redrawRate) {
        Hooks h = hooks;
        return h != null ? h.setupVideo(videoFormat, width, height, redrawRate) : DR_OK;
    }

    public static void bridgeDrStart() {
        Hooks h = hooks;
        if (h != null) h.startVideo();
    }

    public static void bridgeDrStop() {
        Hooks h = hooks;
        if (h != null) h.stopVideo();
    }

    public static void bridgeDrCleanup() {
        Hooks h = hooks;
        if (h != null) h.cleanupVideo();
    }

    public static int bridgeDrSubmitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength,
                                               int decodeUnitType, int frameNumber, int frameType,
                                               char frameHostProcessingLatency,
                                               long receiveTimeUs, long enqueueTimeUs) {
        Hooks h = hooks;
        return h != null
                ? h.submitDecodeUnit(decodeUnitData, decodeUnitLength, decodeUnitType, frameNumber,
                        frameType, frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs)
                : DR_OK;
    }

    public static int bridgeArInit(int audioConfiguration, int sampleRate, int samplesPerFrame) {
        Hooks h = hooks;
        return h != null ? h.setupAudio(audioConfiguration, sampleRate, samplesPerFrame) : 0;
    }

    public static void bridgeArStart() {
        Hooks h = hooks;
        if (h != null) h.startAudio();
    }

    public static void bridgeArStop() {
        Hooks h = hooks;
        if (h != null) h.stopAudio();
    }

    public static void bridgeArCleanup() {
        Hooks h = hooks;
        if (h != null) h.cleanupAudio();
    }

    public static void bridgeArPlaySample(short[] pcmData) {
        Hooks h = hooks;
        if (h != null) h.playSample(pcmData);
    }

    public static void bridgeClStageStarting(int stage) {
        Hooks h = hooks;
        if (h != null) h.stageStarting(safeStageName(stage));
    }

    public static void bridgeClStageComplete(int stage) {
        Hooks h = hooks;
        if (h != null) h.stageComplete(safeStageName(stage));
    }

    public static void bridgeClStageFailed(int stage, int errorCode) {
        Hooks h = hooks;
        if (h != null) h.stageFailed(safeStageName(stage), getPortFlagsFromStage(stage), errorCode);
    }

    public static void bridgeClConnectionStarted() {
        Hooks h = hooks;
        if (h != null) h.connectionStarted();
    }

    public static void bridgeClConnectionTerminated(int errorCode) {
        Hooks h = hooks;
        if (h != null) h.connectionTerminated(errorCode);
    }

    public static void bridgeClConnectionStatusUpdate(int connectionStatus) {
        Hooks h = hooks;
        if (h != null) h.connectionStatusUpdate(connectionStatus);
    }

    public static void bridgeClSetHdrMode(boolean enabled, byte[] hdrMetadata) {
        Hooks h = hooks;
        if (h != null) h.setHdrMode(enabled, hdrMetadata);
    }

    // 下面 5 个回调在 S4 手柄之前没有消费者，但原生层会查它们的 methodID，
    // 必须存在且签名一致，否则加载 / 连接时会失败。
    public static void bridgeClRumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {}
    public static void bridgeClRumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {}
    public static void bridgeClSetMotionEventState(short controllerNumber, byte eventType, short sampleRateHz) {}
    public static void bridgeClSetControllerLED(short controllerNumber, byte r, byte g, byte b) {}

    private static String safeStageName(int stage) {
        try {
            String name = getStageName(stage);
            return name != null ? name : ("stage " + stage);
        } catch (Throwable ignored) {
            return "stage " + stage;
        }
    }

    // ------------------------------------------------------------------
    // native 方法。清单与 .so 导出的 32 个 Java_com_limelight_... 符号一一对应。
    // ------------------------------------------------------------------

    public static native int startConnection(String address, String appVersion, String gfeVersion,
                                             String rtspSessionUrl, int serverCodecModeSupport,
                                             int width, int height, int fps, int bitrate,
                                             int packetSize, int streamingRemotely,
                                             int audioConfiguration, int supportedVideoFormats,
                                             int clientRefreshRateX100, byte[] riAesKey,
                                             byte[] riAesIv, int videoCapabilities,
                                             int colorSpace, int colorRange);

    public static native void stopConnection();
    public static native void interruptConnection();
    public static native void sendMouseMove(short deltaX, short deltaY);
    public static native void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight);
    public static native void sendMouseMoveAsMousePosition(short deltaX, short deltaY,
                                                           short referenceWidth, short referenceHeight);
    public static native void sendMouseButton(byte buttonEvent, byte mouseButton);
    public static native void sendMultiControllerInput(short controllerNumber, short activeGamepadMask,
                                                       int buttonFlags, byte leftTrigger, byte rightTrigger,
                                                       short leftStickX, short leftStickY,
                                                       short rightStickX, short rightStickY);
    public static native int sendTouchEvent(byte eventType, int pointerId, float x, float y,
                                             float pressure, float contactAreaMajor,
                                             float contactAreaMinor, short rotation);
    public static native int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                                          float pressure, float contactAreaMajor, float contactAreaMinor,
                                          short rotation, byte tilt);
    public static native int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask,
                                                        byte type, int supportedButtonFlags, short capabilities);
    public static native int sendControllerTouchEvent(byte controllerNumber, byte eventType,
                                                      int pointerId, float x, float y, float pressure);
    public static native int sendControllerMotionEvent(byte controllerNumber, byte motionType,
                                                       float x, float y, float z);
    public static native int sendControllerBatteryEvent(byte controllerNumber, byte batteryState,
                                                        byte batteryPercentage);
    public static native void sendKeyboardInput(short keyMap, byte keyDirection, byte modifier, byte flags);
    public static native void sendMouseHighResScroll(short scrollAmount);
    public static native void sendMouseHighResHScroll(short scrollAmount);
    public static native void sendUtf8Text(String text);
    public static native String getStageName(int stage);
    public static native String findExternalAddressIP4(String stunHostName, int stunPort);
    public static native int getPendingAudioDuration();
    public static native int getPendingVideoFrames();
    public static native int testClientConnectivity(String testServerHostName, int referencePort, int testFlags);
    public static native int getPortFlagsFromStage(int stage);
    public static native int getPortFlagsFromTerminationErrorCode(int errorCode);
    public static native String stringifyPortFlags(int portFlags, String separator);
    public static native long getEstimatedRttInfo();
    public static native String getLaunchUrlQueryParameters();
    public static native byte guessControllerType(int vendorId, int productId);
    public static native boolean guessControllerHasPaddles(int vendorId, int productId);
    public static native boolean guessControllerHasShareButton(int vendorId, int productId);
    public static native void init();
}
