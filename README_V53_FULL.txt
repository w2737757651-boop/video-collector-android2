Video Collector Android V5.3 FULL

这是整合完整版，不是增量补丁。

已整合功能：

【抖音】
- 手机本地解析分享短链
- 提取 aweme_id
- 请求 iesdouyin.com/share/video/{aweme_id}/
- 读取 window._ROUTER_DATA
- 读取 video.play_addr / playAddr / download_addr
- 读取 music.play_url / playUrl
- 同时显示视频与原声音频
- WebView 仅作为兜底

【小红书】
- 手机本地打开最新官方分享链接
- 读取 window.__INITIAL_STATE__
- note.noteDetailMap
- note.video.media.stream.h264
- 优先 masterUrl
- 优先 H.264，避免 H.265 兼容性问题
- 下载前校验 HTTP 200/206 + Content-Type / MP4 ftyp
- 无效视频地址直接阻止下载
- 避免生成 0 秒白屏 MP4
- 不把 m3u8 清单当普通 MP4 下载

【下载】
- 手机直连媒体源下载
- 实时百分比
- 已下载 / 总大小
- 实时 MB/s
- 视频保存 Movies/VideoCollector
- 下载完成后通知系统媒体库，便于相册显示
- 音频保存 Music/VideoCollector

【界面】
- 清空
- 粘贴最新链接
- 开始解析

【其他平台】
- B站 / 快手继续使用云端辅助
- 视频号仍受公开网页限制

使用方法：
1. 解压本 ZIP
2. 全部覆盖上传到 GitHub 仓库 video-collector-android2
3. Commit changes
4. Actions -> Build Android APK
5. 下载 Artifact: VideoCollector-APK-V53
6. 解压得到 app-debug.apk 并安装

注意：
- 这是完整工程，直接整包覆盖，不需要再叠加 V5.1/V5.2。
- 不上传账号密码。
- 不绕过登录、验证码、DRM 或访问控制。
