package com.questionsolver.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.databinding.ActivityAnswerBinding
import com.questionsolver.app.net.BaiduApiClient
import com.questionsolver.app.net.LlmApiClient
import com.questionsolver.app.net.SolveEngine
import com.questionsolver.app.util.SolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AnswerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnswerBinding
    private lateinit var engine: SolveEngine

    private var currentIndex = 0
    private val results = mutableListOf<SolveResult?>()
    private val errored = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnswerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val cfg = AppConfig.load(this)
        val workDir = File(SessionData.workDirPath.ifBlank { filesDir.absolutePath })
        engine = SolveEngine(
            config = cfg,
            baidu = BaiduApiClient(cfg),
            llm = LlmApiClient(cfg),
            workDir = workDir
        )

        if (SessionData.questions.isEmpty()) {
            finish()
            return
        }
        results.clear()
        repeat(SessionData.questions.size) { results.add(null) }

        binding.btnPrev.setOnClickListener {
            if (currentIndex > 0) { currentIndex--; showCurrent() }
        }
        binding.btnNext.setOnClickListener {
            if (currentIndex < SessionData.questions.size - 1) { currentIndex++; showCurrent() }
        }
        binding.btnRetry.setOnClickListener { solveCurrent() }

        showCurrent()
    }

    private fun showCurrent() {
        val total = SessionData.questions.size
        binding.tvIndex.text = "第 ${currentIndex + 1} / $total 题"
        binding.btnPrev.isEnabled = currentIndex > 0
        binding.btnNext.isEnabled = currentIndex < total - 1

        // 原题图
        val item = SessionData.questions[currentIndex]
        // 优先展示用于解题的“处理后图片”：手动框选=原图裁剪；自动切分=增强后（若有）
        val displayPath = item.enhancedImagePath?.takeIf { File(it).exists() } ?: item.sourceImage
        binding.ivQuestion.setImageURI(android.net.Uri.fromFile(File(displayPath)))

        val r = results.getOrNull(currentIndex)
        if (r != null) {
            renderResult(r)
        } else if (errored.contains(currentIndex)) {
            // 错误态由 overlay 显示，不覆盖
        } else {
            solveCurrent()
        }
    }

    private fun renderResult(r: SolveResult) {
        binding.tvAnswer.text = r.answer.ifBlank { "（未提供）" }
        binding.tvSteps.text = r.steps.ifBlank { "（未提供）" }
    }

    private fun solveCurrent() {
        showLoading()
        binding.errorOverlay.visibility = View.GONE
        val index = currentIndex
        val item = SessionData.questions[index]
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { engine.solve(item, index + 1) }
                results[index] = result
                errored.remove(index)
                hideLoading()
                if (currentIndex == index) renderResult(result)
            } catch (e: Exception) {
                hideLoading()
                errored.add(index)
                if (currentIndex == index) {
                    binding.tvError.text = getString(R.string.answer_error, e.message ?: "未知错误")
                    binding.errorOverlay.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showLoading() {
        binding.tvLoading.text = getString(R.string.answer_loading)
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
    }
}
