# 第三方组件与版权声明

本应用基于 MIT 许可证发布（见 [LICENSE](LICENSE)）。除本仓库自有代码外，应用
分发/依赖以下第三方组件，其版权与许可归属各自的权利人：

## 随应用分发的运行时依赖

| 组件 | 许可证 | 说明 |
|---|---|---|
| AndroidX / Compose / Kotlin 标准库 | Apache-2.0 | Google / JetBrains |
| OkHttp | Apache-2.0 | Square |
| DashScope SDK（com.alibaba.dashscope） | Apache-2.0 | 阿里云 |
| 腾讯云流式 TTS SDK（stream_tts AAR） | 版权所有，腾讯云 | 经官方渠道获取的发行包 |
| sherpa-onnx（定制 AAR） | Apache-2.0 | k2-fsa；本仓使用静态链接 onnxruntime 的定制构建 |
| JLayer 1.0.1 | LGPL-2.1 | JavaZoom，MP3 解码；源码：http://www.javazoom.net/javalayer/sources.html |

> LGPL 合规说明：JLayer 以静态方式打包进 APK。如你认为该使用方式需要补充
> 说明或替换实现，欢迎提 issue 讨论；长期计划是用自研解码器替换 JLayer。

## 仓库内随附的第三方材料

| 内容 | 位置 | 说明 |
|---|---|---|
| 腾讯云流式 TTS Android Demo | `examples/QCloudTTS_*` | 腾讯云版权物，仅作接入参考，计划移出主仓库 |
| Umami 官方脚本 | `doc/umami/*.js` | Umami Software GmbH，MIT；版权与许可声明保留于文件头部 |

## 音色与模型素材

- 内置音色的参考音频均为**经授权**或官方 API 文档公开提供的素材。
- 本地模型（ZipVoice）由 sherpa-onnx 项目发布（Apache-2.0）；官方压缩包自带的
  `test_wavs` 测试音频（真人声纹）**不随本应用分发**，下载服务解压后即删除。
