王的解析 Android V7.0.3 FREE LOCAL

本版同时完成两件事：

一、修复当前 GitHub Actions 明确报错
报错：
gomobile bind requires golang.org/x/mobile in the current module
gomobile: missing golang.org/x/mobile dependency

修复：
在 nativecore 当前 Go module 内执行：
go get -tool golang.org/x/mobile/cmd/gobind@latest
go mod tidy
go test ./...
然后再执行 gomobile bind。

这和之前“只在系统里 go install gobind”不同：
gomobile 现在要求 x/mobile/gobind 同时进入当前 module dependency graph。

二、APP 正式更名
手机桌面名称：王的解析
APP 首页标题：王的解析
applicationId 继续保持：
com.videocollector.app
避免因为改包名引入新的安装/构建问题。

构建成功后的 Artifact：
WangParser-APK-V703-FREE-LOCAL

使用：
1. 解压完整工程。
2. 全部覆盖 video-collector-android2 仓库。
3. Commit changes。
4. Actions 中应看到：
   Build WangParser APK V7.0.3 Free Local
5. 等运行变绿。
6. 下载 WangParser-APK-V703-FREE-LOCAL。
7. 解压安装 app-debug.apk。

注意：
当前截图已经证明失败点不是抖音/小红书逻辑，而是 gomobile 工具依赖未进入 nativecore/go.mod 的依赖图。
V7.0.3 专门修复这一点，并保留 V7.0.2 的 JavaResource/JNI 打包修复。
