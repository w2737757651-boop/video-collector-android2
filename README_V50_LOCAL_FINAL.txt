Video Collector V5.0 Local Parser

架构改变：
- 抖音：Android 手机本地 WebView 打开公开分享页并捕获播放器/页面状态/网络媒体地址
- 小红书：Android 手机本地 WebView 打开公开分享页并读取公开页面数据/实际媒体请求
- B站/快手等：继续使用 Render 云端辅助
- 下载：手机直接下载媒体源
- 视频保存 Movies/VideoCollector，并通知系统媒体库
- 音频保存 Music/VideoCollector
- 保留清空、粘贴、实时下载进度与 MB/s

安全边界：
- 不上传账号密码
- 不主动导出 WebView Cookie 到 Render
- 不绕过登录、验证码、DRM 或访问控制
- 如果公开网页不暴露媒体资源，会明确失败

使用：
1. 整包覆盖 video-collector-android2 仓库
2. Commit
3. Actions -> Build Android APK
4. 下载 Artifact: VideoCollector-APK-V50
5. 安装 app-debug.apk
