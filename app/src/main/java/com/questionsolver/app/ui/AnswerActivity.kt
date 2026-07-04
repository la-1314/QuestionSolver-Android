package com.questionsolver.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.net.BaiduApiClient
import com.questionsolver.app.net.LlmApiClient
import com.questionsolver.app.net.SolveEngine
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import com.questionsolver.app.util.SolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import java.io.File

/**
 * 答案展示页（MIUIX 重写）。
 *
 * 顶部题目图，下方答案/思路/分步解析/知识点，底部上一题/下一题/重试。
 */
class AnswerActivity : ComponentActivity() {

    private lateinit var engine: SolveEngine
    private val currentIndex = mutableStateOf(0)
    private val results = mutableStateListOf<SolveResult?>()
    private val erroredIndices = mutableStateListOf<Int>()
    private val loading = mutableStateOf(false)
    private val errorMsg = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionData.questions.isEmpty()) {
            finish()
            return
        }
        val cfg = AppConfig.load(this)
        val workDir = File(SessionData.workDirPath.ifBlank { filesDir.absolutePath })
        engine = SolveEngine(
            config = cfg,
            baidu = BaiduApiClient(cfg),
            llm = LlmApiClient(cfg),
            workDir = workDir
        )
        results.clear()
        repeat(SessionData.questions.size) { results.add(null) }

        setContent {
            QuestionSolverTheme {
                val idx = currentIndex.value
                AnswerScreen(
                    total = SessionData.questions.size,
                    index = idx,
                    result = results.getOrNull(idx),
                    error = if (erroredIndices.contains(idx)) errorMsg.value else null,
                    loading = loading.value,
                    onPrev = { goTo(currentIndex.value - 1) },
                    onNext = { goTo(currentIndex.value + 1) },
                    onRetry = { retryCurrent() },
                    onBack = { finish() }
                )
            }
        }
        solveCurrent()
    }

    /** 切换到指定题目并触发解题（若未解过）。 */
    private fun goTo(target: Int) {
        if (target !in SessionData.questions.indices) return
        currentIndex.value = target
        solveCurrent()
    }

    /** 重试当前题：清除已有错误/结果后重新请求。 */
    private fun retryCurrent() {
        val i = currentIndex.value
        erroredIndices.remove(i)
        results[i] = null
        solveCurrent()
    }

    @Composable
    private fun AnswerScreen(
        total: Int,
        index: Int,
        result: SolveResult?,
        error: String?,
        loading: Boolean,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onRetry: () -> Unit,
        onBack: () -> Unit
    ) {
        val item = SessionData.questions[index]
        val displayPath = item.enhancedImagePath?.takeIf { File(it).exists() } ?: item.sourceImage
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "第 ${index + 1} / $total 题",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            },
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPrev,
                        enabled = index > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                        Text("上一题")
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("重试")
                    }
                    Button(
                        onClick = onNext,
                        enabled = index < total - 1,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("下一题")
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 题目原图
                Card {
                    AsyncImage(
                        model = File(displayPath),
                        contentDescription = "题目图片",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
                if (loading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("正在解析…")
                        }
                    }
                } else if (error != null) {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("解析失败", fontWeight = FontWeight.Bold)
                            Text(error)
                        }
                    }
                } else if (result != null) {
                    ResultContent(result)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun ResultContent(r: SolveResult) {
        // 答案
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("答案", fontWeight = FontWeight.Bold)
                Text(r.answer.ifBlank { getString(R.string.answer_unavailable) })
                if (r.difficulty.isNotBlank()) {
                    Text(
                        text = r.difficulty,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(difficultyColor(r.difficulty))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White
                    )
                }
            }
        }
        // 思路提示
        if (r.hint.isNotBlank()) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("思路提示", fontWeight = FontWeight.Bold)
                    Text(r.hint)
                }
            }
        }
        // 分步解析
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("分步解析", fontWeight = FontWeight.Bold)
                if (r.steps.isEmpty()) {
                    Text(getString(R.string.answer_unavailable))
                } else {
                    r.steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${i + 1}",
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF3F51B5))
                                    .padding(4.dp),
                                color = Color.White
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(step, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // 知识点
        if (r.knowledgePoints.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("知识点", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(r.knowledgePoints) { pt ->
                            Text(
                                text = pt,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFFE8EAF6))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun difficultyColor(level: String): Color = when (level.trim()) {
        "简单" -> Color(0xFF4CAF50)
        "中等" -> Color(0xFFFF9800)
        "困难" -> Color(0xFFE53935)
        else -> Color(0xFF607D8B)
    }

    private fun solveCurrent() {
        val index = currentIndex.value
        if (index !in SessionData.questions.indices) return
        // 已有结果或已记录错误则不重复请求
        if (results[index] != null || erroredIndices.contains(index)) return
        loading.value = true
        errorMsg.value = null
        val item = SessionData.questions[index]
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { engine.solve(item, index + 1) }
                results[index] = result
                erroredIndices.remove(index)
            } catch (e: Exception) {
                erroredIndices.add(index)
                errorMsg.value = getString(R.string.answer_error, e.message ?: "未知错误")
            } finally {
                loading.value = false
            }
        }
    }
}
