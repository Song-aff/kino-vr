# Preserve useful exception line numbers in the locally distributed build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# libmoonlight-core.so resolves Java_com_limelight_nvstream_jni_MoonBridge_* symbols
# and caches GetStaticMethodID for every bridgeDr*/bridgeAr*/bridgeCl* callback.
# Obfuscating this class or any member breaks native -> Java callbacks at runtime.
-keep class com.limelight.nvstream.jni.MoonBridge { *; }
