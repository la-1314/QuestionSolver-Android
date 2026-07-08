package com.questionsolver.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃 / 高斯模糊 组件库。
 *
 * 设计语言：
 * - 真毛玻璃：[miuix-blur] 的 RuntimeShader（API 33+），模糊背后内容
 * - 通透感：白色低透明叠加 + 轻微提亮/提对比
 * - 氛围光：径向渐变光晕作为全局背景，由壁纸主色驱动
 * - 降级：不支持 RuntimeShader 时退化为半透明色块
 */

/** 当前玻璃模糊所依赖的背景捕获。由 [GlassRoot] 注入。 */
val LocalGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * 玻璃根容器：捕获背景内容并提供给子级的 [GlassCard] / [GlassPill] 模糊。
 *
 * 每个 Activity 的根 Box 用此包裹：先铺不透明底色（避免透明区扩散色伪影），
 * 再 [layerBackdrop] 捕获，最后通过 CompositionLocal 下发 backdrop 实例。
 */
@Composable
fun GlassRoot(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(surface)
                .layerBackdrop(backdrop),
            content = content
        )
    }
}

/**
 * 径向氛围光晕层。放在 [GlassRoot] 内容首层，营造立体呼吸光。
 * 居中径向渐变（主色→第三色→透明）+ 底部线性渐变（辅助色微光）。
 */
@Composable
fun Modifier.ambientHalo(): Modifier {
    val primary = MiuixTheme.colorScheme.primary
    val tertiary = MiuixTheme.colorScheme.tertiaryContainer
    val secondary = MiuixTheme.colorScheme.secondary
    return this
        .background(
            Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.22f),
                    tertiary.copy(alpha = 0.14f),
                    Color.Transparent
                )
            )
        )
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    secondary.copy(alpha = 0.10f)
                )
            )
        )
}

/**
 * 液态玻璃卡片：真高斯模糊背景 + 白色通透叠加。
 *
 * 在 API 33+ 上用 [textureBlur]；否则退化为半透明白底。
 *
 * @param cornerRadius 圆角，默认 24dp
 * @param tint 玻璃色调，默认白色 38%
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.White.copy(alpha = 0.38f),
    content: @Composable BoxScope.() -> Unit
) {
    val shape: Shape = RoundedCornerShape(cornerRadius)
    val supported = isRuntimeShaderSupported()
    val backdrop = LocalGlassBackdrop.current
    val base = modifier.clip(shape)
    if (supported && backdrop != null) {
        val colors = BlurDefaults.blurColors(
            blendColors = listOf(BlendColorEntry(tint, BlurBlendMode.SrcOver)),
            brightness = 0.06f,
            contrast = 1.06f,
            saturation = 1.08f
        )
        Box(
            modifier = base.textureBlur(backdrop = backdrop, shape = shape, colors = colors),
            content = content
        )
    } else {
        Box(modifier = base.background(tint), content = content)
    }
}

/**
 * 玻璃胶囊（标签/徽章）：弱模糊 + 半圆角。
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.45f),
    content: @Composable BoxScope.() -> Unit
) {
    val shape: Shape = RoundedCornerShape(50)
    val supported = isRuntimeShaderSupported()
    val backdrop = LocalGlassBackdrop.current
    val base = modifier.clip(shape)
    if (supported && backdrop != null) {
        val colors = BlurDefaults.blurColors(
            blendColors = listOf(BlendColorEntry(tint, BlurBlendMode.SrcOver)),
            brightness = 0.04f,
            contrast = 1.04f
        )
        Box(
            modifier = base.textureBlur(backdrop = backdrop, shape = shape, colors = colors),
            content = content
        )
    } else {
        Box(modifier = base.background(tint), content = content)
    }
}

/**
 * 玻璃浮动栏（顶栏/底栏通用）：横向全宽 + 大圆角 + 边距。
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.32f),
    content: @Composable BoxScope.() -> Unit
) {
    val shape: Shape = RoundedCornerShape(28.dp)
    val supported = isRuntimeShaderSupported()
    val backdrop = LocalGlassBackdrop.current
    val base = modifier.clip(shape)
    if (supported && backdrop != null) {
        val colors = BlurDefaults.blurColors(
            blendColors = listOf(BlendColorEntry(tint, BlurBlendMode.SrcOver)),
            brightness = 0.05f,
            contrast = 1.05f,
            saturation = 1.05f
        )
        Box(
            modifier = base.textureBlur(backdrop = backdrop, shape = shape, colors = colors),
            content = content
        )
    } else {
        Box(modifier = base.background(tint), content = content)
    }
}
