package com.questionsolver.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.net.BaiduApiClient
import com.questionsolver.app.ui.theme.GlassBar
import com.questionsolver.app.ui.theme.GlassCard
import com.questionsolver.app.ui.theme.GlassRoot
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import com.questionsolver.app.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 拍照页（液态玻璃重写）。
 *
 * CameraX 预览作为 GlassRoot 的背景被捕获，
 * 顶部/底部控制栏用真毛玻璃模糊预览画面。
 * 液态快门：白色外圈 + 渐变橙心 + 阴影光晕。
 */
class CameraActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val previewView: PreviewView by lazy { PreviewView(this) }

    private val returnMode: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_RETURN_MODE, false)
    }

    private val flashCycle = intArrayOf(
        ImageCapture.FLASH_MODE_OFF,
        ImageCapture.FLASH_MODE_ON,
        ImageCapture.FLASH_MODE_AUTO
    )
    private val flashLabels = intArrayOf(
        R.string.camera_flash_off,
        R.string.camera_flash_on,
        R.string.camera_flash_auto
    )

    private var flashIndex by mutableStateOf(0)
    private var currentZoom by mutableStateOf(1.0f)
    private var hasFlash by mutableStateOf(true)
    private var loading by mutableStateOf(false)
    private var loadingText by mutableStateOf("")
    private var snackbarMsg by mutableStateOf<String?>(null)
    private var enhanceError by mutableStateOf<String?>(null)
    private var showEnhanceErrorDialog by mutableStateOf(false)
    private var pendingUseOriginal by mutableStateOf<(() -> Unit)?>(null)

    private val minZoom = 1.0f
    private val maxZoom = 5.0f

    private val orientationListener: OrientationEventListener by lazy {
        object : OrientationEventListener(this) {
            private var lastRotation = Surface.ROTATION_0
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation in 45..134 -> Surface.ROTATION_270
                    orientation in 135..224 -> Surface.ROTATION_180
                    orientation in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    imageCapture?.targetRotation = rotation
                }
            }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else finish() }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> if (uri != null) importFromUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            QuestionSolverTheme {
                CameraScreen(
                    flashIndex = flashIndex,
                    currentZoom = currentZoom,
                    hasFlash = hasFlash,
                    loading = loading,
                    loadingText = loadingText,
                    snackbarMsg = snackbarMsg,
                    onDismissSnackbar = { snackbarMsg = null },
                    onClose = { finish() },
                    onCapture = { takePhoto() },
                    onCycleFlash = { cycleFlash() },
                    onOpenGallery = { openGallery() },
                    onCycleZoomPreset = { cycleZoomPreset() },
                    onZoomChange = { applyZoom(it) },
                    previewView = previewView,
                    showEnhanceErrorDialog = showEnhanceErrorDialog,
                    enhanceError = enhanceError,
                    onUseOriginal = {
                        showEnhanceErrorDialog = false
                        pendingUseOriginal?.invoke()
                        pendingUseOriginal = null
                    },
                    onCancelEnhanceError = { showEnhanceErrorDialog = false }
                )
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashCycle[flashIndex])
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                setupPinchToZoom()
                hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                if (!hasFlash) snackbarMsg = getString(R.string.camera_no_flash)
            } catch (e: Exception) {
                snackbarMsg = "无法启动相机：${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupPinchToZoom() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (currentZoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                applyZoom(next)
                return true
            }
        })
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }
    }

    private fun applyZoom(ratio: Float) {
        val cam = camera ?: return
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        currentZoom = clamped
        cam.cameraControl.setZoomRatio(clamped)
    }

    private fun cycleZoomPreset() {
        val next = when {
            currentZoom < 1.5f -> 2f
            currentZoom < 2.5f -> 3f
            currentZoom < 4.5f -> 5f
            else -> 1f
        }
        applyZoom(next)
    }

    private fun cycleFlash() {
        flashIndex = (flashIndex + 1) % flashCycle.size
        imageCapture?.flashMode = flashCycle[flashIndex]
        snackbarMsg = getString(flashLabels[flashIndex])
    }

    private fun openGallery() {
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val workDir = File(filesDir, "work_${System.currentTimeMillis()}").apply { mkdirs() }
        val rawFile = File(workDir, "raw.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(rawFile).build()

        loading = true; loadingText = "拍摄中…"
        capture.takePicture(outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    loadingText = "本地压缩与增强中…"
                    processAfterCapture(rawFile, workDir)
                }
                override fun onError(exc: ImageCaptureException) {
                    loading = false
                    snackbarMsg = "拍摄失败：${exc.message}"
                }
            })
    }

    private fun importFromUri(uri: Uri) {
        loading = true; loadingText = "导入图片中…"
        CoroutineScope(Dispatchers.Main).launch {
            val workDir = File(filesDir, "work_${System.currentTimeMillis()}").apply { mkdirs() }
            val rawFile = File(workDir, "raw.jpg")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        rawFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: false
                    rawFile.exists() && rawFile.length() > 0
                }.getOrElse { false }
            }
            if (!ok) {
                loading = false
                snackbarMsg = "图片导入失败"
                return@launch
            }
            loadingText = "本地压缩与增强中…"
            processAfterCapture(rawFile, workDir)
        }
    }

    private fun processAfterCapture(rawFile: File, workDir: File) {
        val cfg = AppConfig.load(this)
        CoroutineScope(Dispatchers.Main).launch {
            var compressedPath = ""
            try {
                compressedPath = withContext(Dispatchers.IO) {
                    val bmp = ImageUtils.loadCompressed(rawFile.absolutePath)
                    val out = File(workDir, "original_compressed.jpg")
                    ImageUtils.saveCompressedJpeg(bmp, out)
                }
                val enhancedPath = withContext(Dispatchers.IO) {
                    val baidu = BaiduApiClient(cfg)
                    val bytes = baidu.enhanceDefinition(compressedPath)
                    val out = File(workDir, "enhanced_full.jpg")
                    out.writeBytes(bytes)
                    out.absolutePath
                }
                loading = false
                finishCapture(compressedPath, enhancedPath, workDir.absolutePath, null)
            } catch (e: Exception) {
                loading = false
                val safeCompressed = compressedPath.ifBlank { compressedPathSafe(workDir) }
                if (returnMode) {
                    finishCapture(safeCompressed, safeCompressed, workDir.absolutePath, e.message)
                } else {
                    enhanceError = e.message
                    pendingUseOriginal = {
                        finishCapture(safeCompressed, safeCompressed, workDir.absolutePath, null)
                    }
                    showEnhanceErrorDialog = true
                }
            }
        }
    }

    private fun finishCapture(
        compressedPath: String,
        enhancedPath: String,
        workDirPath: String,
        enhanceError: String?
    ) {
        if (returnMode) {
            val data = Intent().apply {
                putExtra(EXTRA_RESULT_COMPRESSED, compressedPath)
                putExtra(EXTRA_RESULT_ENHANCED, enhancedPath)
                putExtra(EXTRA_RESULT_WORK_DIR, workDirPath)
                enhanceError?.let { putExtra(EXTRA_RESULT_ENHANCE_ERROR, it) }
            }
            setResult(RESULT_OK, data)
            finish()
        } else {
            SessionData.clear()
            SessionData.resetToSinglePage(
                PageData(compressedPath, enhancedPath),
                workDirPath
            )
            SessionData.layoutUsedSource = SessionData.SOURCE_ENHANCED
            startActivity(Intent(this@CameraActivity, SegmentationActivity::class.java))
            finish()
        }
    }

    private fun compressedPathSafe(workDir: File): String {
        val raw = File(workDir, "raw.jpg")
        if (raw.exists()) {
            val bmp = ImageUtils.loadCompressed(raw.absolutePath)
            return ImageUtils.saveCompressedJpeg(bmp, File(workDir, "original_compressed.jpg"))
        }
        return ""
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationListener.disable()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_RETURN_MODE = "return_mode"
        const val EXTRA_RESULT_COMPRESSED = "result_compressed"
        const val EXTRA_RESULT_ENHANCED = "result_enhanced"
        const val EXTRA_RESULT_WORK_DIR = "result_work_dir"
        const val EXTRA_RESULT_ENHANCE_ERROR = "result_enhance_error"
    }
}

