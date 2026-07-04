package com.questionsolver.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.data.QuestionItem
import com.questionsolver.app.data.RectBox
import com.questionsolver.app.net.BaiduApiClient
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import com.questionsolver.app.view.CropBoxOverlayView
import com.questionsolver.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.io.File

/**
 * 试卷切分页（MIUIX 重写）。
 *
 * CropBoxOverlayView 用 AndroidView 包裹，保留自定义 Canvas 绘制与触摸/多选逻辑。
 * 多选拼接：单按钮三态（多选拼接 / 退出多选 / 拼接选中(N)）。
 */
class SegmentationActivity : ComponentActivity() {

    private lateinit var baidu: BaiduApiClient
    private lateinit var cfg: AppConfig

    /** Activity 持有的裁剪框自定义 View，供 AndroidView 包裹与多处调用。 */
    private val cropOverlay: CropBoxOverlayView by lazy {
        CropBoxOverlayView(this).also { v ->
            v.onBoxesChanged = { if (v.multiSelectMode) refreshMultiSelectState() }
        }
    }

    private var sourceIsEnhanced: Boolean = true
    private var stitchedBitmap: Bitmap? = null
    private var stitchedFilePath: String = ""
    private var pageBoundariesNorm: List<Float> = emptyList()
    private var pendingOcrTexts: List<String> = emptyList()
    private val mergedImagePaths = mutableListOf<String>()

    // UI 状态
    private var loading by mutableStateOf(false)
    private var loadingText by mutableStateOf("")
    private var hint by mutableStateOf("")
    private var multiSelectText by mutableStateOf("")
    private var snackbar by mutableStateOf<String?>(null)
    private var showSegmentFailDialog by mutableStateOf(false)
    private var segmentFailMsg by mutableStateOf("")

