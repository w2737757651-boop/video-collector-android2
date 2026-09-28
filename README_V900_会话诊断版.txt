王的解析 V9.0 本地会话诊断版

这不是正式成品，目的只有一个：
验证“手机本地登录会话 + 页面自身数据请求”能否在不播放视频的情况下拿到真实媒体。

使用顺序
========

1. 安装 APK。
2. 第一次测试抖音：
   - 点“登录抖音会话”
   - 直接在真实抖音网页中手动登录
   - 登录完成后点“完成登录 / 返回”
3. 第一次测试小红书同理。
4. 粘贴分享链接。
5. 点“开始诊断（不播放）”。
6. APP 会：
   - 在 document-start 注入监听；
   - 监听页面自己的 fetch；
   - 监听页面自己的 XHR；
   - 扫描 __INITIAL_STATE__ / _ROUTER_DATA；
   - 扫描页面资源候选；
   - 不自动播放 video/audio；
   - 对候选 URL 发 Range 请求；
   - 检查 HTTP 状态、Content-Type、MP4/WebM 文件头。
7. 如果出现：
   “✅ 捕获并验证到真实视频。无需播放。”
   说明底层第一阶段跑通。
8. 再点“下载验证通过的视频”。
9. 下载完成后 APP 会用 MediaMetadataRetriever 检查本地视频时长。
10. 只有本地时长 > 0 才算第二阶段真正跑通。

成功门槛
========

平台至少一条真实样本满足：

A. 不播放；
B. 20 秒内捕获 verified video；
C. 下载完成；
D. 本地时长 > 0；
E. 视频内容对应分享作品。

只有满足 A-E，才进入正式 APP 方案。

隐私 / 边界
===========

- 登录过程发生在平台自己的网页中。
- APP 不读取、不记录账号、密码、验证码。
- 诊断日志不会输出 Cookie。
- Cookie 仅由 Android WebView/CookieManager 在本机保存并用于对应会话。
- 不绕过登录、验证码、DRM 或访问控制。
- 只处理公开可访问且你有权保存的内容。

Actions:
Build WangParser APK V9 Session Diagnostic

Artifact:
WangParser-APK-V900-SESSION-DIAG

Release:
WangParser-V9-Diag.apk
