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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.net.BaiduApiClient
import com.questionsolver.app.net.LlmApiClient
import com.questionsolver.app.net.SolveEngine
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import com.questionsolver.app.ui.theme.StepBadgeColors
import com.questionsolver.app.ui.theme.difficultyColor
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
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPrev,
                        enabled = index > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("上一题", fontSize = 13.sp)
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("重试", fontSize = 13.sp)
                    }
                    Button(
                        onClick = onNext,
                        enabled = index < total - 1,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("下一题", fontSize = 13.sp)
                        Spacer(Modifier.size(4.dp))
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 进度条 + 题号
                ProgressDots(total = total, current = index)
                // 题目原图
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp
                ) {
                    AsyncImage(
                        model = File(displayPath),
                        contentDescription = "题目图片",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }
                if (loading) {
                    LoadingCard()
                } else if (error != null) {
                    ErrorCard(error = error, onRetry = onRetry)
                } else if (result != null) {
                    ResultContent(result)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    /** 顶部进度点：当前题高亮，其余灰点。 */
    @Composable
    private fun ProgressDots(total: Int, current: Int) {
        if (total <= 1) return
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            repeat(total) { i ->
                val active = i == current
                Box(
                    modifier = Modifier
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                )
            }
        }
    }

    /** 解析中骨架卡片。 */
    @Composable
    private fun LoadingCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(
                    text = getString(R.string.answer_loading),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 14.sp
                )
            }
        }
    }

    /** 错误卡片：带重试按钮。 */
    @Composable
    private fun ErrorCard(error: String, onRetry: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.error),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("!", color = MiuixTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("解析失败", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.answer_retry))
                }
            }
        }
    }

    @Composable
    private fun ResultContent(r: SolveResult) {
        // 答案卡：带难度色带
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                // 顶部色带：根据难度着色
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    difficultyColor(r.difficulty),
                                    difficultyColor(r.difficulty).copy(alpha = 0.5f)
                                )
                            )
                        )
                )
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("答案", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        if (r.difficulty.isNotBlank()) {
                            // 难度胶囊标签
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(difficultyColor(r.difficulty))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = r.difficulty,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        text = r.answer.ifBlank { getString(R.string.answer_unavailable) },
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        }
        // 思路提示
        if (r.hint.isNotBlank()) {
            SectionCard(title = "思路提示") {
                Text(r.hint, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
        // 分步解析：每步带循环色数字徽章
        SectionCard(title = "分步解析") {
            if (r.steps.isEmpty()) {
                Text(
                    getString(R.string.answer_unavailable),
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    r.steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            // 数字徽章
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(StepBadgeColors[i % StepBadgeColors.size]),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${i + 1}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.size(10.dp))
                            Text(
                                text = step,
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                lineHeight = 21.sp
                            )
                        }
                    }
                }
            }
        }
        // 知识点：胶囊标签
        if (r.knowledgePoints.isNotEmpty()) {
            SectionCard(title = "知识点") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(r.knowledgePoints) { pt ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = pt,
                                color = MiuixTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    /** 带标题的小节卡片。 */
    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(3.dp, 14.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                content()
            }
        }
    }

    @Composable
    private fun stringResource(resId: Int): String =
        androidx.compose.ui.res.stringResource(resId)

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
