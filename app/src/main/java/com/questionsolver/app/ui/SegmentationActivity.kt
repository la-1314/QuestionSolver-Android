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
 * 多页显示：各页横向并排拼接为一张宽图（stitchHorizontally），UI 上"从左到右列表式并排"，
 * 因底图较宽，外层用 HorizontalScrollView 包裹可横向滑动查看。页间留明显间隔，视觉上如列表项分开。
 *
 * 多选拼接：点击「多选拼接」进入多选模式，每个框角标变复选框；勾选≥2个框后点「拼接选中」，
 * 按页号优先 + 页内 Y 次之的顺序，把选中框裁剪后纵向拼成一张合成图，作为新题目追加，
 * 原选中框删除。这样无需画复杂的跨页框，天然支持跨题/跨页合成。
 */
class SegmentationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySegmentationBinding
    private lateinit var baidu: BaiduApiClient
    private lateinit var cfg: AppConfig

    /** 当前使用的切分源是否为增强图。 */
    private var sourceIsEnhanced: Boolean = true

    /** 当前拼接后的展示位图（多页横向并排拼接）。 */
    private var stitchedBitmap: Bitmap? = null
    /** 拼接图保存为 JPEG 的文件路径，供 paperCutSegment 调用。 */
    private var stitchedFilePath: String = ""
    /** 各页结束 X 的归一化坐标（0~1，递增，末项 1.0）。空表示单页。 */
    private var pageBoundariesNorm: List<Float> = emptyList()

    /** 切分接口返回的每道题文字，按框顺序对应；手动新增的框无文字。 */
    private var pendingOcrTexts: List<String> = emptyList()

    /** 「多选拼接」生成的合成题图路径列表（独立文件，不属于拼接底图上的框）。 */
    private val mergedImagePaths = mutableListOf<String>()

    /** 「再拍一页」结果回调：接收新页的压缩原图路径与增强全图路径。 */
    private val captureAnotherPageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val compressed = data.getStringExtra(CameraActivity.EXTRA_RESULT_COMPRESSED) ?: return@registerForActivityResult
            val enhanced = data.getStringExtra(CameraActivity.EXTRA_RESULT_ENHANCED) ?: compressed
            val err = data.getStringExtra(CameraActivity.EXTRA_RESULT_ENHANCE_ERROR)
            SessionData.addPage(PageData(compressed, enhanced))
            if (err != null) {
                Snackbar.make(binding.root, "新页图像增强失败，已使用原图：$err", Snackbar.LENGTH_LONG).show()
            }
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
        binding.btnMultiSelect.setOnClickListener { toggleMultiSelectMode() }
        binding.btnMergeChecked.setOnClickListener { mergeCheckedBoxes() }

        // 多选模式下勾选状态变化时，刷新「拼接选中」按钮可见性与计数
        binding.cropOverlay.onBoxesChanged = {
            if (binding.cropOverlay.multiSelectMode) updateMergeButtonVisibility()
        }

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

    /** 切换多选模式。 */
    private fun toggleMultiSelectMode() {
        val newMode = !binding.cropOverlay.multiSelectMode
        binding.cropOverlay.multiSelectMode = newMode
        if (newMode) {
            binding.btnMultiSelect.text = "退出多选"
            updateMergeButtonVisibility()
            Snackbar.make(binding.root, "已进入多选模式，点击框切换勾选", Snackbar.LENGTH_SHORT).show()
        } else {
            binding.btnMultiSelect.text = getString(R.string.seg_multi_select)
            binding.btnMergeChecked.visibility = View.GONE
        }
    }

    /** 根据勾选数量更新「拼接选中」按钮可见性。 */
    private fun updateMergeButtonVisibility() {
        val count = binding.cropOverlay.checkedCount()
        binding.btnMergeChecked.visibility = if (count >= 2) View.VISIBLE else View.GONE
        binding.btnMergeChecked.text = "拼接选中（$count）"
    }

    /**
     * 多选拼接：把勾选的框按页号优先 + 页内 Y 次之排序，从展示位图裁剪后纵向拼接为一张合成图，
     * 保存为文件并作为新题目框追加（标记为手动），原勾选框删除。
     */
    private fun mergeCheckedBoxes() {
        val checked = binding.cropOverlay.getCheckedIndices()
        if (checked.size < 2) {
            Snackbar.make(binding.root, "请至少勾选 2 个框", Snackbar.LENGTH_SHORT).show()
            return
        }
        val srcBmp = stitchedBitmap ?: run {
            Snackbar.make(binding.root, "图片未加载", Snackbar.LENGTH_SHORT).show()
            return
        }
        showLoading("拼接中…")
        lifecycleScope.launch {
            val workDir = File(SessionData.workDirPath)
            val mergedPath = withContext(Dispatchers.IO) {
                // 按页号优先 + 页内 Y 次之排序
                val sorted = checked.sortedWith(compareBy({ pageIndexOf(it) }, { boxesYTop(it) }))
                val crops = sorted.map { idx ->
                    val rect = binding.cropOverlay.getBoxes()[idx]
                    ImageUtils.cropFromStitched(srcBmp, rect)
                }
                val merged = ImageUtils.stitchBitmapsVertically(crops)
                val outFile = File(workDir, "merged_${System.currentTimeMillis()}.jpg")
                ImageUtils.saveCompressedJpeg(merged, outFile)
            }
            mergedImagePaths.add(mergedPath)
            // 删除原勾选框，退出多选模式
            binding.cropOverlay.deleteBoxes(checked)
            binding.cropOverlay.multiSelectMode = false
            binding.btnMultiSelect.text = getString(R.string.seg_multi_select)
            binding.btnMergeChecked.visibility = View.GONE
            hideLoading()
            Snackbar.make(binding.root, "已生成拼接题：${File(mergedPath).name}", Snackbar.LENGTH_LONG)
                .setAction("撤销") {
                    runCatching { File(mergedPath).delete() }
                    mergedImagePaths.remove(mergedPath)
                    Snackbar.make(binding.root, "已撤销拼接", Snackbar.LENGTH_SHORT).show()
                }.show()
        }
    }

    /** 框所在页号（0-based）。基于框中心 X 与 pageBoundariesNorm 比较。 */
    private fun pageIndexOf(boxIndex: Int): Int {
        if (pageBoundariesNorm.isEmpty()) return 0
        val rect = binding.cropOverlay.getBoxes().getOrNull(boxIndex) ?: return 0
        val cx = rect.centerX()
        var page = 0
        for (boundary in pageBoundariesNorm.dropLast(1)) {
            if (cx > boundary) page++
            else break
        }
        return page
    }

    private fun boxesYTop(boxIndex: Int): Float =
        binding.cropOverlay.getBoxes().getOrNull(boxIndex)?.top ?: 0f

    /**
     * 重新拼接所有页（按当前切分源）并执行试卷切题识别。
     * - 单页：直接展示，不绘制页边界；
     * - 多页：横向并排拼接，计算页边界归一化 X，绘制竖向虚线分隔。
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
                    // 2) 横向并排拼接（UI 显示用）
                    val (stitched, ranges) = ImageUtils.stitchHorizontally(pageBmps)
                    stitchedBitmap = stitched
                    // 3) 计算页边界归一化 X
                    val totalW = stitched.width.toFloat()
                    pageBoundariesNorm = ranges.map { (startX, endX) -> endX / totalW }
                    // 4) 保存拼接图为 JPEG，供 paperCutSegment 调用
                    val outFile = File(SessionData.workDirPath, "stitched_source.jpg")
                    stitchedFilePath = ImageUtils.saveCompressedJpeg(stitched, outFile)
                    // 5) 调用试卷切题识别
                    val items = baidu.paperCutSegment(stitchedFilePath)
                    boxes = items.map { it.rect }
                    ocrTexts = items.map { it.text }
                }.onFailure { errorMsg = it.message ?: it.toString() }
            }
            val bmp = stitchedBitmap
            if (bmp == null) {
                hideLoading()
                binding.tvHint.text = "图片加载失败：${errorMsg ?: "未知错误"}"
                return@launch
            }
            binding.cropOverlay.setBitmap(bmp)
            binding.cropOverlay.setPageBoundaries(pageBoundariesNorm)
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
                val pageTip = if (SessionData.pageCount > 1) "（共${SessionData.pageCount}页并排，可左右滑动）" else ""
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
     */
    private fun solveAll() {
        val boxes = binding.cropOverlay.getBoxes()
        val manualFlags = binding.cropOverlay.getManualFlags()
        if (boxes.isEmpty() && mergedImagePaths.isEmpty()) {
            Snackbar.make(binding.root, R.string.seg_no_box, Snackbar.LENGTH_LONG).show()
            return
        }
        val srcBmp = stitchedBitmap
        if (boxes.isNotEmpty() && srcBmp == null) {
            Snackbar.make(binding.root, "图片未加载", Snackbar.LENGTH_LONG).show()
            return
        }
        // 若处于多选模式，提示先退出
        if (binding.cropOverlay.multiSelectMode) {
            Snackbar.make(binding.root, "请先退出多选模式", Snackbar.LENGTH_SHORT).show()
            return
        }
        showLoading("准备题目…")
        lifecycleScope.launch {
            val workDir = File(SessionData.workDirPath)
            val questions = withContext(Dispatchers.IO) {
                val fromBoxes = boxes.mapIndexed { i, rect ->
                    val cropped = ImageUtils.cropFromStitched(srcBmp!!, rect)
                    val outFile = File(workDir, "q_${i + 1}.jpg")
                    val path = ImageUtils.saveCompressedJpeg(cropped, outFile)
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
                // 多选拼接生成的合成题（独立图片文件，直接作为题目发送）
                val fromMerged = mergedImagePaths.map { path ->
                    QuestionItem(
                        sourceImage = path,
                        originalCompressedPath = SessionData.compressedOriginalPath,
                        isManuallyModified = true,
                        rect = RectF(),
                        ocrText = null
                    )
                }
                fromBoxes + fromMerged
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
