package dev.astriavr.player;

import com.limelight.nvstream.jni.MoonBridge;

import junit.framework.TestCase;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * S3b 阶段 2 真机回归：验证从 moonlight-android v12.2 官方 GPLv3 APK 提取的
 * libmoonlight-core.so 能被本 APP 正常加载。
 *
 * <p>这颗 .so 在加载/初始化时会反查 com/limelight/nvstream/jni/MoonBridge 的
 * 全部 bridge* 静态方法 ID。只要有一个方法名或 JNI 签名对不上，类加载就会失败，
 * 所以「本测试能跑起来」本身就是最关键的断言。</p>
 */
public class MoonBridgeTest extends TestCase {

    public void testLibraryLoadsAndCallbacksResolve() {
        // 触发类初始化 -> System.loadLibrary("moonlight-core") -> native init()
        // 若 JNI_OnLoad / init 里 GetStaticMethodID 失败，这里会直接抛 UnsatisfiedLinkError
        // 或进程 abort，测试不会通过。
        assertEquals(0, MoonBridge.getPendingVideoFrames());
        assertEquals(0, MoonBridge.getPendingAudioDuration());

        // 纯本地查询类 native 调用，验证符号能解析且参数传递正确
        MoonBridge.getStageName(0);
        MoonBridge.getPortFlagsFromStage(0);
        MoonBridge.stringifyPortFlags(MoonBridge.ML_PORT_FLAG_ALL, ",");
        MoonBridge.getLaunchUrlQueryParameters();
        MoonBridge.getEstimatedRttInfo();

        List<String> nativeMethods = new ArrayList<String>();
        for (Method m : MoonBridge.class.getDeclaredMethods()) {
            if (Modifier.isNative(m.getModifiers())) nativeMethods.add(m.getName());
        }
        assertEquals("native 方法数应与 .so 导出的 32 个 JNI 符号一致", 32, nativeMethods.size());
    }

    public void testBridgeCallbackSignatures() throws Exception {
        // 逐条核对原生层会缓存的 JNI 描述符，避免后续改动悄悄破坏签名
        assertSignature("bridgeDrSetup", int.class,
                int.class, int.class, int.class, int.class);
        assertSignature("bridgeDrSubmitDecodeUnit", int.class,
                byte[].class, int.class, int.class, int.class, int.class,
                char.class, long.class, long.class);
        assertSignature("bridgeArInit", int.class, int.class, int.class, int.class);
        assertSignature("bridgeArPlaySample", void.class, short[].class);
        assertSignature("bridgeClStageStarting", void.class, int.class);
        assertSignature("bridgeClStageComplete", void.class, int.class);
        assertSignature("bridgeClStageFailed", void.class, int.class, int.class);
        assertSignature("bridgeClConnectionStarted", void.class);
        assertSignature("bridgeClConnectionTerminated", void.class, int.class);
        assertSignature("bridgeClConnectionStatusUpdate", void.class, int.class);
        assertSignature("bridgeClSetHdrMode", void.class, boolean.class, byte[].class);
        assertSignature("bridgeClRumble", void.class, short.class, short.class, short.class);
        assertSignature("bridgeClRumbleTriggers", void.class, short.class, short.class, short.class);
        assertSignature("bridgeClSetMotionEventState", void.class, short.class, byte.class, short.class);
        assertSignature("bridgeClSetControllerLED", void.class,
                short.class, byte.class, byte.class, byte.class);
        assertSignature("bridgeDrStart", void.class);
        assertSignature("bridgeDrStop", void.class);
        assertSignature("bridgeDrCleanup", void.class);
        assertSignature("bridgeArStart", void.class);
        assertSignature("bridgeArStop", void.class);
        assertSignature("bridgeArCleanup", void.class);
    }

    public void testNativeLibraryPackaged() throws Exception {
        // 不依赖 androidx.test：通过 ActivityThread 反射拿目标 APP 的 base.apk。
        // Android 6+ 未解压的 .so 直接从 APK 页映射加载（extractNativeLibs=false），
        // 此时 nativeLibraryDir 下不会有实体文件，必须查 APK 条目本身。
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object app = at.getMethod("currentApplication").invoke(null);
        assertNotNull("拿不到目标 APP Context", app);
        android.content.Context ctx = (android.content.Context) app;
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(ctx.getApplicationInfo().sourceDir);
        java.util.zip.ZipEntry entry =
                zip.getEntry("lib/arm64-v8a/" + System.mapLibraryName("moonlight-core"));
        assertNotNull("arm64 原生库未打进 APK", entry);
        assertTrue("原生库大小异常", entry.getSize() > 1024 * 1024);
        zip.close();
        System.out.println("S3B-NATIVE: " + entry.getName() + " (" + entry.getSize() + " bytes)");
    }

    private static void assertSignature(String name, Class<?> returnType, Class<?>... params)
            throws Exception {
        Method m = MoonBridge.class.getDeclaredMethod(name, params);
        assertEquals(name + " 返回类型", returnType, m.getReturnType());
        assertTrue(name + " 必须是 public static",
                Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()));
    }
}
