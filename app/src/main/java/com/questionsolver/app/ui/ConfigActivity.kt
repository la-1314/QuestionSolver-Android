package com.questionsolver.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.ui.theme.GlassBar
import com.questionsolver.app.ui.theme.GlassCard
import com.questionsolver.app.ui.theme.GlassRoot
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import com.questionsolver.app.ui.theme.ambientHalo
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 服务配置页（液态玻璃重写）。
 *
 * 氛围光晕背景 + 浮动玻璃顶栏 + 玻璃表单卡片 + 浮动玻璃保存栏。
 */
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AppConfig.load(this)
        setContent {
            QuestionSolverTheme {
                ConfigScreen(
                    initial = cfg,
                    onBack = { finish() },
                    onSave = { newCfg ->
                        AppConfig.save(this, newCfg)
                        finish()
                    },
                    onOpenUrl = { url -> openUrl(url) }
                )
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun ConfigScreen(
    initial: AppConfig,
    onBack: () -> Unit,
    onSave: (AppConfig) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var baiduApiKey by remember { mutableStateOf(initial.baiduApiKey) }
    var baiduSecretKey by remember { mutableStateOf(initial.baiduSecretKey) }
    var llmDomain by remember { mutableStateOf(initial.llmDomain) }
    var llmApiKey by remember { mutableStateOf(initial.llmApiKey) }
    var llmModel by remember { mutableStateOf(initial.llmModel) }
    var llmSupportsImage by remember { mutableStateOf(initial.llmSupportsImage) }

    Scaffold { padding ->
        GlassRoot(Modifier.padding(padding)) {
            // 氛围光晕背景
            Box(Modifier.fillMaxSize().ambientHalo())
            // 内容层
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp)
                ) {
                    // 浮动玻璃顶栏
                    GlassBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
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
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.config_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    // 分组标题：百度智能云
                    SectionHeader(title = "百度智能云", subtitle = "图像增强与 OCR 切分")
                    Spacer(Modifier.height(8.dp))
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 24.dp
                    ) {
                        Column(
                            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            LabeledTextField(
                                icon = Icons.Filled.Key,
                                label = "API Key",
                                value = baiduApiKey,
                                onValueChange = { baiduApiKey = it }
                            )
                            LabeledTextField(
                                icon = Icons.Filled.Key,
                                label = "Secret Key",
                                value = baiduSecretKey,
                                onValueChange = { baiduSecretKey = it }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    text = "控制台",
                                    onClick = { onOpenUrl("https://console.bce.baidu.com") }
                                )
                                Spacer(Modifier.size(8.dp))
                                TextButton(
                                    text = "文档",
                                    onClick = { onOpenUrl("https://ai.baidu.com/ai-doc/OCR/Cmn8k7ihq") }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    // 分组标题：大模型服务
                    SectionHeader(title = "大模型服务", subtitle = "题意理解与解题推理")
                    Spacer(Modifier.height(8.dp))
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 24.dp
                    ) {
                        Column(
                            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            LabeledTextField(
                                icon = Icons.Filled.Public,
                                label = "域名（如 https://api.example.com）",
                                value = llmDomain,
                                onValueChange = { llmDomain = it }
                            )
                            LabeledTextField(
                                icon = Icons.Filled.Key,
                                label = "API Key",
                                value = llmApiKey,
                                onValueChange = { llmApiKey = it }
                            )
                            LabeledTextField(
                                icon = Icons.Filled.Label,
                                label = "模型名",
                                value = llmModel,
                                onValueChange = { llmModel = it }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (llmSupportsImage) Icons.Filled.Image
                                        else Icons.Filled.SmartToy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MiuixTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text("支持图像输入")
                                }
                                Switch(
                                    checked = llmSupportsImage,
                                    onCheckedChange = { llmSupportsImage = it }
                                )
                            }
                            Text(
                                text = if (llmSupportsImage) "当前模型支持图像，将直接发送题目图片"
                                else "当前模型不支持图像，仅发送 OCR 文字",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(120.dp)) // 给底部保存栏留位
                }
                // 浮动玻璃底部保存栏
                GlassBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                ) {
                    Button(
                        onClick = {
                            onSave(
                                AppConfig(
                                    baiduApiKey = baiduApiKey.trim(),
                                    baiduSecretKey = baiduSecretKey.trim(),
                                    llmDomain = llmDomain.trim(),
                                    llmApiKey = llmApiKey.trim(),
                                    llmModel = llmModel.trim(),
                                    llmSupportsImage = llmSupportsImage
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        minHeight = 50.dp
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.config_save), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 分组标题：大字 + 小副标题，带主色渐变光点。 */
@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(4.dp, 18.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MiuixTheme.colorScheme.primary,
                            MiuixTheme.colorScheme.secondary
                        )
                    )
                )
        )
        Spacer(Modifier.size(10.dp))
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
    }
}

/** 带前置图标的 TextField 包装。 */
@Composable
private fun LabeledTextField(
    icon: ImageVector,
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}
