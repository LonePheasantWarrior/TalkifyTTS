package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelVoice
import com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlParser
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * ZipVoice 内置音色目录
 *
 * 音色清单定义在 res/xml/local_model_voices.xml，参考音频（24kHz 单声道
 * PCM16 wav）随 APK 内置于 assets/voices/，无需用户下载。
 * 解析结果进程内缓存（资源内容不可变）。
 */
object LocalVoiceCatalog {

    private const val TAG = "LocalVoiceCatalog"

    /** assets 内参考音频的根目录 */
    const val ASSETS_DIR = "voices"

    @Volatile
    private var cached: List<LocalModelVoice>? = null

    /**
     * 获取内置音色列表
     *
     * XML 缺失或解析失败时返回空列表，调用方应回退注册表音色。
     */
    fun getVoices(): List<LocalModelVoice> {
        cached?.let { return it }

        val context = TalkifyAppHolder.getContext() ?: run {
            TtsLogger.w("Context unavailable, bundled voice catalog empty", tag = TAG)
            return emptyList()
        }

        val voices = try {
            VoiceXmlParser.parse(context, R.xml.local_model_voices).map { entry ->
                LocalModelVoice(
                    voiceId = entry.id,
                    displayName = entry.displayName,
                    language = entry.language.ifBlank { "zh" },
                    referenceFileName = entry.referenceFileName,
                    referenceText = entry.referenceText,
                    isBundled = true
                )
            }
        } catch (e: Exception) {
            TtsLogger.e("Failed to parse bundled voice catalog", throwable = e, tag = TAG)
            emptyList()
        }

        cached = voices
        return voices
    }
}
