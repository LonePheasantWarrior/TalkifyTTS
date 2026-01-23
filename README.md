# Talkify

云端大模型驱动的 Android 语音合成([TTS](https://developer.android.com/reference/android/speech/tts/TextToSpeech))应用。  
目前接入了阿里云百炼的[通义千问3-TTS-Flash服务](https://bailian.console.aliyun.com/cn-beijing/?spm=5176.29619931.J_SEsSjsNv72yRuRFS2VknO.2.74cd10d7e5xOeO&tab=model#/efm/model_experience_center/voice?currentTab=voiceTts)作为功能引擎。

在阿里云百炼的[密钥管理](https://bailian.console.aliyun.com/cn-beijing/?spm=a2c4g.11186623.nav-v2-dropdown-menu-0.d_main_2_0.57a349e5ACzyY3&tab=model&scm=20140722.M_10904463._.V_1#/api-key)页面下申请对应的`ApiKey`以使用该应用。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3 Expressive
- **SDK**: minSdk 30, targetSdk 36
- **构建**: Gradle 8.13 + AGP 8.13.2

## 项目架构

```
app/src/main/java/com/github/lonepheasantwarrior/talkify/
├── MainActivity.kt              # 应用入口
├── domain/                      # 领域层（业务逻辑核心）
│   ├── model/                   # 领域模型
│   └── repository/              # 仓储接口定义
├── infrastructure/              # 基础设施层（外部服务集成）
│   ├── engine/                  # 引擎特定实现
│   └── app/                     # 应用级配置实现
│       └── permission/          # 权限检查
│           ├── PermissionChecker.kt         # 权限检查工具类
│           └── NetworkConnectivityChecker.kt # 网络连通性检查工具类
├── service/                     # 服务层（TTS 引擎服务）
│   └── engine/                  # 引擎抽象层
└── ui/                          # 表现层（UI 组件）
    ├── components/              # UI 组件
    ├── screens/                 # 界面
    └── theme/                   # 主题配置
```

## 目录与文件职能

| 目录/文件 | 职能 |
|----------|------|
| **domain/** | |
| `TtsModels.kt` | TTS 引擎领域模型 |
| `EngineConfig.kt` | 引擎配置（apiKey, voiceId） |
| `TtsEngineRegistry.kt` | 引擎注册表 |
| `*Repository.kt` | 仓储接口定义 |
| **infrastructure/** | |
| `Qwen3Tts*Repository.kt` | 通义千问3仓储实现 |
| `SharedPreferencesAppConfigRepository.kt` | 应用配置实现 |
| **service/** | |
| `TalkifyTtsService.kt` | TTS 服务（继承 TextToSpeechService） |
| `TalkifyTtsDemoService.kt` | 语音预览服务 |
| `TtsEngineApi.kt` | 引擎抽象接口 |
| `Qwen3TtsEngine.kt` | 通义千问3引擎实现 |
| **ui/** | |
| `MainScreen.kt` | 主界面 |
| `*BottomSheet.kt` | 底部弹窗 |
| `*Preview.kt` | 语音预览 |
| `*Selector.kt` | 引擎选择器 |

## 已实现功能

1. **引擎切换** - SegmentedButton 风格引擎选择
2. **语音预览** - 文本输入 + 声音选择 + 播放控制
3. **引擎配置** - API Key 管理 + 声音选择 + 持久化存储
4. **系统 TTS** - 请求队列 + 速率控制 + 错误处理

## 构建

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 代码检查
./gradlew lint
```

**输出**: `app/build/outputs/apk/debug/app-debug.apk`

## 支持的引擎

| 引擎 ID | 服务商 | 语言支持 |
|---------|--------|---------|
| qwen3-tts | 阿里云通义千问 | zh, en, de, fr, es, pt, it, ja, ko, ru |
