package com.questionsolver.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTakePhoto.setOnClickListener {
            val cfg = AppConfig.load(this)
            if (!cfg.isBlmReadyAndWarn()) return@setOnClickListener
            startActivity(Intent(this, CameraActivity::class.java))
        }
        binding.btnConfig.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        updateTip()
    }

    override fun onResume() {
        super.onResume()
        updateTip()
    }

    private fun updateTip() {
        val cfg = AppConfig.load(this)
        val ready = cfg.isBaiduReady() && cfg.isLlmReady()
        binding.tvTip.text = if (ready) {
            "配置就绪，点击「拍摄题目」开始"
        } else {
            getString(com.questionsolver.app.R.string.main_tip)
        }
    }

    private fun AppConfig.isBlmReadyAndWarn(): Boolean {
        if (!isBaiduReady() || !isLlmReady()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("配置未完成")
                .setMessage("请先在「服务配置」中填写百度 API Key/Secret Key 与大模型域名/密钥/模型名。")
                .setPositiveButton(com.questionsolver.app.R.string.main_config) { _, _ ->
                    startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                }
                .setNegativeButton(com.questionsolver.app.R.string.cancel, null)
                .show()
            return false
        }
        return true
    }
}
