import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Properties
import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 签名配置（§7.3）：优先读取 keystore.properties（已 gitignore，不入库）。
// 文件不存在时（本地 debug / 未配置密钥的 CI）不注册签名配置，release 产物
// 不签名；正式发布经 release.yml 从 GitHub Secrets 注入。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// ==================== 遥测构建配置（严格校验与运行时等价） ====================
// 公开仓库不暴露自建服务器地址：维护者经 local.properties 或 CI 环境变量注入。
// 这里与 TelemetryConfigValidator 使用同一套规则；任一值非法都拒绝构建，避免
// 打出“看起来已启用、实际全部短路”的包。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val umamiEndpoint = (localProperties.getProperty("umami.endpoint")
    ?: providers.environmentVariable("UMAMI_ENDPOINT").orNull ?: "").trim()
val umamiWebsiteId = (localProperties.getProperty("umami.websiteId")
    ?: providers.environmentVariable("UMAMI_WEBSITE_ID").orNull ?: "").trim()
val debugUmamiEndpoint = (localProperties.getProperty("umami.debugEndpoint")
    ?: providers.environmentVariable("UMAMI_DEBUG_ENDPOINT").orNull ?: "").trim()
val debugUmamiWebsiteId = (localProperties.getProperty("umami.debugWebsiteId")
    ?: providers.environmentVariable("UMAMI_DEBUG_WEBSITE_ID").orNull ?: "").trim()
val telemetryRequired = (providers.gradleProperty("telemetryRequired").orNull
    ?: providers.environmentVariable("TALKIFY_REQUIRE_TELEMETRY").orNull)
    ?.equals("true", ignoreCase = true) == true

val TELEMETRY_UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** 返回错误说明；null 表示配置合法。空值由调用方按“未配置”处理。 */
fun telemetryConfigError(endpoint: String, websiteId: String): String? {
    if (endpoint.isBlank() || websiteId.isBlank()) return "endpoint 与 websiteId 必须同时提供"
    if (endpoint.any { it.isWhitespace() || it.isISOControl() }) return "endpoint 含空白或控制字符"
    if (!TELEMETRY_UUID_PATTERN.matches(websiteId)) return "websiteId 不是 UUID"
    if (runCatching { UUID.fromString(websiteId) }.isFailure) return "websiteId 不是 UUID"
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "endpoint 不是合法 URI"
    if (!uri.scheme.equals("https", ignoreCase = true)) return "endpoint 必须为 HTTPS"
    if (uri.host.isNullOrBlank()) return "endpoint 缺少 host"
    if (uri.port == 0 || uri.port > 65535) return "endpoint 端口非法"
    if (!uri.userInfo.isNullOrEmpty()) return "endpoint 不得包含凭据"
    if (uri.rawQuery != null) return "endpoint 不得包含 query"
    if (uri.rawFragment != null) return "endpoint 不得包含 fragment"
    return null
}

val releaseConfigError = if (umamiEndpoint.isBlank() && umamiWebsiteId.isBlank()) {
    null
} else {
    telemetryConfigError(umamiEndpoint, umamiWebsiteId)
}
if (releaseConfigError != null) {
    throw GradleException("遥测配置非法：$releaseConfigError")
}
if (telemetryRequired && (umamiEndpoint.isBlank() || umamiWebsiteId.isBlank())) {
    throw GradleException("遥测配置被声明为必需（telemetryRequired=true），但 umami.endpoint / umami.websiteId 缺失")
}
val telemetryStatus = if (umamiEndpoint.isBlank() || umamiWebsiteId.isBlank()) {
    "Telemetry: DISABLED (missing configuration)"
} else {
    "Telemetry: ENABLED (validated endpoint and website ID)"
}

