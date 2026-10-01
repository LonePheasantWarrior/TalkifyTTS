import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 签名配置（§7.3）：优先读取 keystore.properties（已 gitignore，不入库）。
// 文件不存在时（本地 debug / 未配置密钥的 CI）不注册签名配置，release 产物
// 不签名；正式发布经 release.yml 从 GitHub Secrets 注入。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.github.lonepheasantwarrior.talkify"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.lonepheasantwarrior.talkify"
        minSdk = 30
        targetSdk = 37
        versionCode = 37
        versionName = "1.0.35"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 与 splits.abi.include 保持一致：universal 包收录的是通过 abiFilters 的全部 ABI
        // （splits.include 只约束独立 APK），x86 需在此排除——其引擎库仍含全量 onnxruntime
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // N19-g：匿名遥测端点与网站 ID 不入库（公开仓库不暴露自建服务器地址）。
        // 维护者经 local.properties（umami.endpoint / umami.websiteId，已 gitignore）
        // 或 CI 环境变量（UMAMI_ENDPOINT / UMAMI_WEBSITE_ID）注入；二者缺一即
        // 构建出"未配置遥测"的包，UmamiClient 侧自检短路、零网络流量
        val localProperties = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        // 经 providers.environmentVariable 读取（而非 System.getenv）：configuration
        // cache 将其追踪为输入，环境变量变化会触发重新配置，避免复用缓存条目打出旧值
        val umamiEndpoint = localProperties.getProperty("umami.endpoint")
            ?: providers.environmentVariable("UMAMI_ENDPOINT").orNull ?: ""
        val umamiWebsiteId = localProperties.getProperty("umami.websiteId")
            ?: providers.environmentVariable("UMAMI_WEBSITE_ID").orNull ?: ""
        buildConfigField("String", "UMAMI_ENDPOINT", "\"$umamiEndpoint\"")
        buildConfigField("String", "UMAMI_WEBSITE_ID", "\"$umamiWebsiteId\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            // 一次构建产出 arm64-v8a / armeabi-v7a / x86_64 独立 APK + universal 兜底包
            // x86 不再打包：真实设备不存在，且 static-link AAR 的 x86 引擎库仍含独立 onnxruntime（34.5MB）
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // 生成 BuildConfig（VERSION_NAME/DEBUG）：下载 UA 拼接真实版本号（P3-14）、
        // 日志门控等按构建类型区分的能力依赖该类
        buildConfig = true
    }
    lint {
        // 固化现有 warning 基线（41+ 条历史告警），CI 只对新引入的问题硬门禁（§7.4）
        baseline = file("lint-baseline.xml")
    }
    testOptions {
        unitTests {
            // 全局异常处理器单测会触达 android.jar 桩（如 TtsLogger 内的 Log 调用）：
            // 桩方法返回默认值而非抛 "Stub!" 异常，使 JVM 单测无需 Robolectric
            // 即可运行触碰 Android API 的代码路径（既有纯函数单测不受影响）
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull()?.identifier ?: "universal"
            // debug 加后缀，避免与 release 产物同名导致手动上传时拿错包
            val kind = if (variant.name == "debug") "-debug" else ""
            output.outputFileName.set("Talkify-v${output.versionName.get()}-${abi}${kind}.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 应用级前后台监听（ProcessLifecycleOwner），供遥测在每次回到前台时上报启动信号
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    // 本地 JVM 单测环境没有 Android 实现，提供真实的 org.json 以替代 android.jar 桩
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // ui-test-junit4 等测试依赖无独立版本号，需为 androidTest 配置单独引入 Compose BOM
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 阿里云百炼官方 DashScope SDK，用于通义千问3语音合成引擎
    implementation(libs.dashscope.sdk)

    // OkHttp 用于火山引擎 HTTP 流式 API，支持连接复用
    // 版本与 DashScope SDK 内置 OkHttp 保持一致（4.12.0）
    implementation(libs.okhttp)

    // 腾讯云流式 TTS SDK
    implementation(files("libs/stream_tts-release-v2.0.16-20260128-d80cafe.aar"))
    
    // JLayer 用于 MP3 流式解码
    implementation(libs.jlayer)

    // Sherpa-onnx 本地 TTS 推理引擎
    // 使用官方 static-link-onnxruntime 构建（onnxruntime 静态编入 jni 库并裁剪未用符号）；
    // JitPack 坐标产出的 AAR 动态链接全量 onnxruntime .so，4 ABI 打包时 APK 膨胀至 140MB+
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.1.aar"))

    // 压缩包解压（tar.bz2），用于解压 espeak-ng-data 等模型资源
    implementation(libs.commons.compress)
}