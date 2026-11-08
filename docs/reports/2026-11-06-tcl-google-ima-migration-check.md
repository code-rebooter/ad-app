# ad-sdk-tcl 纯 IMA 迁移检查（2026-11-06）

本轮检查发现并修复了实际遗漏。最终迁移模块 60 项 JVM 回归、原 IMA 模块 28 项回归均通过；Debug/Release AAR、普通 Java 宿主的 R8 Release APK 构建通过。Release Lint 为 0 错误、19 警告。

## 发现与修复

1. 复用核心源码时仍引用旧模块生成的 PropertyLog，首次编译出现 44 个找不到符号错误。已映射到新模块生成的日志类；源码生成任务将映射表纳入输入，映射变化也会重新生成。
2. 普通消费宿主会从 PAL 等传递依赖引入原版 AppSet，与受控 AAR 发生重复类。已在独立受控 POM 中排除五种对应原版模块；消费方不需要借用 app 的 dependency substitution。最终宿主依赖图仅保留这五种模块的受控版本。
3. API 23 下的 Map.computeIfAbsent/getOrDefault/putIfAbsent 和 StringJoiner 调用存在兼容问题。已改为等价的基础集合/StringBuilder 操作；同时为受原有 SDK 判断保护的 API 24/30/31 桥接、由系统调用的 WebView 高版本回调添加准确的 API 标注，补齐 Media3 UnstableApi opt-in。系统服务、网络类型和 checksum 数值改为等值平台常量。共享核心也采用这些修复，原 IMA 模块的 28 项测试通过。
4. 五份独立受控 AAR/POM 原先只有手工生成入口。已增加 preBuild 校验，先核对 app 原受控包与补丁产物，再校验独立包、POM 和来源/输出 SHA；缺失或过期直接失败。runtime-manifest.json 的 JSON 尾部换行已修正。
5. 对齐宿主状态诊断的 hiddenMode/startProgress 字段，删除被本轮 gam_config 或固件策略覆盖、实际无效的配置入口。

## 对照和回归范围

- 91 份核心 Java 源码与 app 使用的 IMA 核心逐文件对照，仅应用声明的固件桥接、URL 桥接和日志包映射。
- 授权配置、默认值、reported_sound_mode、enable_fake_ctr、长整型起播期限；本轮缺失配置不复用旧值。
- Core 完整 URL、冻结身份、native/WebView 回退共享一次 patch、取消、过期、错误不回退；新增静音/非静音穿过完整 patch 链路的回归。
- 逐广告身份、媒体起播证据、播放软/硬预算、缓冲与进度、终态结算、完整 VAST/XML 和 OMID/声音审计。
- 遍历五份受控 AAR 的 class 方法引用，将每个固件桥接调用的完整签名与实际编译的 SDK 桥接类核对；确认没有旧 Hq008 桥接引用。
- Release AAR 检查确认包含独立入口、IMA 核心、固件桥接和生成日志类，未混入 com/tcl 或 io/codex/imarepro 的执行类。
- 新增可复现的普通 Java 宿主用例，只依赖 :ad-sdk-tcl:google-ima，实际完成 R8/DEX/资源合并和 Release APK 打包；用例传入空配置，不请求商业广告。

## 验证命令

```sh
./gradlew :ad-sdk-tcl:google-ima:assembleDebug :ad-sdk-tcl:google-ima:assembleRelease :ad-sdk-tcl:google-ima:testDebugUnitTest :ad-sdk-tcl:google-ima:lintRelease :ad-sdk-google-ima:testDebugUnitTest --offline
./gradlew -I ad-sdk-tcl/google-ima/tools/consumer-smoke.init.gradle :google-ima-consumer-smoke:assembleRelease --offline
python3 ad-sdk-tcl/google-ima/tools/prepare_controlled_runtime.py --check
```

本轮日志位于 `/tmp/ad-sdk-tcl-ima-final.log`、`/tmp/ad-sdk-tcl-ima-extra-tests.log`、`/tmp/ad-sdk-tcl-ima-verify.log`。测试 XML 和 Lint 报告由模块 build 目录生成。Lint 的 19 项警告包括固定依赖版本、已有 SDK 条件、API 桥接标注建议及系统属性/硬件标识等提示；未通过关闭 Lint 或加入 baseline 隐藏错误。

## 产物

| 路径 | SHA-256 |
| --- | --- |
| `ad-sdk-tcl/google-ima/build/outputs/aar/google-ima-debug.aar` | `96d409b1f609fae829b1bb2871e9fbfc14b2c6398489d830b9f4580fb294ee14` |
| `ad-sdk-tcl/google-ima/build/outputs/aar/google-ima-release.aar` | `724673e55db23f5be1a56792abffa2a52c50c46d0e5782eb1ce7bb47c3b6b4d6` |
| `ad-sdk-tcl/google-ima/src/test/consumer-smoke/build/outputs/apk/release/google-ima-consumer-smoke-release-unsigned.apk` | `536ff5cab9372a0ee9ac42750f704aaac5afa218f60a0e33584576436388f44d` |

## 宿主边界与未覆盖范围

纯 IMA 执行层已经迁入 :ad-sdk-tcl:google-ima；原 TCL 引擎模块仍保留。CMP/UMP、业务调度、authorize、ad/report 与 consent-log-report 的 HTTP 发送及重试继续由宿主负责。SDK 的 onAdReport/onAdAudit 提供对应事件、冻结身份和完整审计，宿主必须接入已有上报传输；接口回调本身不代表后台已收到报告。app 的 Debug 样本和强制放行未放入 SDK。

本轮没有安装或操作两台已连接实体设备，没有发起真实广告请求。因此这些结果证明源码、依赖、构建和上述 JVM 分支检查通过，不构成实机播放、实际 Google 测量或生产后台收报成功的结论。

## 后续验证

本文保留最初 API 23 迁移检查时的记录。随后已统一最低 API 24，并完成两台实体设备验证；最新结果、产物哈希及 Google 素材 CDN 404 的未通过项见 [minSdk 24 真机验证](2026-11-06-pure-ima-min24-device-validation.md)。

后续 Google 与 TCL 二合一交付及缺陷修复结果见 [2026-11-06 二合一客户验证](2026-11-06-combined-sdk-customer-validation.md)。本文保留当时产物和外部素材失败记录。
