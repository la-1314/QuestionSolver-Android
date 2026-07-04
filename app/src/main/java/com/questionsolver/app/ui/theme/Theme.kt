package com.questionsolver.app.ui.theme

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX 主题封装。
 *
 * 全局入口：所有 Compose 页面用 [QuestionSolverTheme] 包裹，
 * 内部委托给 [MiuixTheme]，保证 Xiaomi HyperOS 视觉风格统一。
 * 后续如需自定义颜色方案，在此处覆写 MiuixTheme 的 colorScheme 参数即可。
 */
@Composable
fun QuestionSolverTheme(content: @Composable () -> Unit) {
    MiuixTheme(content = content)
}
