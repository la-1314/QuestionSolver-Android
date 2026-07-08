package com.questionsolver.app.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页（液态玻璃重写）。
 *
 * 氛围光晕背景 + 浮动玻璃顶栏 + 玻璃状态卡 + 玻璃入口按钮。
 * 配置未完成时点击「拍摄题目」弹 OverlayDialog 引导。
 */
class MainActivity : ComponentActivity() {

    private val ready = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuestionSolverTheme {
                MainScreen(
                    ready = ready.value,
                    onTakePhoto = {
                        if (ready.value) {
                            startActivity(Intent(this, CameraActivity::class.java))
                        }
                    },
                    onConfig = { startActivity(Intent(this, ConfigActivity::class.java)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val cfg = AppConfig.load(this)
        ready.value = cfg.isBaiduReady() && cfg.isLlmReady()
    }
}

@Composable
private fun MainScreen(
    ready: Boolean,
    onTakePhoto: () -> Unit,
    onConfig: () -> Unit
) {
    var showConfigDialog by remember { mutableStateOf(false) }
    Scaffold { padding ->
        GlassRoot(Modifier.padding(padding)) {
            // 氛围光晕背景层（被玻璃组件模糊捕获）
            Box(Modifier.fillMaxSize().ambientHalo())
            // 内容层
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 浮动玻璃顶栏
                GlassBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 20.dp)
                    )
                }
                Spacer(Modifier.height(28.dp))
                // 玻璃状态卡
                StatusCard(ready = ready)
                Spacer(Modifier.height(20.dp))
                // 玻璃入口：拍摄题目
                GlassEntryButton(
                    icon = Icons.Filled.CameraAlt,
                    label = stringResource(R.string.main_take_photo),
                    primary = true,
                    onClick = { if (ready) onTakePhoto() else showConfigDialog = true }
                )
                Spacer(Modifier.height(12.dp))
                // 玻璃入口：服务配置
                GlassEntryButton(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.main_config),
                    primary = false,
                    onClick = onConfig
                )
            }
            // OverlayDialog 依赖 Scaffold 的 MiuixPopupHost
            if (showConfigDialog) {
                OverlayDialog(
                    title = "配置未完成",
                    show = showConfigDialog,
                    onDismissRequest = { showConfigDialog = false }
                ) {
                    Text("请先在「服务配置」中填写百度 API Key/Secret Key 与大模型域名/密钥/模型名。")
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showConfigDialog = false
                            onConfig()
                        },
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) { Text(stringResource(R.string.main_config)) }
                }
            }
        }
    }
}

/** 玻璃状态卡：渐变色带 + 状态胶囊 + 文案。 */
@Composable
private fun StatusCard(ready: Boolean) {
    val (tagText, tagColor, tagTextColor) = if (ready) {
        Triple("已就绪", Color(0xFF4CAF50), Color.White)
    } else {
        Triple("待配置", Color(0xFFFF9800), Color.White)
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 28.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        if (ready) listOf(Color(0xFF4CAF50).copy(alpha = 0.18f), Color.Transparent)
                        else listOf(Color(0xFFFF9800).copy(alpha = 0.20f), Color.Transparent)
                    )
                )
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(tagColor.copy(alpha = 0.90f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = tagText,
                        fontSize = 11.sp,
                        color = tagTextColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = if (ready) "配置就绪，点击「拍摄题目」开始"
                    else stringResource(R.string.main_tip),
                    color = MiuixTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 大号玻璃入口按钮：左侧圆形图标徽章 + 文案。 */
@Composable
private fun GlassEntryButton(
    icon: ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 22.dp,
        tint = if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.30f)
        else Color.White.copy(alpha = 0.32f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)
                        else MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (primary) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (primary) MiuixTheme.colorScheme.onPrimary
                else MiuixTheme.colorScheme.onBackground
            )
        }
    }
}
