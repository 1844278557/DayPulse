# DayPulse

Android 原生的个人智能闹钟 + 每日打卡 App。

主要功能：每日习惯目标、本地打卡、重复/循环/工作日闹钟、统计、Android 系统语音识别，以及通过 SiliconFlow / DeepSeek 的自然语言闹钟创建与删除。AI 创建结果会先进入可编辑表单，删除也必须人工确认。

## Build

Pushes to `main` automatically build a debug APK with GitHub Actions. The APK is uploaded as the `DayPulse-debug-apk` workflow artifact.

当前独立仓库构建环境：JDK 17、Gradle 8.13、Android API 36。

CI verification: v2 after model fix.
