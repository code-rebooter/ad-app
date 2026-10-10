# 原厂 TCL adurl 引擎验证 Demo

Demo 调用 `ad-sdk-tcl`，广告处理使用 TCL 2.8.02 隔离运行库。当前包名为 `com.smart.android.tclad.demo.isolated`；完整隔离范围见 [ISOLATION.md](../ad-sdk-tcl/ISOLATION.md)。固定真实链接只在 Debug assets 中；默认配置快照与 SDK 内置快照一致。

## 本地对照

```bash
SERIAL=192.168.31.78:38841 # 换成当前已连接设备
python3 ad-sdk-tcl-demo/tools/parity_server.py --output artifacts/my-parity/local-http.jsonl
adb -s "$SERIAL" reverse tcp:18888 tcp:18888
./gradlew :ad-sdk-tcl-demo:assembleDebug
adb -s "$SERIAL" install -r ad-sdk-tcl-demo/build/outputs/apk/debug/ad-sdk-tcl-demo-debug.apk
python3 ad-sdk-tcl-demo/tools/run_parity_matrix.py --serial "$SERIAL" --package com.smart.android.tclad.demo.isolated --output artifacts/my-parity
python3 ad-sdk-tcl-demo/tools/audit_parity.py --output artifacts/my-parity --require-complete
```

每轮使用一个新输出目录。先记录设备状态并停止竞争音频焦点的广告进程；测试后恢复状态、删除本次 adb reverse。自动化测试期间避免其他任务同时发送 ADB 按键或切换前台。

**隔离版中的 reference 也使用隔离运行库，只能验证两种入口的交接一致。**它不能充当未修改原厂的行为基线。未修改原厂的历史证据见 [parity-results.json](../artifacts/tcl-original-engine-migration-20261009/parity-results.json)。审计隔离版时可用 `--reference-output artifacts/tcl-original-engine-migration-20261009` 指定该基线，再用 `--cases single wrapper pod guarantee cycle release-in-flight release-playing` 指定本轮必要用例；当前输出目录需有对应的 `用例-candidate` 抓包。

两种入口：

- `reference=true`：直接调用原厂 `Ad/Config.start()`；demo 仅提供固定取链接响应，后续全为原厂代码。
- 默认 candidate：通过我们的公开 API 传入 adurl；内部同样调用原厂 `Ad/Config.start()`，地址来源适配器返回同一份 JSON。

15 组用例包含单条/三条广告、空响应、坏 XML、HTTP 错误、未知 MIME、Wrapper、循环 Wrapper、保底、素材失败、暂停/继续/隐藏/显示/音量、释放与超时。三条广告对照固定延迟 2 秒开播，让素材加载期间的播放器状态一致。

审计比较实际 VAST 请求链、跟踪 URL 和次数、事件阶段先后、公开回调序列及原厂错误结果。仅规范化每轮原厂生成的 requestId；并发发送的同一事件多个跟踪地址按集合比较，不能把线程调度顺序当成固定协议。回调不去重。

原厂 LOADED 由当时的播放/准备状态决定；即时开播的三条广告可在素材完成时间不同的运行中产生不同次数的 LOADED。测试程序记录实际次数，不额外压成一次。原厂完成回调在已测本地样本中出现两次，保持原样。

## 真实固定链接

```bash
adb -s "$SERIAL" shell am force-stop com.smart.android.tclad.demo.isolated
adb -s "$SERIAL" shell am start -n com.smart.android.tclad.demo.isolated/com.smart.android.tclad.demo.DemoActivity --es live_source google
# 本轮完成后再执行雷鸟
adb -s "$SERIAL" shell am force-stop com.smart.android.tclad.demo.isolated
adb -s "$SERIAL" shell am start -n com.smart.android.tclad.demo.isolated/com.smart.android.tclad.demo.DemoActivity --es live_source leiniao
```

Google 使用提供的 `/21775744923/external/single_preroll_skippable` 测试标签，先生成 Google correlator 和实际 demo 应用/静音参数。雷鸟使用 2026-10-08 抓到的完整标签模板，仅在输入前刷新 cb。之后原厂会自行追加/替换 requestId。雷鸟模板中的历史设备、节目和登记上下文只用于这轮重放。

通过 `--es vast_url URL` 可提供其他 VAST 地址；`google_tag_url` 会先经过 Google 参数准备工具。`gam_resolve_url/channel_id/mac` 保留现有取链接接口测试方式，返回链接后走相同原厂引擎。

## 抓取与复现

`files/live-来源-时间戳/` 保存本轮实际 VAST XML、HTTP 请求/响应和回调。视频播放还需检查原厂日志与画面。demo 的 `LiveCapture` 在原厂客户端上增加记录拦截器，不修改 VAST 响应或跟踪请求；reference 模式仅替换进入 adurl 之前的取链接响应。

`run_parity_case.py` 必须显式传入 `--serial`，隔离包使用 `--package com.smart.android.tclad.demo.isolated`；若使用独立 ADB 服务，另传 `--adb-server-port`。脚本同时保存进程代理选择记录 `network-audit.jsonl`（发生连接尝试时）与应用目录清单 `app-directories.txt`。可单独跑一个用例，支持 `--reference`、`--live`、`--controls`、`--release-after`、`--release-after-vast`、`--start-delay`。`--default-config` 验证 SDK 内置配置而不是 demo 显式设置配置。脚本默认禁用界面手动按钮，按预定操作对照；人工测试直接启动 Activity 即可使用按钮。

设备负向探针使用 `tools/run_isolation_probe.py --serial 当前设备 --output 新目录`，检查初始化及被禁用入口、XML Java 类型与外部 DTD/实体；广告网络审计使用 `tools/audit_isolation_network.py --output 测试目录`。进程代理选择审计记录连接来源，不能替代原始套接字抓包。

测试工具直接读取 Demo 的事件文件，在本地诊断日志关闭时仍能判断播放和导出抓包。`automated=true` 时仅测试页自动亮屏、保持可见，防止 MIUI 锁屏休眠影响测量；不会修改手机代理或注入按键。

同进程交接使用 `tools/run_same_process.py`，默认检查空广告→正常广告；`--first-case single --second-case wrapper --release-between-ads` 检查两轮独立广告机会，并在两轮之间调用原厂 release。`--reference` 走同一隔离库的原厂公开入口。工具合并两轮实际记录，检查新 adurl、Wrapper 链、回报总数和完整完成回调；每个结果记录实际测试 APK 哈希。未 release 的间歇停播记录见验收报告，不能当作已修复。

默认本地播放器素材是 assets 中生成的 20/30/15 秒 MP4，无真实广告流量。真实地址测试失败不会退回本地样本。宿主 API、原厂代码核验和本轮结果见 [SDK 文档](../ad-sdk-tcl/README.md)与[迁移记录](../artifacts/tcl-original-engine-migration-20261009/report.md)。

2026-10-10 已完成本隔离版手机验收，Google/雷鸟实播及八组行为对照见 [当前验收报告](../artifacts/tcl-isolation-20261009/report.md)。
