package com.questionsolver.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
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
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener { takePhoto() }
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
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Snackbar.make(binding.root, "无法启动相机：${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
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

    /**
     * 拍摄后流程：
     *  1) 本地轻量压缩原图并保存（“仅压缩、未做百度增强”的原图）；
     *  2) 默认调用百度图像增强做全局预处理，保存增强全图；
     *  3) 进入切分页（SessionData 携带两条路径）。
     */
    private fun processAfterCapture(rawFile: File, workDir: File) {
        val cfg = AppConfig.load(this)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val compressedPath = withContext(Dispatchers.IO) {
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
                startActivity(android.content.Intent(this@CameraActivity, SegmentationActivity::class.java))
                finish()
            } catch (e: Exception) {
                hideLoading()
                // 增强失败时仍可使用原图进入切分
                SessionData.clear()
                SessionData.workDirPath = workDir.absolutePath
                SessionData.compressedOriginalPath = compressedPathSafe(workDir)
                SessionData.enhancedFullPath = compressedPathSafe(workDir)
                MaterialAlertDialogBuilder(this@CameraActivity)
                    .setTitle("图像增强失败")
                    .setMessage("百度增强接口调用失败：${e.message}\n是否使用原图继续切分？")
                    .setPositiveButton(R.string.ok) { _, _ ->
                        startActivity(android.content.Intent(this@CameraActivity, SegmentationActivity::class.java))
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
