package com.questionsolver.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stairs
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
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
import com.questionsolver.app.ui.theme.GlassBar
import com.questionsolver.app.ui.theme.GlassCard
import com.questionsolver.app.ui.theme.GlassPill
import com.questionsolver.app.ui.theme.GlassRoot
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import com.questionsolver.app.ui.theme.StepBadgeColors
import com.questionsolver.app.ui.theme.ambientHalo
import com.questionsolver.app.ui.theme.difficultyColor
import com.questionsolver.app.util.SolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 答案展示页（液态玻璃重写）。
 *
 * 氛围光晕背景 + 浮动玻璃顶栏 + 玻璃题目图卡 + 玻璃答案/思路/分步/知识点卡 +
 * 浮动玻璃底栏（上一题 / 重试 / 下一题）。
 * 难度色带与步骤徽章色为语义色，不跟随 Monet。
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
        Scaffold { padding ->
            GlassRoot(Modifier.padding(padding)) {
                // 氛围光晕背景层（被玻璃组件模糊捕获）
                Box(Modifier.fillMaxSize().ambientHalo())
                // 内容层
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Spacer(Modifier.height(12.dp))
                        // 浮动玻璃顶栏
                        GlassBar(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable(onClick = onBack),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回",
                                        tint = MiuixTheme.colorScheme.onBackground
                                    )
                                }
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "第 ${index + 1} / $total 题",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.weight(1f))
                                // 进度指示胶囊
                                GlassPill(
                                    tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)
                                ) {
                                    Text(
                                        text = "${index + 1}/$total",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(Modifier.size(6.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        // 进度光带
                        ProgressRibbon(total = total, current = index)
                        Spacer(Modifier.height(4.dp))
                        // 题目原图（玻璃卡 + 渐变描边）
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 22.dp,
                            tint = Color.White.copy(alpha = 0.30f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(18.dp))
                            ) {
                                AsyncImage(
                                    model = File(displayPath),
                                    contentDescription = "题目图片",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                )
                            }
                        }
                        if (loading) {
                            LoadingCard()
                        } else if (error != null) {
                            ErrorCard(error = error, onRetry = onRetry)
                        } else if (result != null) {
                            ResultContent(result)
                        }
                        Spacer(Modifier.height(96.dp))
                    }
                    // 浮动玻璃底栏：上一题 / 重试 / 下一题
                    GlassBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassActionButton(
                                icon = Icons.Filled.ChevronLeft,
                                label = "上一题",
                                enabled = index > 0,
                                primary = false,
                                onClick = onPrev,
                                modifier = Modifier.weight(1f)
                            )
                            GlassActionButton(
                                icon = Icons.Filled.Refresh,
                                label = "重试",
                                enabled = true,
                                primary = false,
                                onClick = onRetry,
                                modifier = Modifier.weight(1f)
                            )
                            GlassActionButton(
                                icon = Icons.Filled.ChevronRight,
                                label = "下一题",
                                enabled = index < total - 1,
                                primary = true,
                                onClick = onNext,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    /** 进度光带：横向分段，当前段高亮渐变。 */
    @Composable
    private fun ProgressRibbon(total: Int, current: Int) {
        if (total <= 1) return
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(total) { i ->
                val active = i == current
                val done = i < current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) {
                                Brush.horizontalGradient(
                                    listOf(
                                        MiuixTheme.colorScheme.primary,
                                        MiuixTheme.colorScheme.tertiary
                                    )
                                )
                            } else if (done) {
                                Brush.horizontalGradient(
                                    listOf(
                                        MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
                                        MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)
                                    )
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        Color.White.copy(alpha = 0.12f)
                                    )
                                )
                            }
                        )
                )
            }
        }
    }

    /** 解析中骨架卡片。 */
    @Composable
    private fun LoadingCard() {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            tint = Color.White.copy(alpha = 0.34f)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = MiuixTheme.colorScheme.primary,
                        strokeWidth = 2.5.dp
                    )
                }
                Text(
                    text = stringResource(R.string.answer_loading),
                    color = MiuixTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "正在解析图像与题意…",
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 12.sp
                )
            }
        }
    }

    /** 错误卡片：带重试按钮。 */
    @Composable
    private fun ErrorCard(error: String, onRetry: () -> Unit) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            tint = Color(0xFFFF5252).copy(alpha = 0.14f)
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5252)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("!", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(12.dp))
                    Text("解析失败", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    lineHeight = 19.sp
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
        val diffColor = if (r.difficulty.isNotBlank()) difficultyColor(r.difficulty) else MiuixTheme.colorScheme.primary
        // 答案卡：顶部难度色带 + 玻璃卡体
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            tint = Color.White.copy(alpha = 0.38f)
        ) {
            Column {
                // 顶部难度色带
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    diffColor,
                                    diffColor.copy(alpha = 0.45f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(3.dp, 16.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("答案", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        if (r.difficulty.isNotBlank()) {
                            // 难度胶囊标签
                            GlassPill(
                                tint = diffColor.copy(alpha = 0.85f)
                            ) {
                                Text(
                                    text = r.difficulty,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = r.answer.ifBlank { stringResource(R.string.answer_unavailable) },
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        // 思路提示
        if (r.hint.isNotBlank()) {
            SectionCard(
                title = "思路提示",
                icon = Icons.Filled.Lightbulb,
                accent = Color(0xFFFFB300)
            ) {
                Text(r.hint, fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
        // 分步解析：每步带循环色数字徽章
        SectionCard(
            title = "分步解析",
            icon = Icons.Filled.Stairs,
            accent = MiuixTheme.colorScheme.tertiary
        ) {
            if (r.steps.isEmpty()) {
                Text(
                    stringResource(R.string.answer_unavailable),
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    r.steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            // 数字徽章：带光晕描边
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(StepBadgeColors[i % StepBadgeColors.size])
                                    .border(
                                        width = 2.dp,
                                        color = StepBadgeColors[i % StepBadgeColors.size].copy(alpha = 0.30f),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${i + 1}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.size(12.dp))
                            Text(
                                text = step,
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }
        }
        // 知识点：胶囊标签
        if (r.knowledgePoints.isNotEmpty()) {
            SectionCard(
                title = "知识点",
                icon = Icons.Filled.MenuBook,
                accent = MiuixTheme.colorScheme.secondary
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(r.knowledgePoints) { pt ->
                        GlassPill(
                            tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)
                        ) {
                            Text(
                                text = pt,
                                color = MiuixTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    /** 带标题与图标的玻璃小节卡片。 */
    @Composable
    private fun SectionCard(
        title: String,
        icon: ImageVector,
        accent: Color,
        content: @Composable () -> Unit
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            tint = Color.White.copy(alpha = 0.32f)
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Box(
                        modifier = Modifier
                            .size(3.dp, 16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(accent)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                content()
            }
        }
    }

    /** 浮动底栏的玻璃动作按钮。 */
    @Composable
    private fun GlassActionButton(
        icon: ImageVector,
        label: String,
        enabled: Boolean,
        primary: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val baseTint = if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.85f)
        else Color.White.copy(alpha = 0.40f)
        val disabledTint = Color.White.copy(alpha = 0.15f)
        Box(
            modifier = modifier
                .height(48.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (enabled) baseTint else disabledTint)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        if (primary) MiuixTheme.colorScheme.onPrimary
                        else MiuixTheme.colorScheme.onBackground
                    } else MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) {
                        if (primary) MiuixTheme.colorScheme.onPrimary
                        else MiuixTheme.colorScheme.onBackground
                    } else MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.5f)
                )
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