@Composable
private fun CameraScreen(
    flashIndex: Int,
    currentZoom: Float,
    hasFlash: Boolean,
    loading: Boolean,
    loadingText: String,
    snackbarMsg: String?,
    onDismissSnackbar: () -> Unit,
    onClose: () -> Unit,
    onCapture: () -> Unit,
    onCycleFlash: () -> Unit,
    onOpenGallery: () -> Unit,
    onCycleZoomPreset: () -> Unit,
    onZoomChange: (Float) -> Unit,
    previewView: PreviewView,
    showEnhanceErrorDialog: Boolean,
    enhanceError: String?,
    onUseOriginal: () -> Unit,
    onCancelEnhanceError: () -> Unit
) {
    Scaffold { padding ->
        GlassRoot(Modifier.padding(padding)) {
            // 相机预览（作为玻璃模糊的背景源）
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            // 顶部玻璃控制栏
            GlassBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "关闭",
                        onClick = onClose
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircleIconButton(
                            icon = Icons.Filled.Bolt,
                            contentDescription = "闪光",
                            onClick = onCycleFlash,
                            enabled = hasFlash,
                            tint = if (flashIndex == 0) Color.White
                            else Color(0xFFFFD54F)
                        )
                        CircleIconButton(
                            icon = Icons.Filled.PhotoLibrary,
                            contentDescription = "图库",
                            onClick = onOpenGallery
                        )
                    }
                }
            }
            // 底部玻璃控制栏：变焦滑块 + 液态快门
            GlassBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 变焦滑块（玻璃胶囊标签）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.22f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = String.format("%.1fx", currentZoom),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Slider(
                            value = currentZoom,
                            onValueChange = onZoomChange,
                            valueRange = 1.0f..5.0f,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.22f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "5.0x",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                    }
                    // 中央液态快门 + 变焦预设
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.size(64.dp))
                        // 液态快门：白色外圈 + 渐变橙心 + 光晕
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .shadow(8.dp, CircleShape, ambientColor = Color(0xFFFF7043))
                                .clip(CircleShape)
                                .background(Color.White)
                                .padding(4.dp)
                                .clickable(enabled = !loading) { onCapture() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Color(0xFFFF7043), Color(0xFFFF5722), Color(0xFFE64A19))
                                        )
                                    )
                            )
                        }
                        // 变焦预设
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = onCycleZoomPreset) {
                                Text(
                                    text = "Z",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            // 加载 overlay（玻璃态）
            if (loading) {
                GlassCard(
                    modifier = Modifier.align(Alignment.Center),
                    cornerRadius = 24.dp
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(loadingText, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // Snackbar（玻璃胶囊）
            if (snackbarMsg != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 240.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    GlassCard(
                        cornerRadius = 16.dp,
                        tint = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text = snackbarMsg,
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
        // 增强失败对话框（依赖 Scaffold 的 MiuixPopupHost）
        if (showEnhanceErrorDialog) {
            OverlayDialog(
                title = "图像增强失败",
                show = showEnhanceErrorDialog,
                onDismissRequest = onCancelEnhanceError
            ) {
                Text("百度增强接口调用失败：${enhanceError ?: ""}\n是否使用原图继续切分？")
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onUseOriginal,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) { Text("使用原图") }
                Button(onClick = onCancelEnhanceError) { Text("取消") }
            }
        }
    }
}

/** 顶部圆形半透明图标按钮。 */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.20f else 0.08f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f)
        )
    }
}
