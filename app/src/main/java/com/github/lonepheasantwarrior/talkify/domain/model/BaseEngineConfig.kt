package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * TTS 引擎配置基类
 *
 * 定义所有 TTS 引擎配置的通用结构
 * 采用抽象基类设计，便于扩展不同引擎的特定配置
 *
 * 设计原则：
 * - 只有所有引擎共有的属性才放在基类中
 * - 具体引擎的特有属性由子类定义
 *
 * @property voiceId 声音 ID，跨引擎通用属性
 *                   不同引擎对 voiceId 的格式和含义可能有不同要求
 */
abstract class BaseEngineConfig(
    open val voiceId: String = ""
)
