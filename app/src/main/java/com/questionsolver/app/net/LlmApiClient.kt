package com.questionsolver.app.net

import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.util.ImageUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 大语言模型客户端：固定接口路径 /v1/chat/completions。
 *
 * 分支逻辑（在客户端内执行）：
 *  - 模型支持图像输入：将处理后单题图片 + 固定提示词直接发送（OpenAI 多模态格式）。
 *  - 模型仅支持文字输入：先 OCR 出文本，再结合提示词发送纯文本请求。
 */
class LlmApiClient(
    private val config: AppConfig,
    private val client: OkHttpClient = defaultClient()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 完整的解题接口地址：域名 + 固定路径。 */
    private fun completionsUrl(): String =
        "${config.normalizedDomain()}$CHAT_PATH"

    /**
     * 视觉模型：图片 + 提示词直接请求。
     * @param imagePath 处理后的单题图片路径
     */
    fun solveWithImage(imagePath: String): String {
        require(config.llmSupportsImage) { "当前模型未开启图像输入" }
        val base64 = ImageUtils.fileToBase64(imagePath)
        val dataUrl = "data:image/jpeg;base64,$base64"

        val content: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", PROMPT)
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", dataUrl)
                })
            })
        }

        val body = buildRequestBody(content)
        return execute(body)
    }

    /**
     * 纯文本模型：先用百度 OCR 识别文本，再结合提示词请求。
     */
    fun solveWithText(ocrText: String): String {
        val content: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", PROMPT_TEMPLATE.format(ocrText))
            })
        }
        val body = buildRequestBody(content)
        return execute(body)
    }

    private fun buildRequestBody(content: JsonArray): String {
        val messages: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", content)
            })
        }
        val root: JsonObject = buildJsonObject {
            put("model", config.llmModel)
            put("messages", messages)
            put("temperature", 0.2)
            put("max_tokens", 2048)
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun execute(bodyJson: String): String {
        require(config.isLlmReady()) { "请先在配置页填写大模型域名、密钥与模型名" }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(completionsUrl())
            .header("Authorization", "Bearer ${config.llmApiKey}")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(mediaType))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}: ${extractError(raw)}")
            }
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw RuntimeException("无法解析响应：$raw")
            // 检查 error 字段
            root["error"]?.let { err ->
                val msg = err.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                throw RuntimeException(msg ?: err.toString())
            }
            val choice = root["choices"]?.let { it as? JsonArray }?.firstOrNull()?.jsonObject
                ?: throw RuntimeException("未返回 choices：$raw")
            val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: throw RuntimeException("返回内容为空：$raw")
            return content
        }
    }

    private fun extractError(raw: String): String {
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: raw
        }.getOrDefault(raw)
    }

    companion object {
        const val CHAT_PATH = "/v1/chat/completions"

        /** 视觉模型固定提示词。 */
        const val PROMPT =
            "你是一位严谨的解题助手。请解答图片中的题目，给出标准答案和分步解析。" +
            "严格按以下JSON格式返回，不要输出JSON以外的任何内容：\n" +
            "{\"answer\":\"标准答案\",\"steps\":\"分步解析，可使用换行分步骤说明\"}"

        /** 纯文本模型固定提示词模板（%s 为 OCR 文本）。 */
        const val PROMPT_TEMPLATE =
            "你是一位严谨的解题助手。请解答以下题目，给出标准答案和分步解析。\n" +
            "题目内容：\n%s\n" +
            "严格按以下JSON格式返回，不要输出JSON以外的任何内容：\n" +
            "{\"answer\":\"标准答案\",\"steps\":\"分步解析，可使用换行分步骤说明\"}"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
