package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder

/**
 * TTS 语音合成事件信息收集追踪器
 *
 * 以 "providerId_modelId" 为维度进行限频上报，兼顾信息价值与免费套餐的配额限制。
 *
 * 限频规则：
 * 1. 与上一次使用的供应商/模型组合不一致 → 立即上报并重置该组合计数
 * 2. 同一供应商/模型组合累计合成 15 次 → 上报一次并重置计数
 *
 * 每个 "providerId_modelId" 组合独立计数，互不干扰。
 * 通过 [TalkifyTelemetry] 统一上报，不直接依赖 Aptabase SDK。
 *
 * @see TalkifyTelemetry
 */
object TtsTelemetryTracker {

    private const val PREFS_NAME = "talkify_tts_telemetry"
    private const val KEY_LAST_COMBO = "last_combo"

    /**
     * 判断是否需要上报当前合成事件，满足条件时通过 [TalkifyTelemetry] 上报。
     *
     * 应在语音合成主作业开始前调用。Aptabase SDK 本身异步批量提交，
     * 因此此方法不会阻塞合成流程。
     *
     * @param providerId 供应商 ID（如 "aliyunBailian", "volcengine"）
     * @param modelId    有效模型 ID（已解析默认值，可能为空字符串）
     * @param textLength 待合成文字字数
     */
    fun trackIfNeeded(providerId: String, modelId: String, textLength: Int) {
        val combo = if (modelId.isNotBlank()) "${providerId}_${modelId}" else providerId
        val prefs = prefs() ?: return

        val lastCombo = prefs.getString(KEY_LAST_COMBO, null)
        val cumulativeCount = prefs.getInt(countKey(combo), 0) + 1

        val shouldTrack = combo != lastCombo || cumulativeCount >= 15

        // 合并一次 SharedPreferences 写入，更新计数和最近组合
        prefs.edit {
            putInt(countKey(combo), if (shouldTrack) 0 else cumulativeCount)
            if (combo != lastCombo) {
                putString(KEY_LAST_COMBO, combo)
            }
        }

        if (shouldTrack) {
            TalkifyTelemetry.trackEvent("tts_$combo", mapOf("text_length" to textLength))
        }
    }

    // ==================== 内部实现 ====================

    private fun prefs(): SharedPreferences? {
        val context = TalkifyAppHolder.getContext() ?: return null
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun countKey(combo: String) = "cnt_$combo"
}
