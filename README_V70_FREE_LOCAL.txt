Video Collector Android V7.0 FREE LOCAL

这是免费本地解析版完整工程，不是补丁。

架构：
- 抖音：手机本地 Go 引擎
- 小红书：手机本地 Go 引擎
- B站/快手：保留云端辅助
- 下载：手机直连媒体源
- 电脑关机不影响
- 不需要 TikHub
- 不需要付费 API Key
- Render 不参与抖音/小红书解析

Go 引擎：
1. github.com/tamnd/douyin-cli v0.1.1
   - 本地 a_bogus / msToken 签名
   - Signed Douyin Web API client
2. github.com/tamnd/xiaohongshu-cli v0.2.0
   - 匿名 web session
   - xsec_token note parser
   - 视频 Masters 可播放流

GitHub Actions 构建流程：
- 安装 Go 1.26.6
- 安装 gomobile
- 把 nativecore 编译成 Android AAR
- 自动检测 gomobile 生成的 Java 类
- 再用 Gradle 打包 APK

使用：
1. 解压此 ZIP
2. 全部覆盖上传到 GitHub 仓库 video-collector-android2
3. Commit changes
4. 打开 Actions
5. 等 Build Android APK V7 Free Local 变绿
6. 打开最新构建
7. Artifacts 下载：
   VideoCollector-APK-V70-FREE-LOCAL
8. 解压后安装 app-debug.apk

说明：
- 第一次 GitHub 构建会比以前慢，因为要下载 Go 依赖并编译两套 Android native library。
- APK 会明显比以前大，这是正常的，因为真正的 Go 解析引擎已打进 APK。
- 完全免费不等于永远不受平台风控。
- 抖音官方网页会根据 IP/session 做反爬；若返回 DOUYIN_WALLED，可切换手机流量/Wi‑Fi后重试。
- 小红书请使用“分享 -> 复制链接”的最新完整链接，必须保留 xsec_token。
