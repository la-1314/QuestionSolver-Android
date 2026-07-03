# 智能搜题 (QuestionSolver)

拍照搜题 Android APP —— 拍摄题目 → 百度图像增强 → 版面切分 → 大模型解题（原题/标准答案/分步解析）。

## 功能

1. **拍照 + 本地压缩**：拍摄原图后本地轻量化压缩（MAX_EDGE=1600，JPEG 质量 85）。
2. **百度图像增强**：默认调用百度智能云「图像清晰度增强」API 做全局预处理；增强失败可降级使用原图。
3. **版面切分**：增强后的整图调用百度「办公文档版面分析」API（`doc_analysis_office`，`layout_analysis=true`）自动生成题目切分框。
4. **原图/增强图切换**：本地保留仅压缩未增强的原图，用户可在切分页切换使用原图或增强图进行切分。
5. **手动框选**：自动生成的裁剪框支持拖动、缩放（8 个手柄）、新建、删除；**手动框选的题目不再做百度增强**。
6. **二次增强**：自动切分、未手动修改框选的单题图片，依旧执行百度图像增强二次预处理。
7. **大模型分支**：
   - 模型支持图像输入：处理后的单题图片 + 固定提示词 → `域名/v1/chat/completions`
   - 模型仅支持纯文字：先调百度 OCR（`accurate_basic`）识别文字 → 文本 + 提示词 → 同一接口
8. **答案展示**：原题 / 标准答案 / 分步解析三段式布局，支持上一题/下一题导航与失败重试。
9. **Material Design 3** 主题。

## 技术栈

- Kotlin · compileSdk 34 · minSdk 24 · Java 17
- Material Design 3 (material:1.12.0)
- CameraX 1.3.4（拍照）
- OkHttp 4.12 + kotlinx.serialization 1.6.3
- Coroutines 1.8.1
- Coil 2.6（图片加载）
- ViewBinding
- Gradle 8.7 wrapper + AGP 8.5.2 + Kotlin 1.9.24

## 项目结构

```
app/src/main/java/com/questionsolver/app/
├── App.kt                      # Application
├── data/
│   ├── AppConfig.kt            # 配置持久化 (SharedPreferences)
│   └── Models.kt               # 数据模型 (Serializable)
├── net/
│   ├── BaiduApiClient.kt       # 百度 OAuth + 增强 + 版面切分 + OCR
│   ├── LlmApiClient.kt         # 大模型 /v1/chat/completions (多模态/纯文本)
│   └── SolveEngine.kt          # 分支逻辑编排
├── ui/
│   ├── MainActivity.kt         # 主入口
│   ├── ConfigActivity.kt       # 服务配置页
│   ├── CameraActivity.kt       # 拍照页
│   ├── SegmentationActivity.kt # 版面切分 + 手动框选页
│   ├── AnswerActivity.kt       # 答案展示页
│   └── SessionData.kt          # 进程级会话数据
├── util/
│   ├── ImageUtils.kt           # 压缩/裁剪/base64
│   └── AnswerParser.kt         # 答案 JSON 解析
└── view/
    └── CropBoxOverlayView.kt   # 自定义裁剪框视图 (拖动/缩放/新建/删除)
```

## 配置

首次启动进入「服务配置」页填写：

**百度智能云**（鉴权需 API Key + Secret Key 同时使用，百度 OAuth 硬性要求）
- API Key
- Secret Key

**大语言模型服务**
- 服务域名（例：`https://api.example.com`，不带末尾斜杠）
- API 密钥
- 模型名称（例：`gpt-4o`）
- 模型支持图像输入（开关）

固定对话接口路径：`/v1/chat/completions`

## 百度 API（已通过官方文档核实，非杜撰）

| 能力 | 接口 | 地址 |
|------|------|------|
| OAuth 鉴权 | token | `https://aip.baidubce.com/oauth/2.0/token` |
| 图像清晰度增强 | image_definition_enhance | `https://aip.baidubce.com/rest/2.0/image-process/v1/image_definition_enhance` |
| 办公文档版面分析 | doc_analysis_office | `https://aip.baidubce.com/rest/2.0/ocr/v1/doc_analysis_office` |
| 通用文字识别高精度 | accurate_basic | `https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic` |

## 构建

```bash
./gradlew assembleDebug
```

> 注：仓库未提交 `gradle/wrapper/gradle-wrapper.jar`（二进制文件），首次构建时 `gradlew` 会自动下载对应版本的 Gradle；也可手动执行 `gradle wrapper --gradle-version 8.7` 生成。

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`

## 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

要求 Android 7.0 (API 24) 及以上。

## License

MIT