val debugConfigError = if (debugUmamiEndpoint.isBlank() && debugUmamiWebsiteId.isBlank()) {
    null
} else {
    telemetryConfigError(debugUmamiEndpoint, debugUmamiWebsiteId)
}
if (debugConfigError != null) {
    throw GradleException("debug 遥测配置非法：$debugConfigError")
}
val debugTelemetryStatus = if (debugUmamiEndpoint.isBlank() || debugUmamiWebsiteId.isBlank()) {
    "Telemetry(debug): DISABLED (missing debug configuration)"
} else {
    "Telemetry(debug): ENABLED (validated debug endpoint and website ID)"
}

/** 把任意配置值转义为 Java/Kotlin 字符串字面量，避免引号/反斜杠注入 BuildConfig */
fun javaStringLiteral(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

val writeTelemetryStatus = tasks.register("writeTelemetryStatus") {
    val release = telemetryStatus
    val debug = debugTelemetryStatus
    val output = layout.buildDirectory.file("telemetry/telemetry-status.txt")
    inputs.property("releaseStatus", release)
    inputs.property("debugStatus", debug)
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("$release\n$debug\n")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(writeTelemetryStatus)
}

/**
 * 构建侧校验矩阵：与运行时单测使用同一组用例。
 * 只验证纯函数，不读取真实配置，也不访问网络。
 */
val telemetryValidationResults: List<String> = run {
    val validId = "123e4567-e89b-12d3-a456-426614174000"
    listOf(
        Triple("https://telemetry.example.com", validId, true),
        Triple("https://telemetry.example.com/umami", validId, true),
        Triple("https:///", validId, false),
        Triple("https://example.com/%zz", validId, false),
        Triple("https://user:pass@example.com", validId, false),
        Triple("https://example.com?x=1", validId, false),
        Triple("https://example.com#frag", validId, false),
        Triple("http://example.com", validId, false),
        Triple("https://example.com:99999", validId, false),
        Triple("https://example.com", "------------------------------------", false),
        Triple("https://example.com", "123e4567-e89b-12d3-a456-42661417400", false),
    ).map { (endpoint, websiteId, expectedValid) ->
        val actualValid = telemetryConfigError(endpoint, websiteId) == null
        "endpoint=$endpoint websiteId=$websiteId expected=$expectedValid actual=$actualValid ok=${actualValid == expectedValid}"
    }
}

tasks.register("verifyTelemetryConfigMatrix") {
    val results = telemetryValidationResults
    doLast {
        results.forEach { line ->
            check(line.endsWith("ok=true")) { "遥测配置校验矩阵不一致: $line" }
        }
    }
}

android {
    namespace = "com.github.lonepheasantwarrior.talkify"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.lonepheasantwarrior.talkify"
        minSdk = 24
        targetSdk = 37
        versionCode = 39
        versionName = "1.0.37"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 与 splits.abi.include 保持一致：universal 包收录的是通过 abiFilters 的全部 ABI
        // （splits.include 只约束独立 APK），x86 需在此排除——其引擎库仍含全量 onnxruntime
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // 匿名遥测配置在文件顶部统一解析并严格校验；这里只负责注入。
        // 经 providers.environmentVariable 读取（而非 System.getenv）：configuration
        // cache 将其追踪为输入，环境变量变化会触发重新配置，避免复用缓存条目打出旧值。
        if (telemetryStatus.contains("DISABLED")) {
            logger.lifecycle("提示：未注入生产 Umami 配置，release 包遥测将全部短路（发布记录须注明）")
        }
        buildConfigField("String", "UMAMI_ENDPOINT", javaStringLiteral(umamiEndpoint))
        buildConfigField("String", "UMAMI_WEBSITE_ID", javaStringLiteral(umamiWebsiteId))
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
        debug {
            // U13：debug 联调只使用独立测试站点；未提供 debug 配置时保持空值，
            // 绝不回退到生产 endpoint
            buildConfigField("String", "UMAMI_ENDPOINT", javaStringLiteral(debugUmamiEndpoint))
            buildConfigField("String", "UMAMI_WEBSITE_ID", javaStringLiteral(debugUmamiWebsiteId))
        }
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
    // 对话模式配置持久化（DataStore Preferences）
    implementation(libs.androidx.datastore.preferences)
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