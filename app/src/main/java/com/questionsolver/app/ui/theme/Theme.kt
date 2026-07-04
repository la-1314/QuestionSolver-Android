package com.questionsolver.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * MIUIX 主题封装。
 *
 * 全局入口：所有 Compose 页面用 [QuestionSolverTheme] 包裹，
 * 内部委托给 [MiuixTheme]，保证 Xiaomi HyperOS 视觉风格统一。
 *
 * 主题模式使用 [ColorSchemeMode.System]，自动跟随系统深色/浅色模式切换。
 */
@Composable
fun QuestionSolverTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}
