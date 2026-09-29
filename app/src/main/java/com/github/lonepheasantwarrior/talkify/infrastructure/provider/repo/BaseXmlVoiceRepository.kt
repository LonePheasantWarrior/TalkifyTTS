package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import androidx.annotation.XmlRes
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlEntry
import com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * XML 音色仓储基类
 *
 * 统一实现「lazy 解析 XML 资源 + 供应商身份校验 + 条目映射」骨架，
 * 子类仅声明 XML 资源与 [VoiceXmlEntry] → [VoiceInfo] 的映射规则。
 *
 * @param xmlResId 音色定义 XML 资源
 * @param expectedProviderId 仅当请求的供应商 ID 与此一致时返回音色列表
 */
abstract class BaseXmlVoiceRepository(
    context: Context,
    @XmlRes private val xmlResId: Int,
    private val expectedProviderId: String
) : VoiceRepository {

    protected val voices: List<VoiceXmlEntry> by lazy {
        VoiceXmlParser.parse(context, xmlResId)
    }

    /** 单条 XML 音色条目到领域模型的映射 */
    protected abstract fun VoiceXmlEntry.toVoiceInfo(): VoiceInfo

    final override suspend fun getVoicesForProvider(provider: TtsProvider): List<VoiceInfo> {
        if (provider.id != expectedProviderId) return emptyList()
        // 首次访问触发 XML 解析（资源 IO），切到 IO 调度器：
        // UI 层的调用方（音色列表/配置面板）多在主线程组合期发起
        return withContext(Dispatchers.IO) {
            voices.map { it.toVoiceInfo() }
        }
    }
}
