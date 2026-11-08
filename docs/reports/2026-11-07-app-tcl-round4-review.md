# 2026-11-07 app / ad-sdk-tcl 第四轮修复复审

> 后续状态：本页 RDID 问题已由助手修复，当前实现与验证见[修复与整体复查交付](/Users/zy/Documents/Codex/ad-app/docs/reports/2026-11-07-rdid-fix-and-overall-recheck.md)。以下保留修复前证据。

## 结论

上一轮的取消回调、CMP NOT_REQUIRED 补报、WebKit R8 方法签名问题已验证关闭。相关构建成功，**833 次现有模块单测全部通过**。

目前只确认 **1 项 P2 尚未闭合：RDID 初始化的锁保障**，包含同进程重叠锁降级和 app API 24/25 路径两个具体缺口。本轮未修改生产代码或既有测试。

## [P2] 文件锁失败后无锁生成，仍不能保证单一 RDID

### A. 同进程锁重叠不是等待，catch 后又绕过了锁

位置：

- [TCL ExecutionConfig.java:79–92](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/ExecutionConfig.java#L79)
- [GoogleImaExecutionConfig.java:80–93](/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/google-ima/src/main/java/com/smart/android/tclad/ima/GoogleImaExecutionConfig.java#L80)
- [Hq008ApiRequestFields.kt:38–54](/Users/zy/Documents/Codex/ad-app/app/src/main/java/com/smart/android/ad_app/Hq008ApiRequestFields.kt#L38)
- [ImaSharedAdvertisingIdentity.java:113–130](/Users/zy/Documents/Codex/ad-app/ad-sdk-google-ima/src/main/java/com/smart/android/ad_app/google/ima/local/ImaSharedAdvertisingIdentity.java#L113)

各入口现在确实使用同一个宿主 filesDir 下的 `tcl_aid.lock`，路径选择没有跨包问题。但同一进程内不同 channel 对同一区域再次加锁时，可能抛 `OverlappingFileLockException`，不会像不同进程竞争那样阻塞等待。

当前各入口捕获 Throwable 后，继续执行不受锁保护的“读空 → 生成 UUID → commit”。因此：

```text
A 持有真实文件锁，已读到空 aid，尚未写入
B 重叠加锁失败，进入无锁 catch 分支
B 也读到空 aid，生成并返回 B
A 恢复，生成并返回 A
两份配置已冻结不同 ID
```

本轮重新编译四个当前生产入口，使用真实 JVM FileChannel/FileLock 和同一个 Context、同一个 filesDir、同一份 preferences 边界，执行 10 种同/跨入口组合：

| 组合 | A 真实持锁 | B 在 A 释放前进入读 | ID 一致 |
|---|---|---|---|
| TCL → TCL | 是 | 是 | 否 |
| TCL → IMA | 是 | 是 | 否 |
| TCL → app | 是 | 是 | 否 |
| TCL → shared | 是 | 是 | 否 |
| IMA → IMA | 是 | 是 | 否 |
| IMA → app | 是 | 是 | 否 |
| IMA → shared | 是 | 是 | 否 |
| app → app | 是 | 是 | 否 |
| app → shared | 是 | 是 | 否 |
| shared → shared | 是 | 否 | 是 |

shared → shared 因额外 TCL_LOCK 串行而通过，说明测试允许正确实现等待。夹具不是让两个线程都在锁内互等的旧 barrier：主线程观察 B 最多 500ms 后必释放 A，正确锁实现会让 B 等待后读取同一个 ID，不会被人为卡死。

这是**同一个宿主应用、同一进程的两个线程**，不涉及两个应用共用私有目录或标识。证据：[identity/result.log](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-runtime/identity/result.log)、[matrix.json](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-runtime/identity/matrix.json)。Android Context/SharedPreferences 为边界桩，文件与文件锁是真实的；未将它表述为 Android 真机测试。

需要在所有参与入口之间共享进程内串行边界，再配合文件锁；加锁失败不能回退到无锁生成和写入。分别给每个模块加自己的 Class 锁，不能串行跨模块调用。

### B. app 的加锁代码使用 API 26，最低版本却是 24

位置：[Hq008ApiRequestFields.kt:39–42](/Users/zy/Documents/Codex/ad-app/app/src/main/java/com/smart/android/ad_app/Hq008ApiRequestFields.kt#L39)。

虽然已经打开 RandomAccessFile，但没有使用其 channel，而是再次调用：

```kotlin
FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)
```

本机 Android SDK 官方 api-versions.xml 显示以下 API 均从 26 开始：

- File.toPath()
- FileChannel.open(Path, ...)
- java.nio.file.StandardOpenOption

app minSdk=24，当前未配置 coreLibraryDesugaring。API 24/25 不能使用这条加锁调用；代码捕获异常后的降级路径仍是无锁生成/写入。因此即使避免了同进程重叠，该最低版本路径也没有同等锁保障。

应使用已有 RandomAccessFile 的兼容 channel，保持加锁路径覆盖最低支持版本。此结论来自当前源码与本机 SDK API 元数据；本轮没有 API 24/25 设备实测，也不将它报告为已观察到的设备崩溃。

证据：[android-api-metadata.json](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/android-api-metadata.json)，原始元数据路径已记在文件中。

## 已关闭项的本轮证据

### GoogleGamClient 取消与超时

当前每次 resolve 使用独立 hostCancelled，显式取消先设置标记，再取消 Call。onFailure、正文 catch 和成功分派均检查该标记。

23 项正向断言全部通过，包括：

- headers/read/body 超时及真实默认 20 秒总超时有终态回调。
- timeout 已产生但 SDK 尚未交付时取消，旧回调被抑制。
- 真实正文 socket 阻塞时取消，Socket closed 不再漏出业务错误。
- 终态不重复；宿主回调中取消并再次 resolve 无死锁。

IMA 所有权/重入/迟到回调的 12 项回归也全部通过。

说明与证据：[sdk-core/README.md](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-core/README.md)。

### Google CMP NOT_REQUIRED 分支

NOT_REQUIRED 现在会启动待补报告发送；报告成功清 pending、失败保留并可在下一轮重试。accepted、冻结 preferences 恢复、不同 action 的 ACK 保护、onDone 与同步重入均通过。independent 启动 timer 回归通过。

blocked / noProvider 分支保留 pending、等待前置状态恢复后消费的延期边界，以及相同 action 的状态合并语义，未据未明确的业务要求新增确定缺陷。

说明与证据：[app-flow/README.md](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/app-flow/README.md)。

### WebKit R8 签名

当前 keep 已匹配实际的 ScriptHandler 三参数方法。本轮当前规则的最小 R8 验证中，两个 feature 字段及 addDocumentStartJavaScript 反射入口均保留。不是使用历史 Release APK 验证；也不声称完整客户 Release 真机验证。

证据：[webkit/result.log](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-runtime/webkit/result.log)。

## 构建与测试

| 套件 | 执行 | 失败/错误/跳过 |
|---|---:|---:|
| ad-sdk-tcl | 71 | 0/0/0 |
| ad-sdk-tcl:google-ima | 66 | 0/0/0 |
| ad-sdk-google-ima | 32 | 0/0/0 |
| app HQ008 TCL | 240 | 0/0/0 |
| app Google IMA | 225 | 0/0/0 |
| app Google independent | 199 | 0/0/0 |
| **合计** | **833** | **0/0/0** |

两次相关 Gradle 构建均成功。833 是模块级执行数，含共享测试重复，不称为 833 个独立场景。git diff --check 通过。本轮没有新的真实广告播放、CMP UI 或生产后台验证。

## 可重跑

所有产物位于 [build/review-20261107-round4](/Users/zy/Documents/Codex/ad-app/build/review-20261107-round4)，含本轮源码 SHA-256、测试 XML、构建日志及探针来源信息。

```bash
export JAVA_HOME=/Users/zy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
cd /Users/zy/Documents/Codex/ad-app

python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-core/run_timeout.py --default
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-core/run_ima.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/app-flow/run.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-runtime/run_webkit.py
python3 /Users/zy/Documents/Codex/ad-app/build/review-20261107-round4/sdk-runtime/run_identity.py
```
