Video Collector Android V7.0.2 FREE LOCAL

本版只修复 Android 打包问题，不修改抖音/小红书解析逻辑。

已确认 V7.0.1 的真实失败任务：
:app:mergeDebugJavaResource

V7.0.2 修复方式：
1. gomobile 正常生成 nativecore.aar。
2. Actions 主动解压 AAR。
3. 从 classes.jar 中删除所有非 .class 文件。
4. 生成纯 Java bytecode JAR：
   app/libs/nativecore-classes.jar
5. JNI .so 继续放入：
   app/src/main/jniLibs/
6. Gradle packaging.resources 排除 META-INF/**。
7. 构建前执行 :app:clean，避免旧缓存资源污染。

这样不再让 gomobile JAR 中的 META-INF/LICENSE/NOTICE/MANIFEST 等资源
参与 :app:mergeDebugJavaResource。

使用：
- 整包覆盖 video-collector-android2
- Commit
- Actions -> Build Android APK V7.0.2 Free Local
- 成功后下载：
  VideoCollector-APK-V702-FREE-LOCAL
