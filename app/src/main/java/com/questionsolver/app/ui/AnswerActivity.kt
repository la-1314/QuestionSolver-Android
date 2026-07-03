package com.questionsolver.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
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

        // 原题图：优先展示用于解题的"处理后图片"
        val item = SessionData.questions[currentIndex]
        val displayPath = item.enhancedImagePath?.takeIf { File(it).exists() } ?: item.sourceImage
        binding.ivQuestion.setImageURI(android.net.Uri.fromFile(File(displayPath)))

        // 切题时先清空解析区
        clearResultViews()

        val r = results.getOrNull(currentIndex)
        if (r != null) {
            renderResult(r)
        } else if (errored.contains(currentIndex)) {
            // 错误态由 overlay 显示，不覆盖
        } else {
            solveCurrent()
        }
    }

    private fun clearResultViews() {
        binding.tvAnswer.text = ""
        binding.tvHint.text = ""
        binding.tvHint.visibility = View.GONE
        binding.tvHintLabel.visibility = View.GONE
        binding.stepsContainer.removeAllViews()
        binding.chipsKnowledge.removeAllViews()
        binding.chipDifficulty.visibility = View.GONE
        binding.tvKnowledgeLabel.visibility = View.GONE
    }

    private fun renderResult(r: SolveResult) {
        // 1) 标准答案
        binding.tvAnswer.text = r.answer.ifBlank { getString(R.string.answer_unavailable) }

        // 2) 难度 chip
        if (r.difficulty.isNotBlank()) {
            binding.chipDifficulty.text = r.difficulty
            binding.chipDifficulty.chipBackgroundColor = difficultyColor(r.difficulty)
            binding.chipDifficulty.setTextColor(Color.WHITE)
            binding.chipDifficulty.visibility = View.VISIBLE
        } else {
            binding.chipDifficulty.visibility = View.GONE
        }

        // 3) 思路提示
        if (r.hint.isNotBlank()) {
            binding.tvHintLabel.visibility = View.VISIBLE
            binding.tvHint.text = r.hint
            binding.tvHint.visibility = View.VISIBLE
        } else {
            binding.tvHintLabel.visibility = View.GONE
            binding.tvHint.visibility = View.GONE
        }

        // 4) 分步解析：每步一张小卡片，带序号
        binding.stepsContainer.removeAllViews()
        if (r.steps.isEmpty()) {
            binding.stepsContainer.addView(buildStepView(1, getString(R.string.answer_unavailable)))
        } else {
            r.steps.forEachIndexed { i, text ->
                binding.stepsContainer.addView(buildStepView(i + 1, text))
            }
        }

        // 5) 知识点 chips
        if (r.knowledgePoints.isNotEmpty()) {
            binding.tvKnowledgeLabel.visibility = View.VISIBLE
            binding.chipsKnowledge.visibility = View.VISIBLE
            r.knowledgePoints.forEach { pt ->
                val chip = Chip(this).apply {
                    text = pt
                    isClickable = false
                    isCheckable = false
                }
                binding.chipsKnowledge.addView(chip)
            }
        } else {
            binding.tvKnowledgeLabel.visibility = View.GONE
            binding.chipsKnowledge.visibility = View.GONE
        }
    }

    /** 构造单步解析卡片：左侧序号圆点 + 右侧文字。 */
    private fun buildStepView(index: Int, text: String): View {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val numView = MaterialTextView(ctx).apply {
            text = index.toString()
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.step_number_bg)
            val lp = LinearLayout.LayoutParams(dp(28), dp(28))
            lp.marginEnd = dp(12)
            layoutParams = lp
        }

        val textView = MaterialTextView(ctx).apply {
            this.text = text
            textAppearance = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            isClickable = true
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        container.addView(numView)
        container.addView(textView)
        return container
    }

    private fun difficultyColor(level: String): android.content.res.ColorStateList {
        val color = when (level.trim()) {
            "简单" -> 0xFF4CAF50.toInt()
            "中等" -> 0xFFFF9800.toInt()
            "困难" -> 0xFFE53935.toInt()
            else -> 0xFF607D8B.toInt()
        }
        return android.content.res.ColorStateList.valueOf(color)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

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
