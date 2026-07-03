package com.questionsolver.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** LLM 返回结果。 */
data class SolveResult(
    val answer: String,
    val steps: String,
    val rawText: String
)

/**
 * 解析大模型返回。模型被要求返回 {"answer": "...", "steps": "..."} JSON。
 * 做容错：若返回中夹杂 markdown 代码块，则提取其中 JSON 再解析；解析失败则把原文作为 answer。
 */
object AnswerParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): SolveResult {
        val jsonStr = extractJson(raw)
        if (jsonStr != null) {
            runCatching {
                val obj: JsonObject = json.parseToJsonElement(jsonStr).jsonObject
                val answer = obj["answer"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val steps = obj["steps"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (answer.isNotBlank() || steps.isNotBlank()) {
                    return SolveResult(answer, steps, raw)
                }
            }
        }
        // 容错：解析失败
        return SolveResult(answer = raw.trim(), steps = "", rawText = raw)
    }

    private fun extractJson(raw: String): String? {
        val text = raw.trim()
        // 1) 直接是 JSON 对象
        if (text.startsWith("{") && text.endsWith("}")) return text
        // 2) 代码块 ```json ... ``` 或 ``` ... ```
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.find(text)?.let { return it.groupValues[1].trim() }
        // 3) 第一个 { 到最后一个 }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }
}
