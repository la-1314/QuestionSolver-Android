package com.questionsolver.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 版本检查器：默认直连 GitHub，4 秒内拿不到响应则切换 CDN 镜像重试。
 *
 * CDN 镜像（gh-proxy.com）代理 GitHub API 与下载链接：
 * 原始 `https://api.github.com/...` → `https://gh-proxy.com/https://api.github.com/...`
 *
 * @param owner 仓库 owner
 * @param repo  仓库名
 */
class UpdateChecker(
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO
) {
    /** 检查结果：是否找到新版本，及其信息。 */
    sealed class Result {
        /** 找到更新，[info] 为最新 Release 信息，[viaCdn] 标记是否经 CDN 取得。 */
        data class HasUpdate(val info: UpdateInfo, val viaCdn: Boolean) : Result()
        /** 已是最新版本。 */
        data class UpToDate(val viaCdn: Boolean) : Result()
        /** 检查失败（网络错误等）。 */
        data class Failed(val message: String) : Result()
    }

    /** 直连超时阈值：4 秒内未拿到响应即降级到 CDN。 */
    private val directTimeoutMs = 4_000L

    /** 直连 GitHub API（不带 CDN 前缀）。 */
    private val directApiUrl: String
        get() = "https://api.github.com/repos/$owner/$repo/releases/latest"

    /** CDN 镜像 GitHub API（gh-proxy.com 代理）。 */
    private val cdnApiUrl: String
        get() = "$CDN_PREFIX$directApiUrl"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 检查更新。
     *
     * @param currentVersion 当前版本号（如 "1.2"）
     */
    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        // 1. 先直连，4 秒超时
        val direct = withTimeoutOrNull(directTimeoutMs) { fetchRelease(directApiUrl) }
        if (direct != null) {
            return@withContext compareVersion(currentVersion, direct, viaCdn = false)
        }
        // 2. 直连超时/失败 → 用 CDN 重试
        val cdn = runCatching { fetchRelease(cdnApiUrl) }.getOrNull()
        if (cdn != null) {
            return@withContext compareVersion(currentVersion, cdn, viaCdn = true)
        }
        Result.Failed("无法连接 GitHub，请检查网络后重试")
    }

    /** 请求指定 URL 并解析为 [UpdateInfo]。 */
    private fun fetchRelease(url: String): UpdateInfo {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "QuestionSolver-Android")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return UpdateInfo.parse(body)
        }
    }

    /** 比较版本号，判定是否需要更新。 */
    private fun compareVersion(current: String, info: UpdateInfo, viaCdn: Boolean): Result {
        val remote = info.version
        if (remote.isBlank()) return Result.Failed("无法解析远程版本号")
        return if (isNewer(remote, current)) {
            Result.HasUpdate(info, viaCdn)
        } else {
            Result.UpToDate(viaCdn)
        }
    }

    companion object {
        const val DEFAULT_OWNER = "la-1314"
        const val DEFAULT_REPO = "QuestionSolver-Android"

        /** CDN 镜像前缀，拼接在 GitHub 原始链接之前。 */
        const val CDN_PREFIX = "https://gh-proxy.com/"

        /**
         * 语义化版本比较：remote > current 返回 true。
         * 形如 "1.2" vs "1.3" → 按点分段逐段比数字。
         */
        fun isNewer(remote: String, current: String): Boolean {
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(r.size, c.size)
            for (i in 0 until maxLen) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }

        /**
         * 将 GitHub 下载链接转换为 CDN 镜像链接。
         * `https://github.com/...` → `https://gh-proxy.com/https://github.com/...`
         */
        fun toCdnUrl(githubUrl: String): String = CDN_PREFIX + githubUrl
    }
}
