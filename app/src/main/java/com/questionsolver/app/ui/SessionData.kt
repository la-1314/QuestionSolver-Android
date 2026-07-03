package com.questionsolver.app.ui

import com.questionsolver.app.data.QuestionItem

/**
 * 进程级会话数据：用于在切分页与答题页之间传递题目列表，避免序列化复杂对象。
 */
object SessionData {
    const val SOURCE_ORIGINAL = "original"
    const val SOURCE_ENHANCED = "enhanced"

    var questions: List<QuestionItem> = emptyList()
    var workDirPath: String = ""
    var compressedOriginalPath: String = ""
    var enhancedFullPath: String = ""
    var layoutUsedSource: String = SOURCE_ENHANCED

    fun clear() {
        questions = emptyList()
        workDirPath = ""
        compressedOriginalPath = ""
        enhancedFullPath = ""
    }
}
