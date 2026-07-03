package com.questionsolver.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.questionsolver.app.R
import com.questionsolver.app.data.AppConfig
import com.questionsolver.app.databinding.ActivityConfigBinding
import com.google.android.material.snackbar.Snackbar

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val cfg = AppConfig.load(this)
        binding.etBaiduApiKey.setText(cfg.baiduApiKey)
        binding.etBaiduSecretKey.setText(cfg.baiduSecretKey)
        binding.etLlmDomain.setText(cfg.llmDomain)
        binding.etLlmKey.setText(cfg.llmApiKey)
        binding.etLlmModel.setText(cfg.llmModel)
        binding.swImageSupport.isChecked = cfg.llmSupportsImage
        updateImageDesc(cfg.llmSupportsImage)

        binding.swImageSupport.setOnCheckedChangeListener { _, checked ->
            updateImageDesc(checked)
        }

        // 引导：打开百度智能云控制台
        binding.btnOpenConsole.setOnClickListener {
            openUrl(getString(R.string.url_baidu_console))
        }
        // 引导：打开图像增强 API 文档
        binding.btnOpenDoc.setOnClickListener {
            openUrl(getString(R.string.url_baidu_doc_image_enhance))
        }

        binding.btnSave.setOnClickListener {
            val newCfg = AppConfig(
                baiduApiKey = binding.etBaiduApiKey.text.toString().trim(),
                baiduSecretKey = binding.etBaiduSecretKey.text.toString().trim(),
                llmDomain = binding.etLlmDomain.text.toString().trim(),
                llmApiKey = binding.etLlmKey.text.toString().trim(),
                llmModel = binding.etLlmModel.text.toString().trim(),
                llmSupportsImage = binding.swImageSupport.isChecked
            )
            AppConfig.save(this, newCfg)
            Snackbar.make(binding.root, R.string.config_saved, Snackbar.LENGTH_SHORT).show()
            // 稍作停留后返回
            binding.root.postDelayed({ finish() }, 600)
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.onFailure {
            Snackbar.make(binding.root, "无法打开链接：$url", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun updateImageDesc(supportsImage: Boolean) {
        binding.tvImageSupportDesc.text = getString(
            if (supportsImage) R.string.config_llm_image_support_on
            else R.string.config_llm_image_support_off
        )
    }
}
