package com.questionsolver.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * APK 下载器：支持官方/镜像源切换，下载完成后调用系统安装器。
 *
 * @param useCdn true = 走镜像源（gh-proxy.com），false = 官方 GitHub 直连
 */
class UpdateDownloader(private val context: Context) {

    /** 下载源类型。 */
    enum class Source { OFFICIAL, CDN }

    /** 下载状态。 */
    sealed class State {
        /** 空闲。 */
        data object Idle : State()
        /** 下载中，[progress] 0~1，[receivedBytes] 已下载字节。 */
        data class Downloading(val progress: Float, val receivedBytes: Long, val totalBytes: Long) : State()
        /** 下载完成，[file] 为 apk 文件。 */
        data class Done(val file: File) : State()
        /** 下载失败。 */
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var downloadJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 开始下载。
     *
     * @param githubUrl GitHub 原始下载链接
     * @param fileName  保存文件名
     * @param source    下载源
     */
    fun start(githubUrl: String, fileName: String, source: Source) {
        if (downloadJob?.isActive == true) return
        val url = if (source == Source.CDN) UpdateChecker.toCdnUrl(githubUrl) else githubUrl
        downloadJob = CoroutineScope(Dispatchers.Main).launch {
            _state.value = State.Downloading(0f, 0L, 0L)
            val result = runCatching { download(url, fileName) }
            _state.value = result.fold(
                onSuccess = { State.Done(it) },
                onFailure = { State.Failed(it.message ?: "下载失败") }
            )
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        _state.value = State.Idle
    }

    /** 下载到外部 files 目录，返回 apk 文件。 */
    private suspend fun download(url: String, fileName: String): File = withContext(Dispatchers.IO) {
        val outDir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val outFile = File(outDir, fileName)
        // 断点不续传，直接覆盖
        if (outFile.exists()) outFile.delete()
        val req = Request.Builder().url(url)
            .header("User-Agent", "QuestionSolver-Android")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: error("响应体为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                outFile.outputStream().use { sink ->
                    val buffer = ByteArray(8192)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        received += read
                        val progress = if (total > 0) received.toFloat() / total else 0f
                        _state.value = State.Downloading(progress, received, total)
                    }
                }
            }
        }
        outFile
    }

    /**
     * 调用系统安装器安装 apk。
     *
     * targetSdk 35：必须通过 FileProvider 提供 content URI，发送 ACTION_VIEW。
     */
    fun install(file: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 对所有可能处理该 intent 的包授予 URI 读取权限
        val resInfo = context.packageManager.queryIntentActivities(intent, 0)
        for (ri in resInfo) {
            val pn = ri.activityInfo.packageName
            context.grantUriPermission(pn, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    companion object {
        /** 格式化文件大小，如 79138480 → "75.5 MB"。 */
        fun formatSize(bytes: Long): String {
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1 -> String.format("%.1f MB", mb)
                kb >= 1 -> String.format("%.0f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}
