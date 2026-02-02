<!-- Mascot on the Right (Float) -->
<img src="doc/images/Screenshot_talkify.webp" align="right" width="25%" alt="Talkify Screenshot" style="margin-left: 20px; margin-bottom: 20px; border-radius: 8px;">

# Talkify

#### 云端大模型驱动的 Android TTS 引擎

Talkify 是一款基于 Android 的现代化 TTS 连接器。它不生产语音，而是作为桥梁，将云端顶尖大模型（通义千问、豆包）的高质量拟人语音合成能力，通过 Android 标准 Text-to-Speech 接口赋予您的系统和阅读软件。

<p>
  <img src="https://img.shields.io/badge/Language-Kotlin-7f52ff?style=flat-square&logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose" alt="Compose"/>
  <img src="https://img.shields.io/badge/Material-Design%203-6200EE?style=flat-square&logo=materialdesign" alt="Material 3"/>
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android" alt="Android"/>
</p>

让您的电子书朗读不再机械冰冷，而是充满情感与温度。

<br clear="both"/>

## ✨ 核心特性

- **🔌 多引擎支持**：内置 **阿里云通义千问** 和 **火山引擎豆包**，支持一键切换。
- **📱 系统级集成**：实现标准 Android TTS 接口，无缝支持 Legado（阅读）、Moon+ Reader（静读天下）等任意阅读软件。
- **⚡️ 流式合成**：采用流式传输技术（Streaming），大幅降低首字延迟，实现近乎实时的响应速度。
- **🛡️ 稳定后台**：完善的权限引导（网络、电池优化、通知），确保在后台长时间朗读不中断。
- **🎨 现代设计**：完全基于 Jetpack Compose 构建，遵循最新的 Material 3 Expressive 设计规范。

## 🧠 支持的引擎

| 引擎 ID | 名称 | 服务商 | 语言支持 | 特点         |
|:---:|:---|:---|:---|:-----------|
| **qwen3-tts** | 通义千问 3 | 阿里云百炼 | 🇨🇳 🇺🇸 🇩🇪 🇫🇷 🇪🇸 <br>🇵🇹 🇮🇹 🇯🇵 🇰🇷 🇷🇺 | 48种音色，响应快  |
| **seed-tts-2.0** | 豆包语音 2.0 | 火山引擎 | 🇨🇳 🇺🇸 | 16种音色，感情丰富 |

## 🛠️ 技术栈

- **语言**: Kotlin 2.3.0
- **架构**: MVVM (Model-View-ViewModel) + Clean Architecture
- **UI**: Jetpack Compose (BOM 2026.01.01) + Material 3 Expressive
- **网络**: OkHttp 4.12.0 (HTTP/2, Streaming)
- **最低兼容**: Android 11 (API 30)
- **目标版本**: Android 16 (API 36)

## 🚀 快速开始

### 前置准备

1.  **阿里云百炼**：前往 [控制台](https://bailian.console.aliyun.com/) 申请 API Key。
2.  **火山引擎**：前往 [控制台](https://console.volcengine.com/speech/new/setting/apikeys) 申请 API Key。

### 构建与运行

```bash
# 1. 克隆仓库
git clone https://github.com/LonePheasantWarrior/Talkify.git
cd Talkify

# 2. 检查代码
./gradlew lint

# 3. 编译 Debug 包
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 推荐搭配

Talkify 作为一个 TTS 引擎，最佳的使用场景是搭配优秀的电子书阅读器：
*   [Legado / 开源阅读](https://github.com/gedoor/legado)
*   [Moon+ Reader / 静读天下](https://play.google.com/store/apps/details?id=com.flyersoft.moonreader)
*   [Google Play Books / Google Play 图书](https://play.google.com/store/apps/details?id=com.google.android.apps.books)

## 📚 文档

详细的架构设计、代码规范和扩展指南，请参阅 [doc/开发指南.md](doc/开发指南.md)。

## ☕️ Buy Me a Mixue

如果您觉得 Talkify 对您有帮助，欢迎请我喝杯蜜雪冰城 🍦

<table>
  <tr>
    <td style="text-align: center;">
      <img src="doc/images/alipay_1769136488503.webp" width="200" alt="支付宝"/>
      <br>支付宝
    </td>
    <td style="text-align: center;">
      <img src="doc/images/wechat_1769136466823.webp" width="200" alt="微信"/>
      <br>微信
    </td>
  </tr>
</table>

## 🤝 致谢

*   [Trae](https://www.trae.cn)
*   [MiniMax M2.1](https://www.minimaxi.com/news/minimax-m21)
*   [Gemini CLI](https://geminicli.com)
*   [Gemini 3 Pro](https://deepmind.google/models/gemini/pro)
