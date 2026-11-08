# Device 124 全链路修复与验证最终报告

更新：2026-11-07T14:43:56.271060+08:00。设备仅 `192.168.0.124:5555`，Android API 29。覆盖 `app`、`ad-sdk-tcl`、`hq008-flow-sdk` 的 TCL / Google 路径；包含 app 的 independent Google URL→TCL 路径。此报告不把“准备了脚本”写成设备通过，也不保证所有可能的运行环境或未列场景。

## 当前结论

七类已批准修复及本轮明确列出的有限验证清单已完成验收。最后追加的 app `AD_COMPLETED` 持久化已在两份新 APK 上实际通过：两引擎各1次持续503故障与1次重启后健康播放，共4个真实会话；原完整payload落盘、真实换PID、原payload重发200、对应key清理、完整播放及晚期资源均通过。追加修复后的七项单测重新执行并独立重算为847/0失败/0错误/0跳过。环境恢复9项也已取得实际PASS；下列未覆盖边界仍保留，不能把有限验收扩张为所有可能场景保证。

## 七类已批准修复

| 项目 | 实际改动范围 | 验证状态 |
|---|---|---|
| 同包多进程 RDID 缓存分歧 | 四个入口、四份 helper；在同一文件锁下读回持久 ID，失败不派发广告，保留磁盘上已提交 ID | 修复后双 PID 16/16、identity70、app 故障/同 PID 正常恢复12/12 PASS |
| Flow 完成上报未持久化 | `AD_COMPLETED` 与已有请求/错误上报共同保存完整 payload | TCL / Google 持续503→真实进程重启→原 payload 补发200→ACK清理 PASS |
| Flow 旧 ACK / worker 退出调度窗口 | 同锁比较原始 payload 后清理；每次 enqueue 使用 `APPEND_OR_REPLACE` 安排后继 work | 77项针对性 JVM断言、3项JUnit及上述两引擎真实持久回归 PASS；JVM模型不冒充多进程压力测试 |
| TCL CMP 旧成功回调清掉新待补状态 | 为本地待补状态加 generation，只清除匹配的那一笔 | 11个主case + 3个recovery PASS；同cycle/同action替换均验证 |
| TCL `onAdFilled` 同步替换串轮/无终态 | exact owner/request/params 隔离、短提交锁、关闭门及合法 wrapper fork 亲缘绑定 | Closure2 新实机5组/10会话 PASS：三次20s替换、两个8s Flow restart；每会话终态/完整tracking/晚期资源清理 |
| TCL 短片跳过 quartile 窗口 | 原native真实进度跨过旧上界后，调用原 EventHandler 补 HTTP tracking + BI；原去重及单返回 AdsEvent 语义保持 | 原8秒素材 SDK3 + Flow3 + app2，共8/8实机 PASS；原生确定性测试15项、既有恢复测试3项 PASS |
| app 完成上报持久化（追加批准） | 仅 `Hq008AdReporter.reportCompleted` 增加 `durable=true`；复用原完整 JSON、队列、worker，未改KEEP策略 | 两引擎2组/4会话实际PASS；各6次真实503跨30s backoff，15/13字段原payload逐字段相等，原key消费与健康新请求200独立验证 |

生产改动为已批准的15个文件；临时宿主、夹具和验证脚本留在 build 测试目录。TC 生成/同步状态逻辑未修改；CMP 文件的改动仅限已批准的待补 ACK 身份清理。

## 实际已通过的主要套件及时间边界

| 套件 | 结果 / 证据范围 |
|---|---|
| 最新追加 app 修复之后的七项单测 | 新执行847项，0失败/0错误/0跳过；归档XML独立重算一致，reporter SHA与最终源码一致，引用 `/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/final-source-regression-post-app/summary.json`，未复用旧结果 |
| 三层×双引擎普通播放、真实210秒长播 | 基线六条普通路径、六次长播均有设备 PASS；属于各自冻结版本，不声称在最终app持久化APK全量再跑 |
| 公共网络入口异常/超时/取消 | 10/10 PASS；未复现“网络超时静默漏回调” |
| 启动及真实播放后的buffer guard | 12/12 PASS；早期未到首帧的无效条件保留，后来使用真实首帧与进度平台条件补测 |
| patch非法JSON/缺URL/拒绝/redirect等 | 24/24 PASS |
| 非debuggable R8消费包 | 基线4/4 PASS；新SDK亦有实际minified宿主回归，但不冒称四个旧R8用例都重跑在新app版本 |
| 真实signal/overlay gate | 5/5 PASS |
| Flow生命周期 | 8/8 PASS；正常新SDK/Flow、wrapper/pod/inline-empty另有实际回归 |
| Google音量 six transition gaps | 六条真实API 0→1/1→0与系统7→6→7触发原生OM；同一批capture经后验v2严格复验6/6 PASS，系统音量逐条恢复 |
| independent 路径的短片audit-off与授权业务播放 | 修正测试GAM源条件后2/2 PASS；旧错误条件及raw保留 |
| app 原未持久化问题留证 | 两引擎均真实完成后4次503、旧payload未入队、PID重启、新广告成功而旧记录未补；这是修复前证据，不是修复后验收 |

