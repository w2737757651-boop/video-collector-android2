王的解析 Android V7.0.4 FREE LOCAL

本版只修复 V7.0.3 已确认的 JAR 清洗问题。

V7.0.3 已经成功：
- Set up Go 1.26.6
- Install gomobile toolchain
- Prepare Go module for gomobile
- Build Go Android binding

唯一失败点：
Extract clean Go classes and JNI libraries

报错：
ERROR: Clean JAR still contains non-class resources:
META-INF/MANIFEST.MF

根因：
`jar cf` 会自动创建 META-INF/MANIFEST.MF，
即使我们前面已经删除了所有非 class 文件。

V7.0.4 修复：
把：
jar cf ...

改为：
jar cMf ...

其中 M 表示“不创建 Manifest”。

因此最终 nativecore-classes.jar 只包含 .class 文件，
不会再因为 META-INF/MANIFEST.MF 被校验阻止。

APP 名称继续保持：
王的解析

applicationId 继续保持：
com.videocollector.app

构建成功后 Artifact：
WangParser-APK-V704-FREE-LOCAL
