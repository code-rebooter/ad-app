# 系统属性日志统一检查（2026-09-23）

## 规则及实现范围

本地诊断输出只允许在 Android 系统属性 `persist.sys.ad.log=true` 时开启，Debug/Release 一致。大小写不敏感，去除两侧空白。未设置、false、其他值、读取失败均关闭；BuildConfig.DEBUG、SDK 配置、Java 系统属性均不能绕过。

公共实现位于 `logging/PropertyLog.java.template`，构建时生成到各模块自己的 namespace 下，避免独立 AAR 引入重复类或额外运行依赖。每次输出读取属性；标准输出包装流即使被第三方缓存，也在实际写入时检查属性。R8 保留公共出口便于最终产物核查。

覆盖九个 Android 模块的源码及所有 App flavor 构建配置。Android Gradle Plugin 对应用及其依赖的 Java/Kotlin 类进行处理，SDK 对自身代码进行处理；Fusion 内嵌的四个 TCL JAR 单独处理后再封入 AAR。启用 JDK 核心库兼容功能的 GAM 示例先通过 `propertyLoggedDesugar` 配置处理兼容库，再交给 L8，覆盖构建工具后加的 CharsetMapping 异常堆栈打印。

控制出口包括 Android Log 各级别及 println/wtf、System.out/err、无参数 printStackTrace、Java Logger 输出。显式写入 Writer 的堆栈序列化保留，避免影响业务内容；压力测试 CSV/线程诊断文件单独加属性判断。可控 WebView console 回调在统一输出后消费消息。

各源码日志包装器、渠道 SDK 的日志初始化开关、原本写死 true 的 TCL 调试开关均接入系统属性。SDK 公开 setDebugLogging API 为兼容保留，但不能开启本地输出。部分第三方 SDK 缓存初始化时的日志配置，从关闭切换为开启后可能需要重新初始化；最终输出关闭仍由实时出口检查保证。

后台广告事件、CMP、popup_log_enabled 上传与本地日志开关独立，本次不修改业务上报。

## 回归验证

- 先验证新增测试失败，再实现属性门控与字节码处理。
- 公共实现运行测试验证属性未设置、false、非法值、true、动态切换及读取异常；覆盖日志等级、异常参数、println/isLoggable、被缓存的标准输出流。
- 字节码运行测试在 JVM 真正加载并执行转换后的类，验证异常子类、Java Logger、分支、long 局部变量和显式 Writer 序列化。
- buildSrc 全部 14 项测试通过；项目源码旁路检查 4 项通过。
- 第一轮构建通过：JM Debug/Release、六个 SDK 的 Debug/Release、两个示例应用 Debug，共 482 个 Gradle 任务。
- JM Debug DEX 扫描 19,771 个类、JM Release 扫描 8,270 个类；六个 SDK Release AAR（包含所有内嵌 JAR）均通过出口扫描。
- 最终 JM Release 和 SDK Release 构建通过，HQ008 调试入口 Java/Kotlin 编译通过。
- GAM 示例在兼容库处理修正后重新构建并扫描：9,868 个类，未发现本脚本检查的旁路；Flow 示例 13,109 个类同样通过。
- 最终统一复核 JM Debug/Release、六个 SDK Release、两个示例 Debug，共十个产物全部通过 Java/Kotlin 出口扫描。逐项结果见 [产物检查记录](2026-09-23-property-logging-artifacts.txt)。
- 独立代码复核未发现新的严重或重要问题；额外检查 JM Debug 和 GAM 示例 DEX，未发现通过标准文件描述符、Java Logger 子类或其他 Android 日志 API 打印的遗漏出口。

复核脚本：`python3 scripts/verify_property_logging.py <apk或aar> ...`。脚本检查直接 Android Log、标准输出字段读取，以及不含属性判断的 Java Logger/无参数异常堆栈出口。这是已列出出口的静态检查，不是任意控制流、反射调用或原生代码的完整证明；字节码运行测试单独验证插入的分支实际生效。未在客户设备上安装或执行开关实测。

## 未能宣称覆盖的边界

1. `lib_autorun-release-202608191720.aar` 包含原生 `libpluginloader.so`，存在 `__android_log_print` 调用。Java 字节码转换不能控制该调用。邻近 SdkKit 工程有相关 C 源码，但本次未替换当前依赖的原生二进制。
2. 运行时下载或动态加载的插件未经过当前 APK 构建流程，需要在插件各自构建流程应用相同规则。
3. Android 系统、其他进程、WebView/播放器原生实现的日志不属于应用 Java/Kotlin 打印出口。
4. hq002/hq004 等旧 flavor 的附加构建检查遇到公共源码依赖缺失，例如 Hq008CmpManager、Hq008ConsentLogReporter 和广告配置字段；不能声明所有历史 flavor 都完成构建验证。
5. AAR 验证覆盖其中实际包含的类和内嵌 JAR。外部 App 单独集成 AAR 时，其宿主代码及额外解析的 Maven 依赖不会自动经过本项目的 APK 日志处理，需要宿主构建接入同样规则。

因此，本次验证的是仓库源码与上述成功构建产物的 Java/Kotlin 日志控制，不能称所有运行环境日志已被彻底控制。

## 使用

```sh
adb shell setprop persist.sys.ad.log true
adb shell getprop persist.sys.ad.log
adb shell setprop persist.sys.ad.log false
```

已交付客户的 JM 1.0.7 旧 APK 不会随源码变化自动更新，需要替换安装包。
