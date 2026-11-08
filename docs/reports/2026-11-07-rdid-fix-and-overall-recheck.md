# 2026-11-07 RDID 修复与整体复查交付

## 结论

RDID 的同进程并发初始化与 API 24/25 锁路径问题已修复，并补齐存储失败时 app 的正常错误回调及资源清理。**833 次现有模块单测、161 项身份/取消/生命周期定向检查全部通过**；相关 SDK Release AAR 与 app Debug APK 构建成功。

本轮整体代码复审未再确认新的业务逻辑缺陷。**不将所有检查表述为全绿**：SDK lint 无错误，但 app lint 仍有既有 TV/Manifest 与依赖通知权限诊断，详见下文。未新增启动入口或通知权限来消除这些诊断。

## 实际修复

### 1. 四个身份入口采用共同进程锁，再取得文件锁

修改：

- [TCL ExecutionConfig](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/ExecutionConfig.java)
- [GoogleImaExecutionConfig](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaExecutionConfig.java)
- [app Hq008ApiRequestFields](/Users/zy/Documents/Codex/ad-app/app/src/main/java/com/smart/android/ad_app/Hq008ApiRequestFields.kt)
- [IMA shared identity](/Users/zy/Documents/Codex/ad-app/ad-sdk-google-ima/src/main/java/com/smart/android/ad_app/google/ima/local/ImaSharedAdvertisingIdentity.java)

四处严格使用相同协议：

```text
("com.smart.android.tclad.identity:" + lockFile.getCanonicalPath()).intern()
→ synchronized(common monitor)
→ RandomAccessFile.getChannel().lock()
→ 读取已有 aid，或生成并同步持久化
```

这样同进程读者不会通过不同 channel 产生重叠锁后进入无锁分支。锁失败不再回退生成新 ID；I/O 失败明确抛 IllegalStateException。app 不再使用 File.toPath、StandardOpenOption 或 FileChannel.open(Path)，保留 API 24 可用的 channel 获取方式。

prefs 名、`aid` 键、UUID 格式及已有非空 ID 均保持不变；没有迁移或改写设备既有 ID，也未添加跨应用共享路径或新的模块依赖。修复范围是同一宿主进程的参与入口；文件锁不等于 Android SharedPreferences 跨进程缓存一致性承诺。

### 2. 写入失败不返回新 ID，也不让脏内存缓存影响重试

新 ID commit=false 时，在两层锁仍持有期间同步 remove/commit 清理未确认值，再抛原异常；清理失败保留 suppressed 信息。没有异步 apply，也没有无锁降级写入。

验证模拟 Android 的真实语义：失败 commit 也会先更新内存。修后清理为空，恢复存储后的第二次调用必须重新生成并成功持久化，不能无写地复用上次未落盘值。已有非空 ID 读取不额外写入。

### 3. app 把准备失败转换为已有失败协议

严格身份读取不再静默回退，因此同步修复三个宿主接入点：

- [Hq008SdkAuthorizeClient](/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt)：请求体准备异常转 AUTHORIZE_FAIL / onResult(null,error)，不发送网络请求。
- [GoogleImaAdManager](/Users/zy/Documents/Codex/ad-app/app/src/googleImaRuntime/java/com/smart/android/ad_app/GoogleImaAdManager.kt)：参数准备异常走既有 fail，释放播放器、结束审计并发错误回调。
- [TCL AdManagerImpl](/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt)：准备前登记所有权；失败退休 ticket、关闭 lease，保持正常 adError；准备期间同步替换不会清掉新请求。

本轮没有改变播放引擎、音量、展示、CMP 决策、广告 URL 参数或报告业务策略。

## 可长期复跑的回归代码

测试代码已加入源目录，不仅保存在可清理的 build 中：

1. [identity-lock](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/test/identity-lock/README.md)：编译四份当前生产身份代码，使用真实 JVM 文件锁；122 项断言及 4 项 API 24 字节码检查。
2. [identity-preparation](/Users/zy/Documents/Codex/ad-app/app/src/test/identity-preparation/README.md)：授权完整生产源码错误转换，以及 TCL/IMA 准备/清理的源码片段回归。

二者均位于 Gradle 默认 `src/test/java` 之外，Android 边界桩不会污染普通单测。输出生成在 build 下。

```bash
export JAVA_HOME=/Users/zy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

python3 /Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/test/identity-lock/run.py
python3 /Users/zy/Documents/Codex/ad-app/app/src/test/identity-preparation/run_authorize_failure.py
python3 /Users/zy/Documents/Codex/ad-app/app/src/test/identity-preparation/run_prepare_paths.py
```

需要当前项目已有 Gradle 缓存和 Android 36 SDK；identity runner 支持 JAVA_HOME、ANDROID_HOME、GRADLE_USER_HOME 和 --work-dir。

## 验证结果

### 现有构建与单测

| 套件 | 执行 | 失败/错误/跳过 |
|---|---:|---:|
| ad-sdk-tcl | 71 | 0/0/0 |
| ad-sdk-tcl:google-ima | 66 | 0/0/0 |
| ad-sdk-google-ima | 32 | 0/0/0 |
| app HQ008 TCL | 240 | 0/0/0 |
| app Google IMA | 225 | 0/0/0 |
| app Google independent | 199 | 0/0/0 |
| **合计** | **833** | **0/0/0** |

