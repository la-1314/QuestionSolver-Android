package com.questionsolver.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 大模型返回的结构化解题结果。
 *
 * @param answer 标准答案（最终结果）
 * @param steps 分步解析（每步一句）
 * @param hint 解题思路提示
 * @param knowledgePoints 考点/知识点列表
 * @param difficulty 难度（简单/中等/困难）
 * @param rawText 原始返回文本，便于排错
 */
data class SolveResult(
    val answer: String,
    val steps: List<String>,
    val hint: String,
    val knowledgePoints: List<String>,
    val difficulty: String,
    val rawText: String
) {
    companion object {
        fun empty(raw: String): SolveResult =
            SolveResult(
                answer = raw.trim().ifBlank { "" },
                steps = emptyList(),
                hint = "",
                knowledgePoints = emptyList(),
                difficulty = "",
                rawText = raw
            )
    }
}

/**
 * 解析大模型返回。模型被要求返回如下 JSON：
 * {
 *   "answer": "...",
 *   "steps": ["...", "..."],
 *   "hint": "...",
 *   "knowledge_points": ["...", "..."],
 *   "difficulty": "简单|中等|困难"
 * }
 *
 * 容错：
 *  - 夹杂 ```json ... ``` 代码块时，提取代码块再解析。
 *  - steps / knowledge_points 字段名兼容 steps_list / points。
 *  - 字段缺失或类型不符时，使用空值兜底，绝不抛异常。
 *  - 完全无法解析时，把原文作为 answer，其他字段留空。
 */
object AnswerParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): SolveResult {
        val jsonStr = extractJson(raw) ?: return SolveResult.empty(raw)
        return runCatching {
            val obj: JsonObject = json.parseToJsonElement(jsonStr).jsonObject
            val answer = obj.str("answer").orEmpty()
            val steps = obj.strList("steps", "steps_list", "step_list")
            val hint = obj.str("hint", "tip", "思路").orEmpty()
            val knowledgePoints = obj.strList("knowledge_points", "points", "knowledge")
            val difficulty = obj.str("difficulty", "level").orEmpty()
            // 即使 answer 与 steps 都空，也返回结构化对象（便于上层判断）
            SolveResult(answer, steps, hint, knowledgePoints, difficulty, raw)
        }.getOrElse { SolveResult.empty(raw) }
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] ?: continue
            return when (v) {
                is JsonPrimitive -> v.contentOrNull
                is JsonArray -> v.joinToString("") { it.jsonPrimitive.contentOrNull.orEmpty() }
                else -> null
            }
        }
        return null
    }

    private fun JsonObject.strList(vararg keys: String): List<String> {
        for (k in keys) {
            val v = this[k] ?: continue
            when (v) {
                is JsonArray -> {
                    val items = v.mapNotNull {
                        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
                    }
                    if (items.isNotEmpty()) return items
                }
                is JsonPrimitive -> {
                    // 字符串形式：按换行或分号切分
                    val text = v.contentOrNull.orEmpty()
                    if (text.isNotBlank()) {
                        return text.split(Regex("[\\n;；]+"))
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    }
                }
                else -> { /* JsonObject / JsonNull 等忽略 */ }
            }
        }
        return emptyList()
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
