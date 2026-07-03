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
     * @param imagePath 图片路径
     * @return 增强后图片字节数组
     */
    fun enhanceDefinition(imagePath: String): ByteArray {
        val token = fetchAccessToken()
        val base64 = fileToBase64(imagePath)
        val body = FormBody.Builder().add("image", base64).build()
        val url = "$ENHANCE_URL?access_token=$token"
        val req = Request.Builder().url(url).post(body).build()
        return runWithRetry(req) { raw ->
            val parsed = json.decodeFromString(BaiduImageEnhanceResponse.serializer(), raw)
            val b64 = parsed.image ?: parsed.result
            b64?.let { base64Decode(it) }
                ?: throw RuntimeException("图像增强失败：${parsed.error_msg ?: raw}")
        }
    }

    /**
     * 办公文档版面分析：返回各版面块的位置与类型（用于自动生成题目切分框）。
     * layout_analysis=true 时返回图、表、标题、段落等的位置。
     */
    fun layoutAnalysis(imagePath: String): List<RectBox> {
        val token = fetchAccessToken()
        val base64 = fileToBase64(imagePath)
        val body = FormBody.Builder()
            .add("image", base64)
            .add("language_type", "CHN_ENG")
            .add("layout_analysis", "true")
            .build()
        val url = "$LAYOUT_URL?access_token=$token"
        val req = Request.Builder().url(url).post(body).build()
        return runWithRetry(req) { raw ->
            val parsed = json.decodeFromString(BaiduLayoutResponse.serializer(), raw)
            if (parsed.error_code != null) {
                throw RuntimeException("版面分析失败：${parsed.error_msg ?: raw}")
            }
            // 同时兼容 layout 字段与 words_result 字段
            val items = (parsed.layout ?: emptyList()) + (parsed.words_result ?: emptyList())
            items.mapNotNull { it.toRectBox() }
                .ifEmpty { extractBoxesFromRaw(raw) }
        }
    }

    /**
     * 通用文字识别（高精度版）：返回识别到的全文本。
     */
    fun accurateOcr(imagePath: String): String {
        val token = fetchAccessToken()
        val base64 = fileToBase64(imagePath)
        val body = FormBody.Builder()
            .add("image", base64)
            .add("language_type", "CHN_ENG")
            .build()
        val url = "$OCR_URL?access_token=$token"
        val req = Request.Builder().url(url).post(body).build()
        return runWithRetry(req) { raw ->
            val parsed = json.decodeFromString(BaiduOcrResponse.serializer(), raw)
            if (parsed.error_code != null) {
                throw RuntimeException("OCR 失败：${parsed.error_msg ?: raw}")
            }
            parsed.words_result?.joinToString("\n") { it.words.orEmpty() }.orEmpty()
        }
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
        val x = o["x"]?.jsonPrimitive?.intOrNull ?: return null
        val y = o["y"]?.jsonPrimitive?.intOrNull ?: return null
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

    private fun <T> runWithRetry(req: Request, parser: (String) -> T): T {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // token 失效时强制刷新重试一次
                        if (resp.code == 401 && attempt == 0) {
                            fetchAccessToken(forceRefresh = true)
                            throw RuntimeException("token 失效，重试")
                        }
                        throw RuntimeException("HTTP ${resp.code}: $raw")
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
