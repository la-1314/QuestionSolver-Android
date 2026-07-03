package com.questionsolver.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.databinding.ActivityCameraBinding
import com.questionsolver.app.net.BaiduApiClient
import com.questionsolver.app.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 闪光灯循环：OFF -> ON -> AUTO -> OFF
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
    private var flashIndex = 0

    // 变焦范围 1.0 ~ 5.0
    private val minZoom = 1.0f
    private val maxZoom = 5.0f
    private var currentZoom = 1.0f

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else finish() }

    // Photo Picker：从图库导入单张图片
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.btnZoomPreset.setOnClickListener { cycleZoomPreset() }

        binding.zoomSlider.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // progress 0~40 → 1.0~5.0
                val ratio = minZoom + (maxZoom - minZoom) * (progress / 40f)
                applyZoom(ratio)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

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
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashCycle[flashIndex])
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                setupPinchToZoom()
                // 检查设备是否支持闪光灯
                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                binding.btnFlash.isEnabled = hasFlash
                binding.btnFlash.alpha = if (hasFlash) 1f else 0.4f
                if (!hasFlash) {
                    Snackbar.make(binding.root, R.string.camera_no_flash, Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "无法启动相机：${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 双指捏合缩放：基于 PreviewView 的内置手势检测。 */
    private fun setupPinchToZoom() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val next = (currentZoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                applyZoom(next)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }
    }

    private fun applyZoom(ratio: Float) {
        val cam = camera ?: return
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        currentZoom = clamped
        cam.cameraControl.setZoomRatio(clamped)
        // 同步 UI
        binding.tvZoomValue.text = String.format("%.1fx", clamped)
        binding.btnZoomPreset.text = formatZoomPreset(clamped)
        val progress = ((clamped - minZoom) / (maxZoom - minZoom) * 40f).toInt().coerceIn(0, 40)
        binding.zoomSlider.progress = progress
    }

    /** 预设按钮循环：1x → 2x → 3x → 5x → 1x。 */
    private fun cycleZoomPreset() {
        val next = when {
            currentZoom < 1.5f -> 2f
            currentZoom < 2.5f -> 3f
            currentZoom < 4.5f -> 5f
            else -> 1f
        }
        applyZoom(next)
    }

    private fun formatZoomPreset(z: Float): String =
        if (z >= 1f) String.format("%.0fx", z) else String.format("%.1fx", z)

    private fun cycleFlash() {
        flashIndex = (flashIndex + 1) % flashCycle.size
        imageCapture?.flashMode = flashCycle[flashIndex]
        Snackbar.make(binding.root, flashLabels[flashIndex], Snackbar.LENGTH_SHORT).show()
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

        showLoading("拍摄中…")
        capture.takePicture(outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread { showLoading("本地压缩与增强中…") }
                    processAfterCapture(rawFile, workDir)
                }
                override fun onError(exc: ImageCaptureException) {
                    runOnUiThread {
                        hideLoading()
                        Snackbar.make(binding.root, "拍摄失败：${exc.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
            })
    }

    /** 从图库导入：复制到工作目录，作为 raw.jpg 走后续压缩+增强流程。 */
    private fun importFromUri(uri: Uri) {
        showLoading("导入图片中…")
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
                hideLoading()
                Snackbar.make(binding.root, "图片导入失败", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            showLoading("本地压缩与增强中…")
            processAfterCapture(rawFile, workDir)
        }
    }

    /**
     * 拍摄/导入后流程：
     *  1) 本地轻量压缩原图并保存（"仅压缩、未做百度增强"的原图）；
     *  2) 默认调用百度图像增强做全局预处理，保存增强全图；
     *  3) 进入切分页（SessionData 携带两条路径）。
     */
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
                // 调用百度图像增强（默认全局预处理）
                val enhancedPath = withContext(Dispatchers.IO) {
                    val baidu = BaiduApiClient(cfg)
                    val bytes = baidu.enhanceDefinition(compressedPath)
                    val out = File(workDir, "enhanced_full.jpg")
                    out.writeBytes(bytes)
                    out.absolutePath
                }
                SessionData.clear()
                SessionData.workDirPath = workDir.absolutePath
                SessionData.compressedOriginalPath = compressedPath
                SessionData.enhancedFullPath = enhancedPath
                SessionData.layoutUsedSource = SessionData.SOURCE_ENHANCED
                hideLoading()
                startActivity(Intent(this@CameraActivity, SegmentationActivity::class.java))
                finish()
            } catch (e: Exception) {
                hideLoading()
                // 增强失败时仍可使用原图进入切分
                SessionData.clear()
                SessionData.workDirPath = workDir.absolutePath
                SessionData.compressedOriginalPath = compressedPath.ifBlank { compressedPathSafe(workDir) }
                SessionData.enhancedFullPath = compressedPath.ifBlank { compressedPathSafe(workDir) }
                MaterialAlertDialogBuilder(this@CameraActivity)
                    .setTitle("图像增强失败")
                    .setMessage("百度增强接口调用失败：${e.message}\n是否使用原图继续切分？")
                    .setPositiveButton(R.string.ok) { _, _ ->
                        startActivity(Intent(this@CameraActivity, SegmentationActivity::class.java))
                        finish()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .setCancelable(false)
                    .show()
            }
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

    private fun showLoading(text: String) {
        binding.tvLoading.text = text
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
