Video Collector Android V6.0 浏览器会话解析版

核心架构：
1. 抖音 / 小红书不再后台猜媒体 URL。
2. APP 内打开真实平台网页。
3. 保留当前 WebView 的 Cookie / Referer / 页面上下文。
4. 用户可正常播放视频。
5. 点击“提取当前媒体”后：
   - 读取 <video>/<audio> currentSrc/src
   - 读取 performance 网络资源
   - 读取当前页面 __INITIAL_STATE__ / _ROUTER_DATA 中媒体类字段
   - 合并实际网络捕获候选
6. 所有候选媒体都必须经过原生 Range 请求校验：
   - HTTP 200/206
   - 排除 HTML/错误页
   - 视频需 video/* 或 MP4 ftyp
   - 音频需 audio/* / ID3 / 可识别 MP4 音频
7. 校验失败不允许下载，避免 0 秒白屏。
8. Cookie 只留在 Android WebView，不上传 Render。

下载：
- 手机直连媒体源
- 实时百分比
- 已下载 / 总大小
- MB/s
- 视频保存 Movies/VideoCollector
- 自动 MediaScanner 入相册
- 音频保存 Music/VideoCollector

使用：
1. 整包覆盖 video-collector-android2
2. Commit changes
3. Actions -> Build Android APK
4. 下载 VideoCollector-APK-V60
5. 安装 app-debug.apk

实际操作：
抖音/小红书：
复制分享链接
-> 打开并解析
-> APP 内进入平台真实网页
-> 如果视频未播放，手动点播放
-> 点顶部“提取当前媒体”
-> 返回 Video Collector 结果页
-> 下载

安全边界：
- 不上传账号密码
- 不向 Render 上传浏览器 Cookie
- 不绕过登录、验证码、DRM 或访问控制
