package com.questionsolver.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.ui.theme.QuestionSolverTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar

/**
 * 服务配置页（MIUIX 重写）。
 *
 * 分两组卡片：百度智能云、大模型服务。底部保存按钮。
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.config_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 百度智能云
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("百度智能云")
                    TextField(
                        value = baiduApiKey,
                        onValueChange = { baiduApiKey = it },
                        label = "API Key",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = baiduSecretKey,
                        onValueChange = { baiduSecretKey = it },
                        label = "Secret Key",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = "打开控制台",
                            onClick = { onOpenUrl("https://console.bce.baidu.com") }
                        )
                        TextButton(
                            text = "查看文档",
                            onClick = { onOpenUrl("https://ai.baidu.com/ai-doc/OCR/Cmn8k7ihq") }
                        )
                    }
                }
            }
            // 大模型服务
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("大模型服务")
                    TextField(
                        value = llmDomain,
                        onValueChange = { llmDomain = it },
                        label = "域名（如 https://api.example.com）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = llmApiKey,
                        onValueChange = { llmApiKey = it },
                        label = "API Key",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = llmModel,
                        onValueChange = { llmModel = it },
                        label = "模型名",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("支持图像输入")
                        Switch(
                            checked = llmSupportsImage,
                            onCheckedChange = { llmSupportsImage = it }
                        )
                    }
                    Text(
                        if (llmSupportsImage) "当前模型支持图像，将直接发送题目图片"
                        else "当前模型不支持图像，仅发送 OCR 文字"
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
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
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) { Text(stringResource(R.string.config_save)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
