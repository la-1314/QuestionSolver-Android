package com.questionsolver.app.net

import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.data.QuestionItem
import com.questionsolver.app.util.AnswerParser
import com.questionsolver.app.util.ImageUtils
import com.questionsolver.app.util.SolveResult
import java.io.File

/**
 * 解题编排引擎：执行第 4~6 条需求的分支逻辑。
 *
 * 规则：
 *  - 自动切分、未手动修改的单题图片：执行百度图像增强二次预处理后，再送入解题。
 *  - 手动框选截取出的图片：不再做百度增强，直接送入解题。
 *  - 模型支持图像输入：处理后图片 + 固定提示词 -> /v1/chat/completions。
 *  - 模型仅支持文字输入：优先用试卷切分接口返回的文字（item.ocrText），
 *    没有时再调用 accurate_basic OCR -> 文本 + 提示词 -> /v1/chat/completions。
 */
class SolveEngine(
    private val config: AppConfig,
    private val baidu: BaiduApiClient,
    private val llm: LlmApiClient,
    private val workDir: File
) {

    /**
     * 对单题执行完整解题流程。
     * @param index 题号（用于命名中间文件）
     */
    fun solve(item: QuestionItem, index: Int): SolveResult {
        // 1) 确定用于解题的"处理后图片"
        val processedPath = prepareProcessedImage(item, index)

        // 2) 按模型能力分支
        return if (config.llmSupportsImage) {
            val raw = llm.solveWithImage(processedPath)
            AnswerParser.parse(raw)
        } else {
            // 纯文本模型：优先用试卷切分接口已返回的题目文字，避免重复调 OCR
            val ocrText = item.ocrText?.takeIf { it.isNotBlank() }
                ?: baidu.accurateOcr(processedPath)
            val raw = llm.solveWithText(ocrText)
            AnswerParser.parse(raw)
        }
    }

    /**
     * 生成"处理后图片"路径，遵循增强规则。
     *  - 未手动修改（自动切分）：做百度图像增强二次预处理。
     *  - 手动框选：保持原图（即 item.sourceImage，已是不做增强的裁剪图）。
     */
    private fun prepareProcessedImage(item: QuestionItem, index: Int): String {
        if (item.isManuallyModified) {
            // 手动框选：不再做百度增强
            return item.sourceImage
        }
        // 自动切分、未手动修改：执行百度图像增强二次预处理
        item.enhancedImagePath?.let { existing ->
            if (File(existing).exists()) return existing
        }
        val enhancedBytes = baidu.enhanceDefinition(item.sourceImage)
        val outFile = File(workDir, "q_${index}_enhanced.jpg").apply { parentFile?.mkdirs() }
        outFile.writeBytes(enhancedBytes)
        item.enhancedImagePath = outFile.absolutePath
        return outFile.absolutePath
    }
}
