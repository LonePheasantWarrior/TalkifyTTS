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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

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

# 保留无参构造函数（Gson 需要）
-keepclassmembers class * {
    public <init>(***);
}

# 保留枚举类（SDK 内部可能使用）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Kotlin 元数据（SDK 可能使用 Kotlin）
-keep class kotlin.Metadata { *; }
-keep class kotlin.** { *; }

# 保留 Kotlin 协程相关（SDK 使用协程）
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
