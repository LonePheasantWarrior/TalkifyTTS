package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

/**
 * 语义树快照的中间表示（平台无关、纯 Kotlin）
 *
 * Android 捕获层（[ReplaySession]）把 Compose 语义树压缩为此结构，
 * 序列化层（[RrwebSnapshotBuilder]）据此生成 rrweb DOM 快照，两者解耦以便纯 JVM 单测。
 *
 * 坐标一律为窗口内的绝对坐标（Int 像素）
 */
internal data class UiSnapshot(
    /** 当前页面路径（应用内映射，如 "/"、"/about"） */
    val url: String,
    val title: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val root: UiNode,
)

/**
 * 单个 UI 节点
 *
 * [text]/[contentDescription] 均为无障碍可见内容；[masked] 表示该节点及其整个子树
 * 命中隐私策略（可编辑输入、strict 模式或业务声明的敏感容器），其文本在捕获层
 * 即被丢弃，序列化层不得再呈现任何原文。
 */
internal data class UiNode(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val text: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val editable: Boolean = false,
    val masked: Boolean = false,
    val children: List<UiNode> = emptyList(),
)
