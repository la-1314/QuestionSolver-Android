package com.questionsolver.app.net

import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.util.ImageUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 *
 * 提示词要求大模型严格返回结构化 JSON，字段包括：
 *   answer            —— 标准答案（最终结果）
 *   steps             —— 分步解析（数组，每步一句）
 *   hint              —— 解题思路提示
 *   knowledge_points  —— 考点与知识点（数组）
 *   difficulty        —— 难度（简单 / 中等 / 困难）
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
                put("text", SYSTEM_PROMPT)
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", USER_PROMPT_IMAGE)
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
                put("text", SYSTEM_PROMPT)
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", USER_PROMPT_TEXT.format(ocrText))
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
            put("max_tokens", 4096)
            // 不传 response_format=json_object：部分 OpenAI 兼容服务不支持该字段会返回 400。
            // JSON 输出由 SYSTEM_PROMPT 强约束，AnswerParser 做容错提取。
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
                val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: err.toString()
                throw RuntimeException(msg)
            }
            val choices = root["choices"] as? JsonArray
            val choice = choices?.firstOrNull()?.jsonObject
                ?: throw RuntimeException("未返回 choices：$raw")
            val message = choice["message"]?.jsonObject
                ?: throw RuntimeException("返回 message 为空：$raw")
            // content 可能是字符串、JSON 对象或 JSON 数组：
            //  - 字符串：常规 OpenAI 兼容服务，content 为模型生成的文本（可能含 ```json``` 代码块）
            //  - 对象/数组：部分服务在 response_format=json_object 时直接返回结构化 JSON
            // 统一规整为字符串，交给 AnswerParser 做容错解析。
            val content = extractContentAsString(message["content"])
                ?: throw RuntimeException("返回内容为空：$raw")
            return content
        }
    }

    /** 把 content 字段统一转为字符串：字符串原样返回，对象/数组序列化为 JSON 字符串。 */
    private fun extractContentAsString(element: JsonElement?): String? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        return when (element) {
            is JsonObject -> json.encodeToString(JsonObject.serializer(), element)
            is JsonArray -> json.encodeToString(JsonArray.serializer(), element)
            // JsonLiteral：返回原始内容（不带引号）；JsonNull 已在前面过滤
            else -> runCatching { element.jsonPrimitive.content }.getOrNull()
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

        /** 系统提示词：定义角色与输出 schema。 */
        const val SYSTEM_PROMPT =
            "你是一位严谨的解题助手，擅长中小学及大学各学科题目的解析。" +
            "请严格按照指定 JSON Schema 返回，禁止输出 JSON 以外的任何文字、解释或 Markdown 代码块标记。" +
            "JSON Schema 如下：\n" +
            "{\n" +
            "  \"answer\": \"string，标准答案，可直接抄写的最终结果\",\n" +
            "  \"steps\": [\"string，分步解析，每步一句，按推理顺序排列\"],\n" +
            "  \"hint\": \"string，一句话解题思路或关键提示\",\n" +
            "  \"knowledge_points\": [\"string，本题涉及的考点/知识点\"],\n" +
            "  \"difficulty\": \"string，难度等级，取值之一：简单 / 中等 / 困难\"\n" +
            "}\n" +
            "字段要求：\n" +
            "- answer 必填；若题目为客观题，给出字母或数值结果，必要时附简短说明。\n" +
            "- steps 至少 1 步，每步描述一个推理或计算环节。\n" +
            "- hint 与 knowledge_points 可空字符串/空数组，但字段必须存在。\n" +
            "- difficulty 必须从「简单」「中等」「困难」三选一。\n" +
            "- 所有文字使用中文。"

        /** 视觉模型 user 提示词：发送图片，要求按 schema 输出。 */
        const val USER_PROMPT_IMAGE =
            "请解答附图中的题目，并按系统提示的 JSON Schema 严格输出。"

        /** 纯文本模型 user 提示词模板（%s 为 OCR 文本）。 */
        const val USER_PROMPT_TEXT =
            "请解答以下题目，并按系统提示的 JSON Schema 严格输出。\n\n" +
            "题目内容：\n%s"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
