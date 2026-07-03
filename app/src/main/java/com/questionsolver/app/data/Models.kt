package com.questionsolver.app.data

import android.graphics.RectF
import kotlinx.serialization.Serializable

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

/** 百度版面分析（doc_analysis_office）返回的单个区域位置。 */
@Serializable
data class BaiduLayoutBox(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)

/** 百度版面分析返回的版面块。 */
@Serializable
data class BaiduLayoutItem(
    val type: String? = null,
    val text: String? = null,
    val box: BaiduLayoutBox? = null,
    val char_box: BaiduLayoutBox? = null,
    val poly_location: List<List<Int>>? = null,
    val location: BaiduLayoutBox? = null,
    val lines: List<BaiduLayoutItem>? = null
) {
    /** 把版面块的位置统一转换为外接矩形（像素坐标）。 */
    fun toRectBox(): RectBox? {
        // 1) box: {x,y,width,height}
        box?.let { return RectBox(it.x, it.y, it.width, it.height) }
        // 2) location: {x,y,width,height}
        location?.let { return RectBox(it.x, it.y, it.width, it.height) }
        // 3) poly_location / polygon_location: 4 个点 [[x,y],...]
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

/** 百度版面分析响应（取其中的 layout/words_result 字段）。 */
@Serializable
data class BaiduLayoutResponse(
    val log_id: Long? = null,
    val layout: List<BaiduLayoutItem>? = null,
    val words_result: List<BaiduLayoutItem>? = null,
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
