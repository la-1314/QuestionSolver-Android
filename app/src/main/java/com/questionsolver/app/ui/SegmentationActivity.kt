package com.questionsolver.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.data.QuestionItem
import com.questionsolver.app.data.RectBox
import com.questionsolver.app.databinding.ActivitySegmentationBinding
import com.questionsolver.app.net.BaiduApiClient
import com.questionsolver.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 试卷切分页。
 *
 * 支持多页：用户可点击「再拍一页」追加拍摄，所有页按顺序纵向拼接为一张长图展示与切分。
 * 跨页框（横跨页边界的框）在解题时会按顺序把涉及的两页区域拼接成一张图发送给大模型。
 *
 * 视觉辅助：每个切分框使用不同颜色 + 左上角数字角标；跨页框额外标注「跨页」并使用醒目橙色；
 * 页边界以橙色虚线分隔，并标注「第N页 ↑ / 第N+1页 ↓」。
 */
class SegmentationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySegmentationBinding
    private lateinit var baidu: BaiduApiClient
    private lateinit var cfg: AppConfig

    /** 当前使用的切分源是否为增强图。 */
    private var sourceIsEnhanced: Boolean = true

    /** 当前拼接后的展示位图（多页纵向拼接）。 */
    private var stitchedBitmap: Bitmap? = null
    /** 拼接图保存为 JPEG 的文件路径，供 paperCutSegment 调用。 */
    private var stitchedFilePath: String = ""
    /** 各页结束 Y 的归一化坐标（0~1，递增，末项 1.0）。空表示单页。 */
    private var pageBoundariesNorm: List<Float> = emptyList()

    /** 切分接口返回的每道题文字，按框顺序对应；手动新增的框无文字。 */
    private var pendingOcrTexts: List<String> = emptyList()

    /** 「再拍一页」结果回调：接收新页的压缩原图路径与增强全图路径。 */
    private val captureAnotherPageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val compressed = data.getStringExtra(CameraActivity.EXTRA_RESULT_COMPRESSED) ?: return@registerForActivityResult
            val enhanced = data.getStringExtra(CameraActivity.EXTRA_RESULT_ENHANCED) ?: compressed
            val err = data.getStringExtra(CameraActivity.EXTRA_RESULT_ENHANCE_ERROR)
            // 追加为新页
            SessionData.addPage(PageData(compressed, enhanced))
            if (err != null) {
                Snackbar.make(binding.root, "新页图像增强失败，已使用原图：$err", Snackbar.LENGTH_LONG).show()
            }
            // 重新拼接 + 重新切分
            rebuildStitchedAndSegment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySegmentationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        cfg = AppConfig.load(this)
        baidu = BaiduApiClient(cfg)

        if (SessionData.pages.isEmpty()) {
            finish()
            return
        }

        // 默认使用增强图（若任一页增强图缺失则降级为原图）
        sourceIsEnhanced = SessionData.pages.all { File(it.enhancedPath).exists() }
        binding.toggleSource.check(if (sourceIsEnhanced) R.id.btnSrcEnhanced else R.id.btnSrcOriginal)

        binding.toggleSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newEnhanced = checkedId == R.id.btnSrcEnhanced
            if (newEnhanced == sourceIsEnhanced) return@addOnButtonCheckedListener
            sourceIsEnhanced = newEnhanced
            SessionData.layoutUsedSource =
                if (sourceIsEnhanced) SessionData.SOURCE_ENHANCED else SessionData.SOURCE_ORIGINAL
            rebuildStitchedAndSegment()
        }

        binding.btnResegment.setOnClickListener { rebuildStitchedAndSegment() }
        binding.btnAddBox.setOnClickListener { binding.cropOverlay.addEmptyBox() }
        binding.btnDelete.setOnClickListener { binding.cropOverlay.deleteSelected() }
        binding.btnSolveAll.setOnClickListener { solveAll() }
        binding.btnAnotherPage.setOnClickListener { launchCaptureAnotherPage() }

        // 首次进入自动切分
        rebuildStitchedAndSegment()
    }

    /** 解析指定页当前切分源对应的图片路径。 */
    private fun resolvePagePath(page: PageData): String =
        if (sourceIsEnhanced && File(page.enhancedPath).exists()) page.enhancedPath
        else page.compressedPath

    /** 唤起相机拍摄下一页（返回模式）。 */
    private fun launchCaptureAnotherPage() {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_RETURN_MODE, true)
        }
        captureAnotherPageLauncher.launch(intent)
    }

    /**
     * 重新拼接所有页（按当前切分源）并执行试卷切题识别。
     * - 单页：直接展示，不绘制页边界；
     * - 多页：纵向拼接，计算页边界归一化 Y，绘制虚线分隔。
     */
    private fun rebuildStitchedAndSegment() {
        showLoading("拼接与切分中，请稍候…")
        lifecycleScope.launch {
            var errorMsg: String? = null
            var boxes: List<RectBox> = emptyList()
            var ocrTexts: List<String> = emptyList()
            withContext(Dispatchers.IO) {
                runCatching {
                    // 1) 加载各页当前源的位图
                    val pageBmps = SessionData.pages.map { ImageUtils.loadCompressed(resolvePagePath(it)) }
                    // 2) 拼接
                    val (stitched, ranges) = ImageUtils.stitchVertically(pageBmps)
                    stitchedBitmap = stitched
                    // 3) 计算页边界归一化 Y
                    val totalH = stitched.height.toFloat()
                    pageBoundariesNorm = ranges.map { (startY, endY) -> endY / totalH }
                    // 4) 保存拼接图为 JPEG，供 paperCutSegment 调用
                    val outFile = File(SessionData.workDirPath, "stitched_source.jpg")
                    stitchedFilePath = ImageUtils.saveCompressedJpeg(stitched, outFile)
                    // 5) 调用试卷切题识别
                    val items = baidu.paperCutSegment(stitchedFilePath)
                    boxes = items.map { it.rect }
                    ocrTexts = items.map { it.text }
                }.onFailure { errorMsg = it.message ?: it.toString() }
            }
            // 切到主线程更新 UI
            val bmp = stitchedBitmap
            if (bmp == null) {
                hideLoading()
                binding.tvHint.text = "图片加载失败：${errorMsg ?: "未知错误"}"
                return@launch
            }
            binding.cropOverlay.setBitmap(bmp)
            binding.cropOverlay.setPageBoundaries(pageBoundariesNorm)
            // 用拼接图尺寸做归一化
            val w = bmp.width
            val h = bmp.height
            val normalized = boxes.map { it.toNormalized(w, h) }
            pendingOcrTexts = ocrTexts
            hideLoading()
            if (normalized.isEmpty()) {
                val hint = errorMsg?.let { "${getString(R.string.seg_no_box)}\n原因：$it" }
                    ?: getString(R.string.seg_no_box)
                binding.tvHint.text = hint
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SegmentationActivity)
                    .setTitle("试卷切分失败")
                    .setMessage(errorMsg ?: "未识别到任何题目区域，可手动框选后解题。")
                    .setPositiveButton("手动框选") { _, _ -> binding.cropOverlay.addEmptyBox() }
                    .setNegativeButton(R.string.cancel, null)
                    .setCancelable(true)
                    .show()
            } else {
                val pageTip = if (SessionData.pageCount > 1) "（共${SessionData.pageCount}页拼接）" else ""
                binding.tvHint.text = getString(R.string.crop_drag_hint) + pageTip
                binding.cropOverlay.setBoxes(normalized)
            }
        }
    }

    private fun RectBox.toNormalized(w: Int, h: Int): RectF = RectF(
        x.toFloat() / w,
        y.toFloat() / h,
        (x + width).toFloat() / w,
        (y + height).toFloat() / h
    )

    /**
     * 全部解题：按当前拼接图 + 各框裁剪出单题图片。
     *
     * 跨页框：直接从拼接图按归一化坐标裁剪，结果即为两页对应区域按顺序纵向拼接的图，
     * 满足「把这两页按顺序贴在一起再发送」的需求。
     */
    private fun solveAll() {
        val boxes = binding.cropOverlay.getBoxes()
        val manualFlags = binding.cropOverlay.getManualFlags()
        if (boxes.isEmpty()) {
            Snackbar.make(binding.root, R.string.seg_no_box, Snackbar.LENGTH_LONG).show()
            return
        }
        val srcBmp = stitchedBitmap ?: run {
            Snackbar.make(binding.root, "图片未加载", Snackbar.LENGTH_LONG).show()
            return
        }
        val crossCount = binding.cropOverlay.crossPageCount()
        showLoading("准备题目…")
        lifecycleScope.launch {
            val workDir = File(SessionData.workDirPath)
            val questions = withContext(Dispatchers.IO) {
                boxes.mapIndexed { i, rect ->
                    val cropped = ImageUtils.cropFromStitched(srcBmp, rect)
                    val tag = if (binding.cropOverlay.isCrossPage(i)) "_cross" else ""
                    val outFile = File(workDir, "q_${i + 1}$tag.jpg")
                    val path = ImageUtils.saveCompressedJpeg(cropped, outFile)
                    // 自动切分的框（非手动）才有切分接口返回的文字；手动新增/修改的框无文字
                    val isManual = manualFlags.getOrElse(i) { true }
                    val ocrText = if (isManual) null else pendingOcrTexts.getOrNull(i)
                    QuestionItem(
                        sourceImage = path,
                        originalCompressedPath = SessionData.compressedOriginalPath,
                        isManuallyModified = isManual,
                        rect = rect,
                        ocrText = ocrText
                    )
                }
            }
            SessionData.questions = questions
            hideLoading()
            if (crossCount > 0) {
                Snackbar.make(binding.root, "已拼接 $crossCount 个跨页题", Snackbar.LENGTH_SHORT).show()
            }
            startActivity(Intent(this@SegmentationActivity, AnswerActivity::class.java))
        }
    }

    private fun showLoading(text: String) {
        binding.tvLoading.text = text
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
    }
}