这是模块级执行数，含共享测试重复，并非 833 个独立场景。

- TCL、迁入 IMA、combined、共享 IMA core 的 Release AAR 构建成功。
- app HQ008 和 Google IMA Debug APK，以及默认 independent Debug APK 构建成功。
- 最终纯 IMA/TCL 构建日志：[final-build-ima.log](/Users/zy/Documents/Codex/ad-app/build/repair-20261107/final-build-ima.log)；默认 independent：[build-independent.log](/Users/zy/Documents/Codex/ad-app/build/repair-20261107/build-independent.log)。

### 定向检查

| 检查 | 结果 |
|---|---|
| 四入口 10 组同/跨入口并发、canonical 路径别名、单次写入 | 全部同 ID，第二读者等待 |
| 锁文件不可打开、已有 ID、commit 失败及清理后重试 | 122 项身份断言全部通过 |
| 四个生产身份入口 API 24 路径字节码 | 4 项通过，无 Path/FileChannel.open(Path) 引用 |
| 网络超时、取消、重复终态、回调重入 | 23 项通过，含真实默认 20 秒用例 |
| IMA 所有权、重入、旧会话晚回调 | 12 项通过 |
| app 授权身份失败 | 错误回调，无外抛、无网络请求 |
| TCL/IMA 准备及清理 | 2 个重建的修前对照＋7 个修后场景通过 |
| CMP 与 independent 启动计时 | 回归通过 |
| 当前 WebKit R8、原厂隔离、五份受控依赖同步 | 通过 |

161 是 122＋4＋23＋12 项定向断言/检查的合计，不把重复运行或补充 app 夹具凑入该数字。持久化源码 runner 也已实际独立运行通过。

## 整体复查的剩余静态诊断

SDK lint：TCL release 0 错误/11 警告；迁入 IMA release 0 错误/23 警告；共享 core debug 0 错误/16 警告。

app lint 未通过：HQ008 Debug 10 错误/104 警告，Google IMA Debug 8 错误/92 警告。错误归为以下四类，含跨 sourceSet、跨 variant 的重复定位，不是 18 个独立业务缺陷：

| 诊断 | 当前定位/原因 |
|---|---|
| MissingLeanbackLauncher | main、HQ008、Google、Debug manifests 没有 Leanback launcher；当前工程通过 Provider 等入口运行，没有为消 lint 新加可见入口。 |
| ImpliedTouchscreenHardware | 部分 manifest 未显式将 touchscreen 标为可选。 |
| MissingLeanbackSupport | 部分 manifest 缺少 optional leanback feature 声明。 |
| NotificationPermission | Glide 的 NotificationTarget 依赖触发权限检查；app 自有源码未找到 NotificationTarget/NotificationManager/NotificationCompat 调用。本轮没有新增 POST_NOTIFICATIONS。 |

这些定位的 manifests/依赖不是本次 RDID 修复修改的文件。保留原始 lint 错误，没有添加 baseline、关闭检查或宣称 app lint 已绿。

APK 打包还出现 ASM 对部分依赖类解析的提示；实际编译、dex、APK 打包和本轮 JVM 回归通过。本轮没有据这个提示推断已经发生运行时损坏，也没有代称设备验证通过。

原始证据：[lint.log](/Users/zy/Documents/Codex/ad-app/build/repair-20261107/lint.log)、[lint-summary.json](/Users/zy/Documents/Codex/ad-app/build/repair-20261107/lint-summary.json)、[lint XML 快照](/Users/zy/Documents/Codex/ad-app/build/repair-20261107/lint-reports)。

另外修正了迁入 IMA README 中历史音量试验与当前源码混用的描述，明确原试验记录不代表此次修复验证；没有借此变更音量代码。

## 交付位置与范围

当前源码已直接修复在工作区。主要构建产物：

- [TCL Release AAR](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/build/outputs/aar/ad-sdk-tcl-release.aar)
- [Google IMA Release AAR](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/build/outputs/aar/google-ima-release.aar)
- [combined Release AAR](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/combined/build/outputs/aar/combined-release.aar)
- [app 纯 IMA Debug APK](/Users/zy/Documents/Codex/ad-app/build/app-google-ima/outputs/apk/google_ad_tv_desktop_ambientlight/debug/app-google_ad_tv_desktop_ambientlight-debug.apk)
- [app HQ008 Debug APK](/Users/zy/Documents/Codex/ad-app/build/app-google-ima/outputs/apk/hq008/debug/app-hq008-debug.apk)

AAR 仍按原工程的传递依赖方式接入，combined AAR 不是单文件 fat AAR。没有发布远端 Maven、修改客户版本号、替换历史冻结 ZIP、提交或推送代码。

全部内部证据：[build/repair-20261107](/Users/zy/Documents/Codex/ad-app/build/repair-20261107)，含源码摘要、测试 XML、构建日志、产物 SHA-256、修前失败和修后成功记录。当前源码快照见 source-sha256.json；产物摘要见 artifact-sha256.json。

本轮没有新的 API 24/25 真机、真实广告/CMP UI 或 OMID 设备矩阵。API 24 身份路径结论基于兼容 API 选择和字节码检查；文件锁测试使用真实 JVM 文件与锁，Android 边界使用桩；app 清理回归部分为生产方法体提取编译。没有将这些结果表述为生产入库或设备播放验收。
