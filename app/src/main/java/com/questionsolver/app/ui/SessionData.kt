package com.questionsolver.app.ui

import com.questionsolver.app.data.QuestionItem

/**
 * 单页数据：每页都有「仅压缩原图」与「百度增强全图」两条路径。
 *
 * @param compressedPath 仅做本地轻量压缩、未做百度增强的原图路径。
 * @param enhancedPath 百度图像增强后的全图路径；增强失败时与 compressedPath 相同。
 */
data class PageData(
    val compressedPath: String,
    val enhancedPath: String
)

/**
 * 进程级会话数据：用于在切分页与答题页之间传递题目列表，避免序列化复杂对象。
 *
 * 支持多页：[pages] 按拍摄顺序保存每一页。当仅一页时，[compressedOriginalPath] 与
 * [enhancedFullPath] 指向该页（兼容旧逻辑）。
 */
object SessionData {
    const val SOURCE_ORIGINAL = "original"
    const val SOURCE_ENHANCED = "enhanced"

    /** 按拍摄顺序的页面列表。 */
    val pages: MutableList<PageData> = mutableListOf()

    var questions: List<QuestionItem> = emptyList()
    var workDirPath: String = ""
    var layoutUsedSource: String = SOURCE_ENHANCED

    /** 兼容字段：指向第一页。 */
    val compressedOriginalPath: String
        get() = pages.firstOrNull()?.compressedPath ?: ""

    /** 兼容字段：指向第一页。 */
    val enhancedFullPath: String
        get() = pages.firstOrNull()?.enhancedPath ?: ""

    /** 当前页数。 */
    val pageCount: Int get() = pages.size

    fun clear() {
        pages.clear()
        questions = emptyList()
        workDirPath = ""
    }

    /** 重置为单页（用于首轮拍摄后初始化）。 */
    fun resetToSinglePage(page: PageData, workDir: String) {
        pages.clear()
        pages.add(page)
        workDirPath = workDir
        questions = emptyList()
    }

    /** 追加一页。 */
    fun addPage(page: PageData) {
        pages.add(page)
    }
}
