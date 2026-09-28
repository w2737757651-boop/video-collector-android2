王的解析 V7.2 FREE HYBRID

本版针对 V7.1 实机结果：
- 抖音：DOUYIN_WALLED
- 小红书：XHS_ACCESS / -101 needs logged-in cookie

核心调整：

1. 小红书不再直接依赖 signed JSON API
   - 展开分享短链
   - 读取公开网页 HTML
   - 优先从 SSR / __INITIAL_STATE__ 中寻找 masterUrl / backupUrls / xhscdn 视频
   - 只有 SSR 没拿到视频时才进入原库 fallback

2. 小红书网页读取不再主动加站内 Referer
   当前上游 release notes 明确指出某些 web reads 带站内 Referer 会被重定向到登录页。

3. 小红书出现以下错误时也提供网页会话兜底：
   XHS_ACCESS
   XHS_ANTIBOT
   XHS_RATE
   XHS_ID
   XHS_TOKEN

4. 网页会话现在同时支持：
   - Douyin
   - Xiaohongshu

   会从：
   - video/currentSrc
   - source
   - performance resource entries
   - window.__INITIAL_STATE__
   - xhscdn / sns-video / douyinvod 等网络地址
   中寻找可直接访问媒体。

5. 不绕过登录/验证码/DRM
   如果真实网页明确要求登录、验证码，或只提供 blob/MSE 分段流，
   APP 会明确停止，不会伪造成功，也不会保留 0 秒白屏文件。

6. 下载
   - 当前 WebView Cookie / Referer 只在手机本地用于媒体下载
   - 不上传到 Render 或其他第三方
   - 下载后继续做视频时长校验

Artifact:
WangParser-APK-V720-FREE-HYBRID

Release:
WangParser-V7.2.apk
