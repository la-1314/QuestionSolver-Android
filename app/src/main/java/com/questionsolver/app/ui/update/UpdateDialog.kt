package com.questionsolver.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.questionsolver.app.update.UpdateDownloader
import com.questionsolver.app.update.UpdateInfo
import com.questionsolver.app.ui.theme.GlassCard
import com.questionsolver.app.ui.theme.GlassPill
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 更新对话框（液态玻璃风格）。
 *
 * 展示新版本信息、更新日志、下载源选择（官方网站 / 镜像源）、下载进度，
 * 下载完成后调用系统安装器。
 *
 * @param info 最新 Release 信息
 * @param currentVersion 当前版本号
 * @param suggestedSource 检测阶段建议的源（直连成功=官方，超时降级=镜像）
 * @param downloader 下载器实例
 * @param onDismiss 关闭回调
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    suggestedSource: UpdateDownloader.Source,
    downloader: UpdateDownloader,
    onDismiss: () -> Unit
) {
    val state by downloader.state.collectAsState()
    var selectedSource by remember { mutableStateOf(suggestedSource) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(vertical = 40.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.98f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶部：图标 + 标题 + 关闭按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MiuixTheme.colorScheme.primary,
                                    MiuixTheme.colorScheme.tertiaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("发现新版本", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "v$currentVersion → v${info.version}",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(enabled = state !is UpdateDownloader.State.Downloading) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // 版本标签 + 文件大小
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassPill(tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.22f)) {
                    Text(
                        text = info.tagName,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (info.apkSize > 0) {
                    Text(
                        text = UpdateDownloader.formatSize(info.apkSize),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant
                    )
                }
            }
            // 更新日志
            if (info.body.isNotBlank()) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    tint = Color.White.copy(alpha = 0.30f)
                ) {
                    Text(
                        text = info.body.trim(),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            // 下载源选择：官方网站 / 镜像源
            val downloading = state is UpdateDownloader.State.Downloading
            Text(
                "下载源",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceOption(
                    icon = Icons.Filled.Public,
                    label = "官方网站",
                    desc = "GitHub 直连",
                    selected = selectedSource == UpdateDownloader.Source.OFFICIAL,
                    enabled = !downloading,
                    onClick = { selectedSource = UpdateDownloader.Source.OFFICIAL },
                    modifier = Modifier.weight(1f)
                )
                SourceOption(
                    icon = Icons.Filled.Speed,
                    label = "镜像源",
                    desc = "国内 CDN 加速",
                    selected = selectedSource == UpdateDownloader.Source.CDN,
                    enabled = !downloading,
                    onClick = { selectedSource = UpdateDownloader.Source.CDN },
                    modifier = Modifier.weight(1f)
                )
            }
            // 下载进度 / 按钮
            when (val s = state) {
                is UpdateDownloader.State.Idle -> {
                    DownloadButton(
                        label = "下载并安装",
                        enabled = true,
                        onClick = {
                            val url = info.apkDownloadUrl ?: return@DownloadButton
                            downloader.start(url, info.apkFileName, selectedSource)
                        }
                    )
                }
                is UpdateDownloader.State.Downloading -> {
                    ProgressSection(
                        progress = s.progress,
                        received = s.receivedBytes,
                        total = s.totalBytes,
                        onCancel = { downloader.cancel() }
                    )
                }
                is UpdateDownloader.State.Done -> {
                    DownloadButton(
                        label = "立即安装",
                        enabled = true,
                        primary = true,
                        onClick = { downloader.install(s.file) }
                    )
                    Text(
                        "下载完成，点击「立即安装」唤起系统安装器",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
                is UpdateDownloader.State.Failed -> {
                    Text(
                        text = s.message,
                        fontSize = 12.sp,
                        color = Color(0xFFFF5252)
                    )
                    DownloadButton(
                        label = "重新下载",
                        enabled = true,
                        onClick = {
                            val url = info.apkDownloadUrl ?: return@DownloadButton
                            downloader.start(url, info.apkFileName, selectedSource)
                        }
                    )
                }
            }
        }
    }
}

/** 下载源选项卡片。 */
@Composable
private fun SourceOption(
    icon: ImageVector,
    label: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) MiuixTheme.colorScheme.primary else Color.Transparent
    val bgTint = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
    else Color.White.copy(alpha = 0.28f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgTint)
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else Color.White.copy(alpha = 0.30f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    desc,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
            }
        }
    }
}

/** 主操作按钮。 */
@Composable
private fun DownloadButton(
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (primary) Brush.horizontalGradient(
        listOf(MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.tertiaryContainer)
    ) else Brush.horizontalGradient(
        listOf(MiuixTheme.colorScheme.primary.copy(alpha = 0.85f), MiuixTheme.colorScheme.primary.copy(alpha = 0.70f))
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) bg else Brush.horizontalGradient(listOf(Color.Gray.copy(alpha = 0.4f), Color.Gray.copy(alpha = 0.3f))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 下载进度区。 */
@Composable
private fun ProgressSection(
    progress: Float,
    received: Long,
    total: Long,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (total > 0) "下载中 ${(progress * 100).toInt()}% · ${UpdateDownloader.formatSize(received)}/${UpdateDownloader.formatSize(total)}"
                else "下载中 ${UpdateDownloader.formatSize(received)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "取消",
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color = MiuixTheme.colorScheme.primary,
            trackColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
        )
    }
}
