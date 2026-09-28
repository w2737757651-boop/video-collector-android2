王的解析 V7.1 FREE HYBRID

本版针对实机结果做结构重构：

一、小红书 XHS_ID 修复
旧版仅识别：
/explore/<id>
/discovery/item/<id>

V7.1 会同时从以下位置寻找 Note ID / xsec_token：
- 原始分享短链
- 最终重定向 URL
- URL 编码后的跳转参数
- 页面 HTML / SSR JSON
- noteId / note_id 字段
- canonical/explore/discovery URL

二、抖音 DOUYIN_WALLED
这不是付费限制，也不是简单签名错误。
当前开源 Go 引擎官方说明 video API 可能被 IP/session 层 anti-bot 拦截。

V7.1 流程：
Go 本地解析
-> 若成功：直接显示视频/音频
-> 若 DOUYIN_WALLED：显示“使用网页会话继续解析（免费）”
-> APP 内打开真实抖音分享页
-> 用户手动让视频开始播放
-> 点顶部“提取当前媒体”
-> APP 捕获当前会话产生的 HTTP 媒体地址
-> 下载时携带当前 WebView Cookie / Referer
-> 下载完成后再校验真实视频时长
-> 0 秒/无效文件自动删除

说明：
如果页面实际采用 blob/MSE 分段流且没有暴露完整 HTTP 视频地址，
免费本地模式仍可能无法直接导出原文件。APP 会明确提示，不再生成假视频。

三、下载
- 实时百分比
- 已下载/总大小
- MB/s
- 视频 Movies/WangParser
- 音频 Music/WangParser
- 网页会话下载后做时长校验

四、GitHub
Artifact：
WangParser-APK-V710-FREE-HYBRID

另外尝试自动发布到 Releases：
WangParser-V7.1.apk
这样以后可以直接下载 APK，不需要先下载 Actions ZIP。
