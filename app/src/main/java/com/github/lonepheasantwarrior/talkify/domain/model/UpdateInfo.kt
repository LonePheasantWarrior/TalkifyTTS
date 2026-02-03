package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 更新信息数据类
 *
 * 存储从 GitHub Releases 获取的最新版本信息
 *
 * @param versionName 版本名称（如 "v1.0.0"）
 * @param releaseNotes 更新说明（Release notes 内容）
 * @param releaseUrl 版本发布页面地址
 * @param downloadUrl APK 下载地址（如果有的话）
 * @param publishedAt 发布时间
 */
data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val downloadUrl: String?,
    val publishedAt: String
)
