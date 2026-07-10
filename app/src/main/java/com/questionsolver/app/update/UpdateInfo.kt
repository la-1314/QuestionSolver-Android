package com.questionsolver.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub Release 信息（仅保留更新功能所需字段）。
 *
 * tag 形如 `v1.2-20260708_185400`，版本号取 `v` 后到首个 `-` 之前的部分。
 */
@Serializable
data class UpdateInfo(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<Asset> = emptyList()
) {
    @Serializable
    data class Asset(
        val name: String = "",
        @SerialName("content_type") val contentType: String = "",
        val size: Long = 0,
        @SerialName("browser_download_url") val downloadUrl: String = ""
    )

    /** 解析 tag 得到纯版本号，如 `v1.2-20260708_185400` → `1.2`。 */
    val version: String
        get() = tagName.removePrefix("v").substringBefore('-').trim()

    /** 第一个 apk asset 的下载地址。 */
    val apkDownloadUrl: String?
        get() = assets.firstOrNull { it.contentType.contains("android") || it.name.endsWith(".apk", true) }
            ?.downloadUrl

    /** 第一个 apk asset 的文件名。 */
    val apkFileName: String
        get() = assets.firstOrNull { it.contentType.contains("android") || it.name.endsWith(".apk", true) }
            ?.name ?: "update.apk"

    /** apk 文件大小（字节）。 */
    val apkSize: Long
        get() = assets.firstOrNull { it.contentType.contains("android") || it.name.endsWith(".apk", true) }
            ?.size ?: 0L

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(content: String): UpdateInfo =
            runCatching { json.decodeFromString(serializer(), content) }.getOrDefault(UpdateInfo())
    }
}
