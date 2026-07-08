package com.questionsolver.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * MIUIX 主题封装（Monet 动态取色）。
 *
 * 全局入口：所有 Compose 页面用 [QuestionSolverTheme] 包裹，
 * 内部委托给 [MiuixTheme]，保证 Xiaomi HyperOS 视觉风格统一。
 *
 * 主题模式使用 [ColorSchemeMode.MonetSystem]：
 * - 自动跟随系统深色/浅色模式切换
 * - 从系统壁纸提取主色（Android 12+ Material You）生成调色板
 * - 采用 [ThemePaletteStyle.TonalSpot] + [ThemeColorSpec.Spec2025]，
 *   获得更新更和谐的色彩规范
 *
 * 若系统未启用动态取色或低于 Android 12，会优雅回退到默认 Miuix 配色。
 */
@Composable
fun QuestionSolverTheme(content: @Composable () -> Unit) {
    val controller = remember {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.MonetSystem,
            paletteStyle = ThemePaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.Spec2025
        )
    }
    MiuixTheme(controller = controller, content = content)
}

/* ==================== 难度色板 ==================== */

/** 题目难度对应的语义色（背景）。 */
object DifficultyColors {
    val Easy: Color = Color(0xFF4CAF50)
    val Medium: Color = Color(0xFFFF9800)
    val Hard: Color = Color(0xFFE53935)
    val Unknown: Color = Color(0xFF607D8B)
}

/** 根据难度文字返回对应语义色。 */
fun difficultyColor(level: String): Color = when (level.trim()) {
    "简单" -> DifficultyColors.Easy
    "中等" -> DifficultyColors.Medium
    "困难" -> DifficultyColors.Hard
    else -> DifficultyColors.Unknown
}

/* ==================== 分步解析序号徽章色 ==================== */

/** 分步解析的数字徽章颜色序列，循环使用以区分步骤。 */
val StepBadgeColors: List<Color> = listOf(
    Color(0xFF3F51B5),
    Color(0xFF009688),
    Color(0xFF8E24AA),
    Color(0xFFEF6C00),
    Color(0xFF3949AB),
    Color(0xFFD81B60),
)
