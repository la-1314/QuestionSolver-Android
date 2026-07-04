package com.questionsolver.app.net

import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.data.BaiduImageEnhanceResponse
import com.questionsolver.app.data.BaiduLayoutResponse
import com.questionsolver.app.data.BaiduOcrResponse
import com.questionsolver.app.data.BaiduTokenResponse
import com.questionsolver.app.data.RectBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 百度智能云 API 客户端。
 *
 * 接口地址与请求参数均固定写死（见下方常量），仅鉴权所需的 API Key / Secret Key 由用户在配置页填入。
 * 已核实接口来源：百度智能云图像增强与特效 / 文字识别 OCR 官方文档。
 *
 *  - 鉴权：POST https://aip.baidubce.com/oauth/2.0/token
 *  - 图像清晰度增强：POST .../rest/2.0/image-process/v1/image_definition_enhance
 *  - 版面分析（含位置输出）：POST .../rest/2.0/ocr/v1/doc_analysis_office?layout_analysis=true
 *  - 通用文字识别（高精度版）：POST .../rest/2.0/ocr/v1/accurate_basic
 */
class BaiduApiClient(
    private val config: AppConfig,
    private val client: OkHttpClient = defaultClient()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpireAt: Long = 0L

    /** 获取（必要时刷新）access_token。 */
    fun fetchAccessToken(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedToken != null && now < tokenExpireAt) {
            return cachedToken!!
        }
        require(config.isBaiduReady()) { "请先在配置页填写百度 API Key 与 Secret Key" }

        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", config.baiduApiKey)
            .add("client_secret", config.baiduSecretKey)
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val parsed = json.decodeFromString(BaiduTokenResponse.serializer(), raw)
            val token = parsed.access_token
                ?: throw RuntimeException("百度鉴权失败：${parsed.error_description ?: parsed.error ?: raw}")
            cachedToken = token
            // 提前 5 分钟过期，避免边界失效
            tokenExpireAt = now + (parsed.expires_in.coerceAtLeast(0) - 300) * 1000L
            return token
        }
    }

    /**
     * 图像清晰度增强（全局预处理 / 二次预处理）。
     * 已核实接口：https://aip.baidubce.com/rest/2.0/image-process/v1/image_definition_enhance
     * 参数：image(base64 编码后 urlencode)，Content-Type 由 FormBody 自动设置为
     * application/x-www-form-urlencoded。返回 {log_id, image:<base64>}。
     * @param imagePath 图片路径
     * @return 增强后图片字节数组
     */
    fun enhanceDefinition(imagePath: String): ByteArray {
        val base64 = fileToBase64(imagePath)
        return runWithRetry(ENHANCE_URL, base64, extra = null) { raw ->
            val parsed = json.decodeFromString(BaiduImageEnhanceResponse.serializer(), raw)
            val b64 = parsed.image ?: parsed.result
            b64?.let { base64Decode(it) }
                ?: throw baiduError("图像增强", parsed.error_code, parsed.error_msg, raw)
        }
    }

    /**
     * 办公文档版面分析：返回各版面块的位置与类型（用于自动生成题目切分框）。
     * 已核实接口：https://aip.baidubce.com/rest/2.0/ocr/v1/doc_analysis_office?layout_analysis=true
     * 参数：image / language_type=CHN_ENG / layout_analysis=true。
     * 返回 results（每项含 words.words_location，用 left/top/width/height）与 layout（图/表/标题等，含 poly_location）。
     */
    fun layoutAnalysis(imagePath: String): List<RectBox> {
        val base64 = fileToBase64(imagePath)
        val extras = linkedMapOf(
            "language_type" to "CHN_ENG",
            "layout_analysis" to "true"
        )
        return runWithRetry(LAYOUT_URL, base64, extras) { raw ->
            val parsed = json.decodeFromString(BaiduLayoutResponse.serializer(), raw)
            if (parsed.error_code != null) {
                throw baiduError("版面分析", parsed.error_code, parsed.error_msg, raw)
            }
            // 合并 layout / results / words_result 三种字段，兼容百度不同接口返回
            val items = (parsed.results ?: emptyList()) +
                    (parsed.layout ?: emptyList()) +
                    (parsed.words_result ?: emptyList())
            val boxes = items.mapNotNull { it.toRectBox() }
            if (boxes.isNotEmpty()) boxes else extractBoxesFromRaw(raw)
        }
    }

    /**
     * 通用文字识别（高精度版）：返回识别到的全文本。
     * 已核实接口：https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic
     * 参数：image(base64)。该接口不支持 language_type，故不再传。
     */
    fun accurateOcr(imagePath: String): String {
        val base64 = fileToBase64(imagePath)
        return runWithRetry(OCR_URL, base64, extra = null) { raw ->
            val parsed = json.decodeFromString(BaiduOcrResponse.serializer(), raw)
            if (parsed.error_code != null) {
                throw baiduError("OCR", parsed.error_code, parsed.error_msg, raw)
            }
            parsed.words_result?.joinToString("\n") { it.words.orEmpty() }.orEmpty()
        }
    }

    /** 构造带有明确错误码的异常，便于上层向用户暴露真实失败原因。 */
    private fun baiduError(api: String, code: Int?, msg: String?, raw: String): RuntimeException {
        val detail = when (code) {
            110, 111 -> "$api 失败：access_token 失效或过期，请在配置页核对 API Key/Secret Key"
            17 -> "$api 失败：该百度服务未开通（错误码 17），请到百度智能云控制台领取/开通对应接口"
            18 -> "$api 失败：QPS 超限（错误码 18），请稍后重试"
            19 -> "$api 失败：请求总量超限（错误码 19），请检查百度配额"
            216201 -> "$api 失败：图片格式或尺寸不合法（错误码 216201）"
            216202 -> "$api 失败：图片为空或 base64 编码错误（错误码 216202）"
            else -> "$api 失败：${code?.let { "错误码 $it，" } ?: ""}${msg ?: raw}"
        }
        return RuntimeException(detail)
    }

    /** 兜底：当结构化解析拿不到坐标时，从原始 JSON 中按 poly_location/polygon_location/location 提取外接矩形。 */
    private fun extractBoxesFromRaw(raw: String): List<RectBox> {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val results = mutableListOf<RectBox>()
        fun scan(obj: JsonObject) {
            // polygon_location / poly_location: [[x,y],[x,y],[x,y],[x,y]]
            listOf("polygon_location", "poly_location", "words_location").forEach { key ->
                obj[key]?.let { el ->
                    val pts = el.asPointsOrNull()
                    if (pts != null && pts.isNotEmpty()) results.add(boundingBox(pts))
                }
            }
            // location / box: {x,y,width,height}
            obj["location"]?.let { el ->
                el.asRectBoxOrNull()?.let { results.add(it) }
            }
            obj["box"]?.let { el ->
                el.jsonPrimitive.contentOrNull?.let { s -> parseBoxString(s)?.let { results.add(it) } }
            }
            // 递归子对象与数组
            obj.forEach { (_, v) ->
                when (v) {
                    is JsonObject -> scan(v)
                    is JsonArray -> v.forEach { item -> if (item is JsonObject) scan(item) }
                    else -> Unit
                }
            }
        }
        scan(root)
        return results
    }

    private fun JsonElement.asPointsOrNull(): List<Pair<Int, Int>>? {
        return (this as? JsonArray)?.mapNotNull { p ->
            val arr = p as? JsonArray ?: return@mapNotNull null
            if (arr.size >= 2) {
                val x = arr[0].jsonPrimitive.intOrNull ?: return@mapNotNull null
                val y = arr[1].jsonPrimitive.intOrNull ?: return@mapNotNull null
                x to y
            } else null
        }
    }

    private fun JsonElement.asRectBoxOrNull(): RectBox? {
        val o = this as? JsonObject ?: return null
        val x = o["x"]?.jsonPrimitive?.intOrNull ?: o["left"]?.jsonPrimitive?.intOrNull ?: return null
        val y = o["y"]?.jsonPrimitive?.intOrNull ?: o["top"]?.jsonPrimitive?.intOrNull ?: return null
        val w = o["width"]?.jsonPrimitive?.intOrNull ?: return null
        val h = o["height"]?.jsonPrimitive?.intOrNull ?: return null
        return RectBox(x, y, w, h)
    }

    private fun parseBoxString(s: String): RectBox? {
        val parts = s.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 4) RectBox(parts[0], parts[1], parts[2], parts[3]) else null
    }

    private fun boundingBox(pts: List<Pair<Int, Int>>): RectBox {
        val xs = pts.map { it.first }
        val ys = pts.map { it.second }
        val l = xs.min(); val r = xs.max(); val t = ys.min(); val b = ys.max()
        return RectBox(l, t, r - l, b - t)
    }

    /**
     * 带重试的请求执行：第一次用当前 token，若返回 401 或百度错误码 110/111，
     * 强制刷新 token 后用新 token 重建请求再试一次。其余错误直接抛出。
     */
    private fun <T> runWithRetry(
        baseUrl: String,
        base64Image: String,
        extra: Map<String, String>?,
        parser: (String) -> T
    ): T {
        var token = fetchAccessToken()
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val builder = FormBody.Builder().add("image", base64Image)
                extra?.forEach { (k, v) -> builder.add(k, v) }
                val req = Request.Builder()
                    .url("$baseUrl?access_token=$token")
                    .post(builder.build())
                    .build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if ((resp.code == 401) && attempt == 0) {
                            token = fetchAccessToken(forceRefresh = true)
                            throw RuntimeException("token 失效，使用新 token 重试")
                        }
                        throw RuntimeException("HTTP ${resp.code}: ${raw.take(500)}")
                    }
                    // 百度返回 200 但 body 含 error_code 110/111 → token 失效，刷新重试
                    val tokenInvalid = raw.contains("\"error_code\":110") ||
                            raw.contains("\"error_code\":111")
                    if (attempt == 0 && tokenInvalid) {
                        token = fetchAccessToken(forceRefresh = true)
                        throw RuntimeException("token 失效，使用新 token 重试")
                    }
                    return parser(raw)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("未知错误")
    }

    companion object {
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val ENHANCE_URL = "https://aip.baidubce.com/rest/2.0/image-process/v1/image_definition_enhance"
        private const val LAYOUT_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/doc_analysis_office"
        private const val OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        private fun base64Decode(s: String): ByteArray =
            java.util.Base64.getDecoder().decode(s)

        private fun fileToBase64(path: String): String {
            val bytes = File(path).readBytes()
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }
}
