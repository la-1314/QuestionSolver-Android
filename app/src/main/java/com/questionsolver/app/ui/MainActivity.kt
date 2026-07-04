package com.questionsolver.app.ui

import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页（MIUIX 重写）。
 *
 * 两个入口：拍摄题目、服务配置。配置未完成时点击「拍摄题目」弹 OverlayDialog 引导。
 */
class MainActivity : ComponentActivity() {

    /** 配置是否就绪，onResume 时刷新以反映 ConfigActivity 的改动。 */
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
    Scaffold(
        topBar = { SmallTopAppBar(title = stringResourceSafe(R.string.app_name)) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            // 状态提示卡（带渐变背景）
            StatusCard(ready = ready)
            Spacer(Modifier.height(8.dp))
            // 主入口：拍摄题目
            EntryButton(
                icon = Icons.Filled.CameraAlt,
                label = stringResourceSafe(R.string.main_take_photo),
                primary = true,
                onClick = { if (ready) onTakePhoto() else showConfigDialog = true }
            )
            // 次入口：服务配置
            EntryButton(
                icon = Icons.Filled.Settings,
                label = stringResourceSafe(R.string.main_config),
                primary = false,
                onClick = onConfig
            )
        }
        // OverlayDialog 需被 Scaffold 包裹以使用其 popup 容器
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
                ) { Text(stringResourceSafe(R.string.main_config)) }
            }
        }
    }
}

/** 顶部状态提示卡片：根据就绪状态切换渐变与文案。 */
@Composable
private fun StatusCard(ready: Boolean) {
    val (gradient, tagText, tagColor) = if (ready) {
        Triple(
            listOf(Color(0xFF4CAF50), Color(0xFF2E7D32)),
            "已就绪",
            Color(0xFFC8E6C9)
        )
    } else {
        Triple(
            listOf(Color(0xFFFF9800), Color(0xFFEF6C00)),
            "待配置",
            Color(0xFFFFE0B2)
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(gradient))
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(tagColor.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = tagText,
                        fontSize = 11.sp,
                        color = Color(0xFF1B5E20).takeIf { ready } ?: Color(0xFFE65100),
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = if (ready) "配置就绪，点击「拍摄题目」开始"
                    else stringResourceSafe(R.string.main_tip),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 大号入口按钮：左侧圆形图标徽章 + 文案。 */
@Composable
private fun EntryButton(
    icon: ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary()
        else ButtonDefaults.buttonColors(),
        cornerRadius = 16.dp,
        minHeight = 56.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (primary) Color.White.copy(alpha = 0.25f)
                        else MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.takeIf { primary } ?: MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 简单封装 stringResource，避免 import 冗余。 */
@Composable
private fun stringResourceSafe(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)
