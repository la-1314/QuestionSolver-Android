package com.questionsolver.app.ui

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.View
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

class SegmentationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySegmentationBinding
    private lateinit var baidu: BaiduApiClient
    private lateinit var cfg: AppConfig

    private var currentSourcePath: String = ""
    private var sourceIsEnhanced: Boolean = true

    /** 解析当前切分源对应的图片路径。 */
    private fun resolveSourcePath(): String =
        if (sourceIsEnhanced && File(SessionData.enhancedFullPath).exists())
            SessionData.enhancedFullPath
        else
            SessionData.compressedOriginalPath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySegmentationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        cfg = AppConfig.load(this)
        baidu = BaiduApiClient(cfg)

        if (SessionData.compressedOriginalPath.isBlank()) {
            finish()
            return
        }

        // 默认使用增强图（若增强失败则降级为原图）
        sourceIsEnhanced = SessionData.enhancedFullPath.isNotBlank() &&
                File(SessionData.enhancedFullPath).exists()
        binding.toggleSource.check(if (sourceIsEnhanced) R.id.btnSrcEnhanced else R.id.btnSrcOriginal)

        binding.toggleSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newEnhanced = checkedId == R.id.btnSrcEnhanced
            if (newEnhanced == sourceIsEnhanced) return@addOnButtonCheckedListener
            // 切换切分源需重新切分（图像内容不同）
            sourceIsEnhanced = newEnhanced
            SessionData.layoutUsedSource =
                if (sourceIsEnhanced) SessionData.SOURCE_ENHANCED else SessionData.SOURCE_ORIGINAL
            loadSourceAndSegment()
        }

        binding.btnResegment.setOnClickListener { loadSourceAndSegment() }
        binding.btnAddBox.setOnClickListener { binding.cropOverlay.addEmptyBox() }
        binding.btnDelete.setOnClickListener { binding.cropOverlay.deleteSelected() }

        binding.btnSolveAll.setOnClickListener { solveAll() }

        // 首次进入自动切分
        loadSourceAndSegment()
    }

    private fun loadSourceBitmapForDisplay() {
        val path = resolveSourcePath()
        currentSourcePath = path
        val bmp = ImageUtils.loadCompressed(path)
        binding.cropOverlay.setBitmap(bmp)
    }

    private fun loadSourceAndSegment() {
        loadSourceBitmapForDisplay()
        // 清空已有框，调用版面分析
        binding.cropOverlay.setBoxes(emptyList())
        showLoading("版面切分中…")
        lifecycleScope.launch {
            var boxes: List<RectBox> = emptyList()
            var errorMsg: String? = null
            withContext(Dispatchers.IO) {
                runCatching { baidu.layoutAnalysis(currentSourcePath) }
                    .onSuccess { boxes = it }
                    .onFailure { errorMsg = it.message ?: it.toString() }
            }
            val (w, h) = ImageUtils.imageSize(currentSourcePath)
                ?: (binding.cropOverlay.width to binding.cropOverlay.height)
            val normalized = boxes.map { it.toNormalized(w, h) }
            hideLoading()
            if (normalized.isEmpty()) {
                val hint = errorMsg?.let { "${getString(R.string.seg_no_box)}\n原因：$it" }
                    ?: getString(R.string.seg_no_box)
                binding.tvHint.text = hint
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SegmentationActivity)
                    .setTitle("版面切分失败")
                    .setMessage(errorMsg ?: "未识别到任何题目区域，可手动框选后解题。")
                    .setPositiveButton("手动框选") { _, _ -> binding.cropOverlay.addEmptyBox() }
                    .setNegativeButton(R.string.cancel, null)
                    .setCancelable(true)
                    .show()
            } else {
                binding.tvHint.text = getString(R.string.crop_drag_hint)
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
     * 全部解题：按当前切分源 + 各框（含手动标记）裁剪出单题图片，构建 QuestionItem 列表。
     */
    private fun solveAll() {
        val boxes = binding.cropOverlay.getBoxes()
        val manualFlags = binding.cropOverlay.getManualFlags()
        if (boxes.isEmpty()) {
            Snackbar.make(binding.root, R.string.seg_no_box, Snackbar.LENGTH_LONG).show()
            return
        }
        showLoading("准备题目…")
        lifecycleScope.launch {
            val workDir = File(SessionData.workDirPath)
            val sourcePath = currentSourcePath
            val questions = withContext(Dispatchers.IO) {
                val srcBmp = ImageUtils.loadCompressed(sourcePath)
                boxes.mapIndexed { i, rect ->
                    val cropped = ImageUtils.cropByNormalizedRect(srcBmp, rect)
                    val outFile = File(workDir, "q_${i + 1}.jpg")
                    val path = ImageUtils.saveCompressedJpeg(cropped, outFile)
                    QuestionItem(
                        sourceImage = path,
                        originalCompressedPath = SessionData.compressedOriginalPath,
                        isManuallyModified = manualFlags.getOrElse(i) { true },
                        rect = rect
                    )
                }
            }
            SessionData.questions = questions
            hideLoading()
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