    private val captureAnotherPageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val compressed = data.getStringExtra(CameraActivity.EXTRA_RESULT_COMPRESSED)
                ?: return@registerForActivityResult
            val enhanced = data.getStringExtra(CameraActivity.EXTRA_RESULT_ENHANCED) ?: compressed
            val err = data.getStringExtra(CameraActivity.EXTRA_RESULT_ENHANCE_ERROR)
            SessionData.addPage(PageData(compressed, enhanced))
            if (err != null) snackbar = "新页图像增强失败，已使用原图：$err"
            rebuildStitchedAndSegment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionData.pages.isEmpty()) {
            finish()
            return
        }
        cfg = AppConfig.load(this)
        baidu = BaiduApiClient(cfg)
        sourceIsEnhanced = SessionData.pages.all { File(it.enhancedPath).exists() }
        SessionData.layoutUsedSource =
            if (sourceIsEnhanced) SessionData.SOURCE_ENHANCED else SessionData.SOURCE_ORIGINAL
        refreshMultiSelectState()

        setContent {
            QuestionSolverTheme {
                SegmentationScreen(
                    hint = hint,
                    loading = loading,
                    loadingText = loadingText,
                    multiSelectText = multiSelectText,
                    snackbar = snackbar,
                    onDismissSnackbar = { snackbar = null },
                    onBack = { finish() },
                    onResegment = { rebuildStitchedAndSegment() },
                    onAddBox = { cropOverlay.addEmptyBox() },
                    onDelete = { cropOverlay.deleteSelected() },
                    onMultiSelect = { onMultiSelectClick() },
                    onAnotherPage = { launchCaptureAnotherPage() },
                    onSolveAll = { solveAll() },
                    onToggleSource = { toggleSource() },
                    sourceIsEnhanced = sourceIsEnhanced,
                    cropOverlay = cropOverlay,
                    showSegmentFailDialog = showSegmentFailDialog,
                    segmentFailMsg = segmentFailMsg,
                    onManualBox = {
                        showSegmentFailDialog = false
                        cropOverlay.addEmptyBox()
                    },
                    onDismissSegmentFailDialog = { showSegmentFailDialog = false }
                )
            }
        }
        rebuildStitchedAndSegment()
    }

    private fun resolvePagePath(page: PageData): String =
        if (sourceIsEnhanced && File(page.enhancedPath).exists()) page.enhancedPath
        else page.compressedPath

    private fun toggleSource() {
        sourceIsEnhanced = !sourceIsEnhanced
        SessionData.layoutUsedSource =
            if (sourceIsEnhanced) SessionData.SOURCE_ENHANCED else SessionData.SOURCE_ORIGINAL
        rebuildStitchedAndSegment()
    }

    private fun launchCaptureAnotherPage() {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_RETURN_MODE, true)
        }
        captureAnotherPageLauncher.launch(intent)
    }

    private fun onMultiSelectClick() {
        if (!cropOverlay.multiSelectMode) {
            cropOverlay.multiSelectMode = true
            refreshMultiSelectState()
            snackbar = "已进入多选，点框切换勾选，勾≥2个即可拼接"
            return
        }
        if (cropOverlay.checkedCount() < 2) {
            exitMultiSelect()
            return
        }
        mergeCheckedBoxes()
    }

    private fun exitMultiSelect() {
        cropOverlay.multiSelectMode = false
        refreshMultiSelectState()
    }

    private fun refreshMultiSelectState() {
        multiSelectText = when {
            !cropOverlay.multiSelectMode -> getString(R.string.seg_multi_select)
            cropOverlay.checkedCount() < 2 -> "退出多选"
            else -> "拼接选中（${cropOverlay.checkedCount()}）"
        }
    }

    private fun mergeCheckedBoxes() {
        val checked = cropOverlay.getCheckedIndices()
        if (checked.size < 2) {
            snackbar = "请至少勾选 2 个框"
            return
        }
        val srcBmp = stitchedBitmap ?: run {
            snackbar = "图片未加载"
            return
        }
        loading = true; loadingText = "拼接中…"
        lifecycleScope.launch {
            val workDir = File(SessionData.workDirPath)
            val mergedPath = withContext(Dispatchers.IO) {
                val sorted = checked.sortedWith(compareBy({ pageIndexOf(it) }, { boxesYTop(it) }))
                val crops = sorted.map { idx ->
                    val rect = cropOverlay.getBoxes()[idx]
                    ImageUtils.cropFromStitched(srcBmp, rect)
                }
                val merged = ImageUtils.stitchBitmapsVertically(crops)
                val outFile = File(workDir, "merged_${System.currentTimeMillis()}.jpg")
                ImageUtils.saveCompressedJpeg(merged, outFile)
            }
            mergedImagePaths.add(mergedPath)
            cropOverlay.deleteBoxes(checked)
            exitMultiSelect()
            loading = false
            snackbar = "已生成拼接题：${File(mergedPath).name}"
        }
    }

    private fun pageIndexOf(boxIndex: Int): Int {
        if (pageBoundariesNorm.isEmpty()) return 0
        val rect = cropOverlay.getBoxes().getOrNull(boxIndex) ?: return 0
        val cx = rect.centerX()
        var page = 0
        for (boundary in pageBoundariesNorm.dropLast(1)) {
            if (cx > boundary) page++ else break
        }
        return page
    }

    private fun boxesYTop(boxIndex: Int): Float =
        cropOverlay.getBoxes().getOrNull(boxIndex)?.top ?: 0f

    private fun rebuildStitchedAndSegment() {
        loading = true; loadingText = "拼接与切分中，请稍候…"
        lifecycleScope.launch {
            var errorMsg: String? = null
            var boxes: List<RectBox> = emptyList()
            var ocrTexts: List<String> = emptyList()
            withContext(Dispatchers.IO) {
                runCatching {
                    val pageBmps = SessionData.pages.map { ImageUtils.loadCompressed(resolvePagePath(it)) }
                    val (stitched, ranges) = ImageUtils.stitchHorizontally(pageBmps)
                    stitchedBitmap = stitched
                    val totalW = stitched.width.toFloat()
                    pageBoundariesNorm = ranges.map { (startX, endX) -> endX / totalW }
                    val outFile = File(SessionData.workDirPath, "stitched_source.jpg")
                    stitchedFilePath = ImageUtils.saveCompressedJpeg(stitched, outFile)
                    val items = baidu.paperCutSegment(stitchedFilePath)
                    boxes = items.map { it.rect }
                    ocrTexts = items.map { it.text }
                }.onFailure { errorMsg = it.message ?: it.toString() }
            }
            val bmp = stitchedBitmap
            if (bmp == null) {
                loading = false
                hint = "图片加载失败：${errorMsg ?: "未知错误"}"
                return@launch
            }
            cropOverlay.setBitmap(bmp)
            cropOverlay.setPageBoundaries(pageBoundariesNorm)
            val w = bmp.width
            val h = bmp.height
            val normalized = boxes.map { it.toNormalized(w, h) }
            pendingOcrTexts = ocrTexts
            loading = false
            if (normalized.isEmpty()) {
                hint = errorMsg?.let { "${getString(R.string.seg_no_box)}\n原因：$it" }
                    ?: getString(R.string.seg_no_box)
                segmentFailMsg = errorMsg ?: "未识别到任何题目区域，可手动框选后解题。"
                showSegmentFailDialog = true
            } else {
                val pageTip = if (SessionData.pageCount > 1) "（共${SessionData.pageCount}页并排，可左右滑动）" else ""
                hint = getString(R.string.crop_drag_hint) + pageTip
                cropOverlay.setBoxes(normalized)
            }
        }
    }

    private fun RectBox.toNormalized(w: Int, h: Int): RectF = RectF(
        x.toFloat() / w,
        y.toFloat() / h,
        (x + width).toFloat() / w,
        (y + height).toFloat() / h
    )

    private fun solveAll() {
        val boxes = cropOverlay.getBoxes()
        val manualFlags = cropOverlay.getManualFlags()
        if (boxes.isEmpty() && mergedImagePaths.isEmpty()) {
            snackbar = getString(R.string.seg_no_box)
            return
        }
        val srcBmp = stitchedBitmap
        if (boxes.isNotEmpty() && srcBmp == null) {
            snackbar = "图片未加载"
            return
        }
        if (cropOverlay.multiSelectMode) {
            snackbar = "请先退出多选模式"
            return
        }
        loading = true; loadingText = "准备题目…"
        lifecycleScope.launch {
            val workDir = File(SessionData.workDirPath)
            val questions = withContext(Dispatchers.IO) {
                val fromBoxes = boxes.mapIndexed { i, rect ->
                    val cropped = ImageUtils.cropFromStitched(srcBmp!!, rect)
                    val outFile = File(workDir, "q_${i + 1}.jpg")
                    val path = ImageUtils.saveCompressedJpeg(cropped, outFile)
                    val isManual = manualFlags.getOrElse(i) { true }
                    QuestionItem(
                        sourceImage = path,
                        originalCompressedPath = SessionData.compressedOriginalPath,
                        isManuallyModified = isManual,
                        rect = rect
                    )
                }
                val fromMerged = mergedImagePaths.map { path ->
                    QuestionItem(
                        sourceImage = path,
                        originalCompressedPath = SessionData.compressedOriginalPath,
                        isManuallyModified = true,
                        rect = RectF()
                    )
                }
                fromBoxes + fromMerged
            }
            SessionData.questions = questions
            loading = false
            startActivity(Intent(this@SegmentationActivity, AnswerActivity::class.java))
        }
    }
}

