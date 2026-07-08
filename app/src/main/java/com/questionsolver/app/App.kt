package com.questionsolver.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 启用 Material You 动态取色（Android 12+ 自动从壁纸提取色板，
        // 应用于 XML 层：启动屏、状态栏、window background。
        // Compose 层由 MiuixTheme 的 ColorSchemeMode.MonetSystem 单独处理。
        // 低于 Android 12 时自动回退到 Material3 baseline 配色。）
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
