# 三模块最终源码验证（124 实机）

## 结论

- `app`、`ad-sdk-tcl`、`hq008-flow-sdk` 的本轮主矩阵与全部专项通过，无未关闭验收项。
- 覆盖 TCL、Google IMA，以及 app 的独立 Google URL 转 TCL 分支。
- 设备：`192.168.0.124:5555`，Android API 29。
- 使用冻结源码重新构建并绑定实际安装 APK SHA-256；未复用旧轮采集或通过结论。
- 验证冻结后生产源码未改；503 项生产输入、654 项扩展输入复核一致，无新增、删除或改变。

## 主矩阵

| 测试组 | 实测通过 |
|---|---:|
| 基础播放、业务调度、重入及上报恢复 | 120/120 |
| 启动与缓冲保护 | 12/12 |
| 物理/上报音量及动态 OM | 24/24 |
| 补片异常 | 24/24 |
| R8 非调试构建 | 4/4 |

合计：184/184。另有 8 个全新 Gradle 单元测试任务，886 项，0 failure/error/skipped。

## 已验证闭环

1. 单条/Pod/Wrapper、短片和 210 秒长片：播放、真实 tracking/业务上报、一次终态与资源清理。
2. 空广告、素材错误、HTTP/JSON/URL 错误、响应头/响应体/补片超时、主动取消：有界终态、连接取消和清理。
3. 首帧启动保护、缓冲卡死、缓冲恢复；取消/替换/同步回调重入、待处理请求及跨 manager 隔离。
4. Flow 流控、CMP、授权、宿主 watchdog、停止/完成后重启。
5. app/Flow × TCL/Google 四组完成上报：持续 503、完整 payload 入 SQLite、真实新 PID、无新广告恢复投递、原 body 200 ACK、条件删除和后续健康播放。
6. 双进程队列并发、旧 ACK 保留同键新 payload、SQLite 旧队列迁移与匹配 ACK 清理。
7. 同包双进程/多引擎 RDID 16 项、app 标识失败/恢复 12 项、跨 JVM/真实磁盘 92 断言。
8. TCL CMP 14 项、REJECT 四份状态及冷读、完整快照/条件 ACK 151 断言、TC freshness 22 断言。
9. 真实静默 UMP：首次与冷热同意/拒绝、非 EEA、SDK 加载错误及恢复、宿主销毁、合并请求；MP4/PNG 验证静默；15 秒动作超时后 18 秒 renderer 恢复无迟到点击。
10. 物理/上报声音四种组合、动态开关、实际系统音量 7→6→7、独立 OM 事件及 ActiveView；保留既有声音映射。
11. 精确渠道 gate、配置缺失/拒绝、独立 Google URL 原单 AdsEvent 契约；审计开关与 R8 非调试构建。
12. 实际版本 wire：TCL `2.8.02`，纯 IMA `google_ima_3.35.1`，独立 Google URL 转 TCL `2.8.02`。

## 定制与原始失败记录

静默 UMP、既有 ACCEPT/REJECT 自动动作、拒绝后的广告放行、物理/上报声音分离和原单 AdsEvent 逻辑保留。未为通过测试而归一化业务。

原始失败采集全部保留；修正了测试的实际触发条件、异步 ACK 观察时序、业务 channel 授权夹具输入和观察命令延迟。真实 UMP 原生加载失败按错误回调、清理和恢复路径留证，最新完整运行通过。

## 本地证据与完整性

完整报告及 5651 个原始证据的索引在本机目录：`build/three-module-final-20261108/`。大型日志、视频、APK 和临时构建缓存不纳入代码提交。

| Evidence | SHA-256 |
|---|---|
| `source-freeze.json` | `7c1b7846d47fe9b6a355ebfa9c58635424d74147c2ab84b54a360d1792b47bf9` |
| `source-inputs-expanded.json` | `d89507afc9801671a2257b2aa837efb016f993b55ac0b136f86340fdd9770ceb` |
| `ACCEPTANCE.json` | `9be1d482dc565da527fc189c6d39cdb4b0c9aa359f64f8fa8455279bd26e918c` |
| `FINAL-CHECK.json` | `35f5f9ac288a24839b333b7d679b579e6d0d882650dce9ae86f1903a3844e424` |
| `evidence-integrity.json` | `299b420632eaaca8fb86c31491a6007a80767d79b0db5a5f5e645d92a43aeaf3` |

设备代理、音量/静音、信号、reverse/forward、前台均恢复；14 个本轮测试包及 3 个 fixture 服务已停止。未卸载、未清应用数据、未清 logcat、未重启设备。