@Composable
private fun SegmentationScreen(
    hint: String,
    loading: Boolean,
    loadingText: String,
    multiSelectText: String,
    snackbar: String?,
    onDismissSnackbar: () -> Unit,
    onBack: () -> Unit,
    onResegment: () -> Unit,
    onAddBox: () -> Unit,
    onDelete: () -> Unit,
    onMultiSelect: () -> Unit,
    onAnotherPage: () -> Unit,
    onSolveAll: () -> Unit,
    onToggleSource: () -> Unit,
    sourceIsEnhanced: Boolean,
    cropOverlay: CropBoxOverlayView,
    showSegmentFailDialog: Boolean,
    segmentFailMsg: String,
    onManualBox: () -> Unit,
    onDismissSegmentFailDialog: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = "试卷切分",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 提示文字
            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            // 裁剪框图层（自定义 View，横向可滑动）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { cropOverlay },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 底部操作区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 源切换 + 重切分
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onToggleSource,
                        colors = if (sourceIsEnhanced) ButtonDefaults.buttonColorsPrimary()
                        else ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (sourceIsEnhanced) "增强图" else "原图") }
                    Button(
                        onClick = onResegment,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("重切分")
                    }
                }
                // 添加框 / 删除 / 多选拼接
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAddBox,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("添加框")
                    }
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("删除")
                    }
                    Button(
                        onClick = onMultiSelect,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f)
                    ) { Text(multiSelectText) }
                }
                // 再拍一页 / 全部解题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAnotherPage,
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Cameraswitch, contentDescription = null)
                        Text("再拍一页")
                    }
                    Button(
                        onClick = onSolveAll,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("全部解题")
                    }
                }
            }
            // Snackbar
            if (snackbar != null) {
                Text(
                    text = snackbar,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
        // 加载 overlay
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(8.dp))
                    Text(loadingText)
                }
            }
        }
        // 切分失败对话框（必须放在 Scaffold 内，依赖 MiuixPopupHost 渲染弹窗）
        if (showSegmentFailDialog) {
            OverlayDialog(
                title = "试卷切分失败",
                show = showSegmentFailDialog,
                onDismissRequest = onDismissSegmentFailDialog
            ) {
                Text(segmentFailMsg.ifBlank { "未识别到任何题目区域，可手动框选后解题。" })
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onManualBox,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) { Text("手动框选") }
                Button(onClick = onDismissSegmentFailDialog) { Text("取消") }
            }
        }
    }
}
