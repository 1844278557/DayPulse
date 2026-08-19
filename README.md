# DayPulse

Android 原生的个人智能闹钟 + 每日打卡 App。

主要功能：本地打卡、重复闹钟、循环提醒、工作日规则、统计、Android 系统语音输入，以及通过 SiliconFlow / DeepSeek 的自然语言闹钟解析。API Key 不写入源码，安装后在 App 内设置。

## Build

Pushes to `main` automatically build a debug APK with GitHub Actions. The APK is uploaded as the `DayPulse-debug-apk` workflow artifact.

当前独立仓库构建环境：JDK 17、Gradle 8.13、Android API 36。

CI verification branch only.
