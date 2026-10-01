# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# ==================== 崩溃可观测性（§7.3） ====================
# 保留行号：release 崩溃堆栈需配合 mapping.txt 才可解读（CI release.yml 随
# Release 归档 mapping.txt）；无行号时 app_crash 遥测收集的是废数据
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==================== 阿里云 DashScope SDK ProGuard 规则 ====================
# 修复通义千问3语音合成在 Release 模式下崩溃的问题
# 问题原因：SDK 内部使用 Gson 进行 JSON 解析，类被混淆后导致反射失败

# 保留 DashScope SDK 所有类
-keep class com.alibaba.dashscope.** { *; }

# 保留 SDK 内部使用的 Gson 相关类
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# 保留使用 Gson 注解的类
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 保留所有可能用于 JSON 序列化的内部类
-keepclassmembers class * {
    @com.google.gson.annotations.Expose <fields>;
}

# 保留枚举类（SDK 内部可能使用）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Kotlin 元数据（R8 需要 kotlin.Metadata 才能正确处理 Kotlin 代码；
# 注意不可用 -keep class kotlin.** { *; } 全量保留标准库——那会整体关闭
# stdlib 收缩，显著膨胀 APK（P3-28））
-keep class kotlin.Metadata { *; }

# 保留 Kotlin 协程相关（SDK 使用协程）
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ==================== 腾讯云流式 TTS SDK ProGuard 规则 ====================
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizer { *; }
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerRequest { *; }
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerListener { *; }
-keep class com.tencent.cloud.stream.tts.SpeechSynthesizer** { *; }
-keep class com.tencent.cloud.stream.tts.FlowingSpeechSynthesizerResponse { *; }
-keep class com.tencent.cloud.stream.tts.core.ws.CommonRequest { *; }
-keepclassmembers class * extends com.tencent.cloud.stream.tts.core.ws.CommonRequest { *; }

# ==================== Lombok ProGuard 规则 ====================
# Lombok 是编译期注解处理器，运行时类不会进入 APK，无需 -keep；
# 仅保留 -dontwarn 抑制混淆期对缺失类的警告（N11）
-dontwarn lombok.**
-dontwarn org.eclipse.**

# ==================== JLayer MP3 解码库 ProGuard 规则 ====================
# 修复 JLayer 在 Release 模式下崩溃的问题
# 问题原因：R8/ProGuard 混淆或剔除了 JLayer 的核心类和资源文件

# 保留 JLayer 所有类
-keep class javazoom.** { *; }

# 忽略 JLayer 中我们项目不使用的 Java Sound 和 Applet 相关类
-dontwarn java.applet.**
-dontwarn javax.sound.sampled.**
-keep class javazoom.jl.decoder.** { *; }
-keep class javazoom.jl.player.** { *; }

# ==================== sherpa-onnx 本地 TTS 引擎 ProGuard 规则 ====================
# 修复本地模型供应商在 Release 模式下 SIGABRT 崩溃的问题（AAR 自带的 consumer
# proguard.txt 为空，R8 混淆会破坏 JNI 按名字进行的反射访问）：
#   1) OfflineTts.newFromFile 用 GetFieldID 按字段名读取 OfflineTtsConfig 各字段，
#      字段被混淆后返回 null，触发 "JNI DETECTED ERROR: fid == null" 中止
#   2) 流式合成回调由 JNI 经 GetMethodID 查找 invoke([F)Ljava/lang/Integer;，
#      方法被重命名会触发 NoSuchMethodError
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.SherpaCallbackBridge { *; }
