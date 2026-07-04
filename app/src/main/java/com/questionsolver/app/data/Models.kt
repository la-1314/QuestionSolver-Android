package com.questionsolver.app.data

import android.graphics.RectF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 矩形区域（像素坐标，用于版面分析返回）。 */
@Serializable
data class RectBox(val x: Int = 0, val y: Int = 0, val width: Int = 0, val height: Int = 0)

/**
 * 单题切分项。
 *
 * @param sourceImage 绝对路径：用于解题的图片。
 * @param originalCompressedPath 本地保留的“仅压缩、未做百度增强”的原图路径。
 * @param enhancedImagePath 百度增强后的图片路径（若该题经过二次增强则为该路径，否则可能为空）。
 * @param isManuallyModified 是否被用户手动框选/修改过。手动修改的题目不再做百度增强。
 * @param rect 归一化裁剪框（0~1），仅用于记录与重绘。
 */
data class QuestionItem(
    var sourceImage: String,
    val originalCompressedPath: String,
    var enhancedImagePath: String? = null,
    var isManuallyModified: Boolean = false,
    val rect: RectF = RectF()
)

/** 百度 access_token 响应。 */
@Serializable
data class BaiduTokenResponse(
    val access_token: String? = null,
    val expires_in: Long = 0,
    val error: String? = null,
    val error_description: String? = null
)

/** 百度图像增强响应：{ "log_id": ..., "image": "<base64>" }。
 *  部分增强接口返回字段名为 result，故两者皆兼容。 */
@Serializable
data class BaiduImageEnhanceResponse(
    val log_id: Long? = null,
    val image: String? = null,
    val result: String? = null,
    val error_code: Int? = null,
    val error_msg: String? = null
)

/** 版面区域坐标，兼容 x/y 与 left/top 两种命名。 */
@Serializable
data class BaiduLayoutBox(
    val x: Int = 0,
    val y: Int = 0,
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    fun toRectBox(): RectBox = RectBox(
        if (x != 0 || y != 0) x else left,
        if (x != 0 || y != 0) y else top,
        width,
        height
    )
}

/** 百度版面分析返回的版面块。 */
@Serializable
data class BaiduLayoutItem(
    val type: String? = null,
    val words_type: String? = null,
    val text: String? = null,
    val word: String? = null,
    val words: BaiduLayoutItem? = null,
    val box: BaiduLayoutBox? = null,
    val char_box: BaiduLayoutBox? = null,
    val poly_location: List<List<Int>>? = null,
    val location: BaiduLayoutBox? = null,
    val words_location: BaiduLayoutBox? = null,
    val lines: List<BaiduLayoutItem>? = null
) {
    /** 把版面块的位置统一转换为外接矩形（像素坐标）。 */
    fun toRectBox(): RectBox? {
        // 0) doc_analysis_office 的 results 项内层 words.words_location（用 left/top）
        words?.words_location?.let { return it.toRectBox() }
        words?.box?.let { return it.toRectBox() }
        words?.location?.let { return it.toRectBox() }
        // 1) words_location（用 left/top）
        words_location?.let { return it.toRectBox() }
        // 2) box: {x,y,width,height} 或 {left,top,width,height}
        box?.let { return it.toRectBox() }
        // 3) location
        location?.let { return it.toRectBox() }
        // 4) char_box
        char_box?.let { return it.toRectBox() }
        // 5) poly_location / polygon_location: 4 个点 [[x,y],...]
        val pts = poly_location
        if (!pts.isNullOrEmpty() && pts.all { it.size >= 2 }) {
            val xs = pts.map { it[0] }
            val ys = pts.map { it[1] }
            val l = xs.min(); val r = xs.max(); val t = ys.min(); val b = ys.max()
            return RectBox(l, t, r - l, b - t)
        }
        return null
    }
}

/** 百度版面分析响应。兼容 layout / words_result / results 三种字段命名。 */
@Serializable
data class BaiduLayoutResponse(
    val log_id: Long? = null,
    val layout: List<BaiduLayoutItem>? = null,
    val words_result: List<BaiduLayoutItem>? = null,
    val results: List<BaiduLayoutItem>? = null,
    val directions: Int? = null,
    val content: String? = null,
    val error_code: Int? = null,
    val error_msg: String? = null
)

/** 百度 OCR（accurate_basic）响应。 */
@Serializable
data class BaiduOcrWord(val words: String? = null)

@Serializable
data class BaiduOcrResponse(
    val log_id: Long? = null,
    val words_result: List<BaiduOcrWord>? = null,
    val words_result_num: Int = 0,
    val error_code: Int? = null,
    val error_msg: String? = null
)

/** 试卷切题识别（paper_cut_edu_vlm）单题结果：坐标 + 文字。 */
data class PaperCutItem(val rect: RectBox, val text: String)

/** 试卷切题 create_task 响应。 */
@Serializable
data class PaperCutCreateTaskResponse(
    val task_id: String? = null,
    val error_code: Int? = null,
    val error_msg: String? = null
)

/** 试卷切题 get_task_result 响应。result 为原始 JSON，由调用方进一步解析。 */
@Serializable
data class PaperCutTaskResultResponse(
    val task_status: String? = null,
    val error_code: Int? = null,
    val error_msg: String? = null,
    val result: JsonElement? = null
)

/** LLM 响应（仅用于错误信息解析参考；实际请求体在 LlmApiClient 中以 JsonObject 手工构建，支持多模态）。 */
@Serializable
data class LlmResponse(
    val id: String? = null,
    val choices: List<LlmChoice>? = null,
    val error: LlmError? = null
)

@Serializable
data class LlmChoice(
    val index: Int = 0,
    val message: LlmChoiceMessage? = null,
    val finish_reason: String? = null
)

@Serializable
data class LlmChoiceMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class LlmError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
