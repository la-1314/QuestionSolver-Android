package com.questionsolver.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片处理工具：本地轻量化压缩、Base64 编码、按裁剪框裁剪、保存。
 *
 * 重点：所有从文件加载的 Bitmap 都会根据 JPEG 的 EXIF orientation 标签进行旋转，
 * 以解决拍照时手机竖向/横向手持导致图片方向不正确的问题。CameraX 在保存 JPEG 时
 * 会把传感器方向写入 EXIF，因此读取 EXIF 并应用旋转即可得到与预览一致的最终方向。
 */
object ImageUtils {

    /** 本地压缩目标：最长边不超过此值，JPEG 质量。 */
    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 85

    /** 从文件加载并做轻量压缩（按 EXIF 旋转后缩放最长边）。返回压缩后的 Bitmap。 */
    fun loadCompressed(path: String): Bitmap {
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        val raw = BitmapFactory.decodeFile(path, opts) ?: error("无法解码图片: $path")
        val oriented = applyExifOrientation(path, raw)
        return scaleToMaxEdge(oriented, MAX_EDGE)
    }

    /** 读取 EXIF orientation 并把 Bitmap 旋转到正向。 */
    private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
        return runCatching {
            val exif = ExifInterface(path)
            val rotation = when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation == 0f) bitmap else {
                val matrix = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }.getOrElse { bitmap }
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = max(w, h)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        val nw = (w * scale).roundToInt().coerceAtLeast(1)
        val nh = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /** 将 Bitmap 保存为 JPEG（轻量压缩）。返回保存的文件路径。 */
    fun saveCompressedJpeg(bitmap: Bitmap, outFile: File, quality: Int = JPEG_QUALITY): String {
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
        }
        return outFile.absolutePath
    }

    /** 读取文件字节并 Base64 编码（百度接口要求原始 Base64，表单提交时由 OkHttp 做 urlencode）。 */
    fun fileToBase64(path: String): String {
        val bytes = File(path).readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun bitmapToBase64(bitmap: Bitmap, quality: Int = JPEG_QUALITY): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 按 [normalizedRect]（0~1 归一化坐标）从 [src] 裁剪出子图。
     */
    fun cropByNormalizedRect(src: Bitmap, normalizedRect: RectF): Bitmap {
        val left = (normalizedRect.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val top = (normalizedRect.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val right = (normalizedRect.right * src.width).roundToInt().coerceIn(left + 1, src.width)
        val bottom = (normalizedRect.bottom * src.height).roundToInt().coerceIn(top + 1, src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    /**
     * 在 [src] 上绘制给定的归一化裁剪框列表（用于切分预览叠加），返回新 Bitmap。
     */
    fun drawBoxesOverlay(src: Bitmap, boxes: List<RectF>, borderColor: Int = Color.parseColor("#3F51B5")): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = max(2f, src.width / 300f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = borderColor
            strokeWidth = stroke
        }
        for (b in boxes) {
            val l = b.left * src.width
            val t = b.top * src.height
            val r = b.right * src.width
            val bo = b.bottom * src.height
            canvas.drawRect(l, t, r, bo, paint)
        }
        return out
    }

    /** 计算原图解码尺寸（不加载到内存）。 */
    fun imageSize(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return if (opts.outWidth > 0) opts.outWidth to opts.outHeight else null
    }

    /** 把 RectF（像素坐标）转为归一化 RectF（相对给定宽高）。 */
    fun toNormalized(pixelRect: Rect, width: Int, height: Int): RectF {
        return RectF(
            pixelRect.left.toFloat() / width,
            pixelRect.top.toFloat() / height,
            pixelRect.right.toFloat() / width,
            pixelRect.bottom.toFloat() / height
        )
    }

    /**
     * 将多页 Bitmap 按顺序纵向拼接为一张长图。
     *
     * - 各页等宽对齐：以最大宽度为基准，较窄的页居中绘制于白色背景上；
     * - 返回拼接后的新 Bitmap（ARGB_8888）；
     * - 同时返回每页在拼接图中的「起始 Y / 结束 Y」（像素），用于跨页判断与分页裁剪。
     *
     * @return (stitchedBitmap, pageRanges) 其中 pageRanges[i] = (startY, endY) 像素坐标
     */
    fun stitchVertically(pages: List<Bitmap>): Pair<Bitmap, List<Pair<Int, Int>>> {
        require(pages.isNotEmpty()) { "至少需要一页" }
        val maxW = pages.maxOf { it.width }
        // 页间距：留少量白边便于视觉分隔
        val gap = (maxW * 0.015f).roundToInt().coerceIn(2, 24)
        val totalH = pages.sumOf { it.height } + gap * (pages.size - 1)
        val out = Bitmap.createBitmap(maxW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, maxW.toFloat(), totalH.toFloat(), bg)
        val ranges = mutableListOf<Pair<Int, Int>>()
        var y = 0
        for ((idx, bmp) in pages.withIndex()) {
            val left = ((maxW - bmp.width) / 2f)
            canvas.drawBitmap(bmp, left, y.toFloat(), null)
            ranges.add(y to y + bmp.height)
            y += bmp.height + gap
        }
        return out to ranges
    }

    /**
     * 从拼接长图中按归一化坐标裁剪。对跨页框天然有效：裁剪区域横跨页边界时，
     * 结果即为两页对应区域的纵向拼接（即「按顺序贴在一起」）。
     */
    fun cropFromStitched(stitched: Bitmap, normalizedRect: RectF): Bitmap {
        return cropByNormalizedRect(stitched, normalizedRect)
    }
}
