package com.questionsolver.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 应用配置：持久化保存于 SharedPreferences。
 *
 * 说明：百度智能云鉴权要求同时提供 API Key (client_id) 与 Secret Key (client_secret)，
 * 这是百度 OAuth 接口的硬性要求（已通过官方文档核实），因此配置页同时保留两个输入框。
 */
@Serializable
data class AppConfig(
    val baiduApiKey: String = "",
    val baiduSecretKey: String = "",
    val llmDomain: String = "",          // 形如 https://api.example.com （不带末尾斜杠）
    val llmApiKey: String = "",
    val llmModel: String = "",
    val llmSupportsImage: Boolean = true
) {
    fun isBaiduReady(): Boolean = baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()
    fun isLlmReady(): Boolean = llmDomain.isNotBlank() && llmApiKey.isNotBlank() && llmModel.isNotBlank()
    fun normalizedDomain(): String = llmDomain.trimEnd('/')

    companion object {
        private const val PREFS = "questionsolver_prefs"
        private const val KEY_CONFIG = "app_config"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(context: Context): AppConfig {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CONFIG, null) ?: return AppConfig()
            return runCatching { json.decodeFromString(AppConfig.serializer(), raw) }.getOrDefault(AppConfig())
        }

        fun save(context: Context, config: AppConfig) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CONFIG, json.encodeToString(AppConfig.serializer(), config)).apply()
        }
    }
}
