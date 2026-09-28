王的解析 V8.0 STABLE FREE

这是按“方案 A”重构的稳定免费版，不再继续修补抖音/小红书内部解析接口。

核心边界
========

1. 抖音 / 小红书
   - 不再伪装成稳定直链解析器。
   - 不再展示未经真实性验证的 CDN 候选地址。
   - 不再出现“解析成功但 0:00 / 白屏”的产品逻辑。
   - 提供：
       仅打开官方页面
       系统授权录屏并打开官方页面
       停止录屏并保存

2. 系统录屏
   - 使用 Android MediaProjection。
   - 每次必须由用户点击系统授权弹窗。
   - 录屏期间有前台服务。
   - 可从通知“停止并保存”，也可回 APP 点停止。
   - 保存到：
       Movies/WangParser/ScreenRecords
   - Android 10+ 使用 MediaStore，保存后进入相册。
   - 如果用户允许麦克风权限，会录环境/扬声器声音。
   - 这不是平台内部音频抓取。
   - 如果源 App 禁止系统录屏，可能出现黑屏；本工具不会绕过。

3. B站 / 快手 / 其他可解析平台
   - 继续使用云端辅助。
   - 不再直接预览候选视频。
   - 点击下载时必须先通过 APP 的视频真实性校验。
   - 校验失败则不保存，避免 0 秒文件。

4. 公开媒体直链
   - mp4/webm/mov/mkv/mp3/m4a/aac/wav/ogg
   - 直接识别。
   - 视频下载前仍会做真实性校验。

5. 下载
   - 实时百分比
   - 已下载 / 总大小
   - MB/s
   - 视频保存 Movies/WangParser
   - 音频保存 Music/WangParser

6. 构建
   - 已移除 nativecore / Go / gomobile。
   - GitHub Actions 只编译 Android APK。
   - 结构更简单，构建更稳定。

GitHub Actions 名称：
Build WangParser APK V8 Stable Free

Artifact：
WangParser-APK-V800-STABLE-FREE

Release：
WangParser-V8.apk

重要说明
========
本版的目标不是“任何链接都声称解析成功”，而是：
- 能验证的才下载；
- 受限平台走官方页面/系统能力；
- 不制造假成功结果。
