Video Collector Android V7.0.1 FREE LOCAL

这是 V7.0 的构建修复完整工程。

V7.0 的失败点：
GitHub Actions 已经完成 gomobile AAR 生成，
但 Gradle 在 Build APK 阶段的 ExtractAarTransform 失败。

V7.0.1 修复：
1. gomobile 仍生成 nativecore.aar。
2. GitHub Actions 在 Gradle 构建前主动解压 AAR。
3. classes.jar -> app/libs/nativecore-classes.jar
4. jni/* -> app/src/main/jniLibs/*
5. 删除 nativecore.aar，不再让 Android Gradle 执行 ExtractAarTransform。
6. app/build.gradle 只依赖 nativecore-classes.jar。
7. JNI .so 通过 Android 标准 jniLibs 目录打包。

解析逻辑没有回退：
- 抖音：手机本地 Go 引擎
- 小红书：手机本地 Go 引擎
- 不需要 TikHub
- 不需要电脑常开
- 不需要付费 API

使用：
1. 解压此 ZIP。
2. 全部覆盖上传到 video-collector-android2。
3. Commit changes。
4. Actions -> Build Android APK V7.0.1 Free Local。
5. 成功后下载 Artifact：
   VideoCollector-APK-V701-FREE-LOCAL
6. 解压并安装 app-debug.apk。
