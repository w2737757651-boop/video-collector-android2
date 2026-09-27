Video Collector V5.2

小红书专项修复：
1. 不再广泛抓取所有疑似视频 URL 作为首选。
2. 优先读取：
   window.__INITIAL_STATE__
   -> note.noteDetailMap
   -> note.video.media.stream.h264
   -> masterUrl
3. 优先 H.264，避免 H.265 兼容性问题。
4. masterUrl 下载前先做 Range 请求校验：
   - HTTP 200/206
   - Content-Type 为 video/* 或 octet-stream
   - 或文件头包含 MP4 ftyp
5. 校验失败直接阻止下载，避免生成“下载成功但 0 秒白屏”的假 MP4。
6. 不再把 m3u8 清单当成普通 MP4 下载。
7. 抖音 V5.1 本地直解析保留。
8. 视频继续保存 Movies/VideoCollector 并媒体入库。

覆盖 video-collector-android2 后重新构建 APK。
Artifact: VideoCollector-APK-V52
