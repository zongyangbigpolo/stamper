# Stamper（现场语音相机）

面向施工现场、弱电安装、设备巡检等高频拍摄场景的 Android 应用：拍照后直接通过**语音录入**画面说明（例如 `302教室东南角投影仪`），自动将备注合成到照片中并按项目归档，方便导出到电脑后由 AI Agent 自动分类插图入文档。

## 核心特性

1. **三种采集方式**
   - **内置快速连拍（CameraX）**：拍完即弹语音录入条，点击“保存并继续拍”无缝连拍下一张。
   - **后台监听手机原生相机**：开启监听后，使用手机系统相机拍照会立即在屏幕上方浮出语音录入卡片（未开悬浮窗权限时自动降级为高优通知）。
   - **相册选图/分享补录**：支持从系统相册多选已有照片，或从系统图库直接“分享”到本应用逐张补录。
   - **内置离线中文语音识别**：采用阿里 FunASR **SenseVoiceSmall**（int8，经 sherpa-onnx 运行），无需联网，也不依赖手机厂商/Google 语音服务；数字自动规整（“302教室”而非“三零二教室”）。仅当模型未打包时才回退到系统语音识别。
2. **干净的边角水印**
   - 画面边角水印**仅印制语音录入内容**（不印日期、不印项目名），四角位置与字号比例可调，支持独立开关。
3. **对电脑端 AI 零乱码的命名与元数据规范**
   - **文件命名**：`<语音录入内容>_<yyyyMMdd>.jpg`（例如 `302教室东南角投影仪_20261125.jpg`，重名自动追加 `_2`）。
   - **EXIF `UserComment`**：写入 7-bit ASCII 安全的 RFC 8259 JSON（`stamper.v1`），跨平台 `json.loads` / `JSON.parse` 100% 零乱码还原中文。
   - **XMP (`dc:description` / `photoshop:Headline`)**：写入标准 UTF-8 XML，Windows 属性、macOS 预览、`exiftool` 直接显示中文。
   - **项目导出**：直接存于手机公共目录 `Pictures/Stamper/<项目名>/`，支持 USB 数据线直拷或一键连同 `manifest.json` 打包为 `.zip` 分享。

详细需求与元数据字段契约参见 [`docs/SPEC.md`](docs/SPEC.md)。

## 本地构建与测试

需配置 Android SDK（`compileSdk 36`）及 JDK 17/21（如 Android Studio 内置 JBR）：

提供两个版本（product flavor）：

| 版本 | 安装包 | 语音识别 | 体积 |
| --- | --- | --- | --- |
| `full` | `app/build/outputs/apk/full/debug/stamper-full.apk` | 内置阿里 SenseVoice 离线中文识别，国产手机推荐 | 约 300MB |
| `lite` | `app/build/outputs/apk/lite/debug/stamper-lite.apk` | 手机系统语音识别 / 输入法语音 | 约 21MB |

```bash
# full 版首次构建前下载离线语音识别运行库与模型（已在 .gitignore 中忽略）
./scripts/fetch_asr_model.sh

# 单元测试 + 构建两个版本
./gradlew testFullDebugUnitTest testLiteDebugUnitTest assembleFullDebug assembleLiteDebug

# 在已连接设备/模拟器上验证离线识别模型
./gradlew connectedFullDebugAndroidTest
```

每次发布请同步提升 `app/build.gradle.kts` 中的 `versionCode` / `versionName`，首页标题下会显示当前版本号（lite 版带 `-lite` 后缀）。

### GitHub Actions 自动构建

`.github/workflows/android.yml` 在推送 `main`、PR 或手动触发时并行构建 full / lite 两个 APK 并上传为 Artifacts；推送 `v*` 标签（如 `v1.4.0`）时自动创建 GitHub Release 并附带两个 APK。

为保证 CI 产物能覆盖安装旧版本，需在仓库 Secrets 中配置统一签名：`STAMPER_KEYSTORE_BASE64`（keystore 的 base64）以及可选的 `STAMPER_KEYSTORE_PASSWORD` / `STAMPER_KEY_ALIAS` / `STAMPER_KEY_PASSWORD`（缺省为 Android debug 默认值）。
