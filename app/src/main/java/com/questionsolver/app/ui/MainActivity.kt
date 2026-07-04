package com.questionsolver.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 首页（MIUIX 重写）。
 *
 * 两个入口：拍摄题目、服务配置。配置未完成时点击「拍摄题目」弹 SuperDialog 引导。
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
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text(
                text = if (ready) "配置就绪，点击「拍摄题目」开始" else stringResourceSafe(R.string.main_tip),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (ready) onTakePhoto() else showConfigDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) { Text(stringResourceSafe(R.string.main_take_photo)) }
            Button(
                onClick = onConfig,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors()
            ) { Text(stringResourceSafe(R.string.main_config)) }
        }
        // SuperDialog 需被 Scaffold 包裹以使用其 popup 容器
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

/** 简单封装 stringResource，避免 import 冗余。 */
@Composable
private fun stringResourceSafe(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)