完整tracking按真实served VAST的每个URL/slot验证；20秒VAST同一个事件有4个合法slot，不能误判为重复事件。早期5组验收失败及对应原raw/freeze均保留；Closure2以新冻结合同和新的真实run重新验证。音量v2只修复合法 `OmidSupported[true]` 诊断及完整HTTP audit的解析适配，明确是不可变六次真机capture的后验复验，**不是新跑六次设备**。

## 明确保留的未验证/未修改边界

1. **真实 Google UMP UI 未验证成功。** 结果边界注入、生产manager处理、重试/旧ACK屏障和真实UMP错误回调已测试，但不能代替原服务端同意/拒绝表单成功。
2. **原厂 IMA 的最终 GAM RDID 未直接观察。** 18个稳定样本来自官方公开 GMS `AdvertisingIdClient` 与只读 Settings，覆盖双PID和peer重启；没有原厂IMA广告request及最终GAM URL，不能说“IMA任何情况下绝不会改变RDID”。本次修复仅针对同包同来源的持久ID被缓存分裂，不更改合法ID来源/重置语义。
3. **app队列仍是原 `KEEP`。** 同key新payload恰逢worker退出的调度窗口、队列跨进程并发写未由本次有限app回归覆盖，也未擅自顺带修改；这是静态未覆盖边界，不冒称实际设备已复现。
4. **CMP待补保存仍是动作状态，不是完整原上报快照。** 字段为cycle/action/generation，没有新增TC字符串和完整payload存储。ACK修复不能被描述为“完整consent payload持久化”。
5. **短片修复不新增 SDK quartile 枚举分发。** 它补原HTTP tracking和原BI，但保持原单返回AdsEvent；跳窗时不额外制造 `onEvent(FIRST_QUARTILE)`。本轮“quartile通过”指已约定的tracking/BI，不指所有私有枚举通知已重构。
6. **同包与不同包不同合同。** RDID16组合验证同包双进程和四入口，不承诺不同包名的私有files目录共用一个锁或同一fallback ID。

## 主要证据位置

- 源码授权与SHA：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/source-scope-final-post-app.json`（15个批准文件SHA匹配，550基线内10个已知变化、0越界）。
- 新app原payload持久/重启补报：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/app-completed-durable-repair/device-regression/evidence-appdurable1/summary.json`（两引擎/4会话PASS）。
- 最后七项单测：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/final-source-regression-post-app/summary.json`（847项PASS）。
- 短片8次：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/quartile-repair/regression/results-device1/summary.json`。
- 替换/重启5组：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/closure-retest-quartile2/results/summary.json`。
- 音量6条后验适配：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/om-volume-dynamic-retest-v2/results/summary.json`。
- Flow补报：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/flow-completed-repair/queue-v2/device-regression/d124-durable-queuev2-final--summary.json`。
- CMP ACK：`/Users/zy/Documents/Codex/ad-app/build/tcl-cmp-ack-repair/device-evidence-003/summary.json`。
- RDID修复后16组合：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/rdid-repair-candidate/device-matrix/d124-rdidrepair1-mp16/summary.json`。不要误引旧 `multiprocess/summary.json` 的16个失败。
- RDID70：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/postrepair-playback/identity70-v2-result.json`；app12故障恢复：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/postrepair-app-identity/evidence/summary.json`。
- 原官方GMS18样本：`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/stock-identity-results/device-summary.json`。

## 最终收口清单

- [x] 两份新app APK逐SHA绑定，两引擎旧完整payload真实入队、真实换PID、原payload200、对应key清理、健康新请求和晚期资源均通过。两行结果各68个原始证据文件，共136个SHA独立复核无漂移。
- [x] 新追加修复后的七项单测实际完成，归档XML重新计数847项，0失败/0错误/0跳过，不复用旧结果冒充。
- [x] 15文件源码SHA与批准范围更新，550基线内10个已知变化、0越界；TC仍等于ACK候选，两个原vendor media AAR输入SHA均未改。
- [x] `/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/cleanup-final.json` 的9项检查全部为真：原代理和signal恢复、音量7/15且未静音、原launcher恢复、原19446 reverse保留、仅移除新增19448 reverse、19个新test包进程停止、fixture19448服务停止；未卸载、未清应用数据。

最终结论：**本轮已列出的有限验证清单与七项批准修复已完成验收；仍有上述明确边界。** 没有依据宣称所有可能场景100%通过。


## 离线复验入口

```sh
/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/verify-final.sh
```

此入口不调用设备、网络、Gradle或worker；重新读取原始证据，复验当前四组独立验收、847项归档XML与15个授权源码SHA。当前版本结果可刷新；历史v1失败和所有设备raw/freeze均保留。离线总结果位于 `build/device124-validation-20261107/offline-final-reverify/`，执行日志为 `offline-final-acceptance.log`。
