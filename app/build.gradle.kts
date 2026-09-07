import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 31
        versionName = "1.0.29"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 与 splits.abi.include 保持一致：universal 包收录的是通过 abiFilters 的全部 ABI
        // （splits.include 只约束独立 APK），x86 需在此排除——其引擎库仍含全量 onnxruntime
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
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