# TCL ACCEPT -> REJECT: cause, production reachability and impact

Read-only investigation. Original Kotlin offline probe: 19 assertions PASS. No production source edits, ADB, device actions, Gradle or online requests. Historical fixtures are not a production end-to-end reproduction.



---

## Cause

# TC REJECT 旧值：原因侧只读审计与原代码离线执行

## 执行范围

- 未改业务源码、历史原始记录、冻结清单或验收器；未运行 Gradle / ADB / 网络 / Android / 原生 SDK。
- 仅在本目录生成 Kotlin 夹具、编译并执行。
- 原生产 `Hq008CmpManager.kt` SHA：`c3ffa1a5fcf4361eb269c8d5b82c73a0d79156f0dbd0f3ea574f5c07a731d7f9`，前后未变。
- `run_offline_probe.py` 从实际生产文件逐字抽取原轮询、三个谓词、持久回写与文件序列化方法；代码起始行与方法 SHA 见 `extracted-methods.json`。
- 输入来自旧 ACCEPT、旧 REJECT、ACK 后 REJECT 的真实归档快照，原文件 SHA 见 `probe-result.json`。

## 确定性定位

1. `tryReflectiveSilentAction` :2945–2963 在触发原 SDK 动作后轮询本地状态。
2. `waitForReflectiveSdkActionResult` :3003–3049 优先读 `consent.0`；兼容性失败后，仍尝试 `isReflectiveSdkActionStateAdvanced`。
3. **`:3117–3118` 的 Advanced 条件只要 campaign 相同就成立**，并没有要求 action、TC 或生成时刻真的变化：

```kotlin
return (tcChanged || createdAtChanged || campaignMatched) &&
    (campaignMatched || campaignUnknown)
```

在 `campaignMatched=true`、旧TC非空时，即使 `tcChanged=false` / `createdAtChanged=false`，它仍返回 true。若 SDK 异步动作尚未改完文件，第一份旧 ACCEPT 就会被当作“动作已推进”而提前接收。
4. `executeSilentDecision` :1596–1605 优先使用该返回 TC，而非先前已经按 REJECT 列表生成的 `dynamicTcString`。
5. `persistSilentConsentState` :1413–1423 无条件将传入 TC 同步到默认 prefs / manager / seed；当 SDK 持久化失败时，`:1405–1406` 还会把相同 TC 写回 `consent.0`。

因此可以出现 `action=REJECT`、用途列表为空，而 TC 字符串仍为 ACCEPT；若手写 fallback 最后完成，磁盘文件也可被改成旧 TC。

另一个传播条件：`isReflectiveSdkActionStateCompatible` :3122–3135 只比较 action / campaign / 各列表，不验证 TC 编码是否与这些列表一致。ACK 后实际归档的“REJECT 元数据 + 旧 ACCEPT TC”文件也会被它判 compatible。不能简单认为三个字符串相等就代表拒绝语义正确。

## 已实际执行：19 项断言 PASS

证据：`probe-result.json`、`probe.stdout`、`compile.stdout/stderr`。

- 原始 ACCEPT 与 REJECT TC 确实不同；目标 action 为 REJECT、同意列表为空。
- 旧 ACCEPT 既不 Compatible，也没有产生 Observed 增量；**Advanced 却因同 campaign 为 true**。
- 序列输入 `[归档旧ACCEPT, 归档新REJECT]`，执行**原始 Kotlin 轮询函数**，只读一次、未 sleep，就返回旧 TC；第二份正确 REJECT 根本未读取。
- 不同 campaign 负例、空旧TC负例均等待第二次采样（100ms）后返回正确 REJECT。
- 原 SAVE_AND_EXIT 的前置 action guard 仍保留，不能用此次证明声称该路径也同样立即接收旧 ACCEPT。
- 执行原始持久函数，在明确的 `native persistence=false` seam 下，模拟文件 / 默认prefs / manager / seed TC 都变成旧 ACCEPT，但 action 与用途列表仍是 REJECT / 空。
- ACK 后实际归档文件的 REJECT 元数据配旧 TC，确实被原 Compatible 判为 true。

### 夹具边界

Android clock、Looper、Context / preferences、文件读序列、SDK 原生持久化成功与否、日志是明确 seam。SilentConsentSeed 声明按原源码提取；GvlBean 仅为类型占位，所测原方法不使用其内部字段，本测试不执行 TC 编码或 SDK model 解析。action / TC / ID列表取实际归档。这里证明了原轮询与回写代码存在可确定复现的旧值采纳路径，**不是新 Android 端到端复测**。

## 与两轮真实记录的关系

- 旧 PID6695：REJECT 原 SDK user/action HTTP 与 SDK 文件已带新拒绝 TC，但 app manager / default / seed TC 仍为旧 ACCEPT；实际 trace 为 `storage=sdk`。
- ACK 后 PID6865：SDK user/action HTTP同样生成新拒绝 TC，但 app trace 为 `storage=manual_fallback`，settled file/default/manager 保持旧 ACCEPT。这与原 fallback 可以回写旧值的代码路径相容。
- 历史 trace 未记录每次轮询读到的完整快照与实际选择的 Compatible / Advanced / Observed 分支，故**不能断言某一行就是两次现场的唯一原因**。可确认的是“同 campaign 旧值可被提前接收”这条代码缺口和对应回写后果。
- fresh 独立包没有既有 ACCEPT TC，不满足“非空旧 TC + 相同 campaign”的触发条件，因此它的单次 REJECT / 重启读回通过，并不能排除此路径。

## 对线上现象的解释边界

这不是“REJECT 从来不成功”；触发至少需要已有非空旧状态、同 campaign、目标状态不同，且轮询抢在 SDK 完整更新之前。新安装 / 空TC、campaign真正变化或 SDK先写完时，可走正确结果。线上是否经由正常入口形成这个组合，以及广告请求最终从哪份状态取TC，须与 reachability / wire 两份审计合并，不能从这 19 项离线测试推断线上发生率、填充率或收益影响。



---

## Reachability

# ACCEPT→REJECT：夹具与线上路径可达性只读审计

范围：仅读取当前源码与原始设备证据；未运行 ADB、网络、设备、Gradle、回归脚本；未修改生产或旧证据。

## 结论

1. 原受控 ACCEPT→REJECT 不是完整线上门禁链的复现；它强行制造了同 campaign 第二次决策的资格。
2. 它仍调用了真实生产 decision 重载和 SDK 原动作，且没有在 REJECT 前清 TC，因此不能直接当作“纯测试假象”。一旦线上合法进入同一动作路径，状态读回/回写代码是共享的。
3. 正常“有效终态 TC、SDK 不要求再处理、skip_cmp 或门禁跳过”的路径不会每次广告都重复 ACCEPT→REJECT。这可以解释线上通常没有播放故障，但不是线上绝不受影响的证明。
4. 没有线上 SDK popup/consent-status/campaign/consent-popup 的响应及流程日志，当前不能证明该混合状态在生产自然发生过，也不能确认发生率。

## 线上实际入口

- `app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt:254-278`：`skip_cmp=true` 直接授权；false才等待 CMP ready并调用无 decision 参数的入口。
- `app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt:699-814`：先刷新 SDK `loadPopState`；若 SDK 不需弹窗且本地同意有效，`:723-730`直接结束，不调用后端决策；只有需要继续处理时再做远端状态/seed门禁、调用provider，并在`:806-810`调用decision重载。
- `:1077-1244`：`needsDecisionFlow = remote.hasNewCampaign || local.hasNewCampaign || localInvalid`；缺seed或campaign被suppress不会放行；本地无效但远端已有TC且没有新campaign时优先恢复远端TC，不执行新REJECT。
- `:817-961`：decision重载仍检查`cmpDecisionEligible`，可优先恢复pending SDK同步或remote TC；真正动作进入`:924 executeSilentDecision`。

## 测试强制制造的条件

`build/tcl-cmp-ack-repair/reject-readonly/host/tcl/com/smart/android/ad_app/Device124TclCmpControls.kt:90-105,147-172`（旧r7同一结构）：

- 每次action都重建外部 campaign seed：`actionType=ACCEPT_ALL`、campaignId=124、`currentTcString=null`、`hasNewCampaign=true`。
- 每次强写 ready=true、eligible=true、needShowPop=true；cycle直接写本run_id，而不是生产`:3243-3249`生成的campaign/expire/needShowPop组合。
- 清两个pending key，但不清manager TC、默认prefs TC或consent.0。
- 直接调用带decision的公开重载，不先走SDK loadPopState、远端consent status/campaign刷新和provider。
- TCL SDK user/action HTTP由测试interceptor实际观察后返回固定`{"code":100000,"data":{}}`，不具备真实后台的历史consent状态变更、时延与后续status一致性。

这些差异使已有ACCEPT状态可以立刻再次收到同campaign REJECT；不能将测试流称为线上完整路径。

注意：`currentTcString=null`本身也不是可直接归咎夹具的错误。生产`:2544-2577`构建REJECT seed时本来就把currentTcString设null、清purpose/vendor consent；一旦进入真正REJECT动作，此核心输入与测试有共同点。

## 原证据对比

| 记录 | 动作前状态 | 执行入口 | 动作后已观察 |
|---|---|---|---|
| early ACCEPT/REJECT | 同PID6695，先实际ACCEPT生成TC，再REJECT；每次测试强写资格/seed | decision重载→reflective AcceptEssential | REJECT动作和seed列表已变；manager/default仍旧ACCEPT，consent.0是拒绝；storage=sdk |
| ACK003 ACCEPT/REJECT | 同PID6865，先ACCEPT再REJECT；同样强制资格 | 同路径 | storage=manual_fallback，manager/default/file最后均旧ACCEPT串 |
| fresh REJECT | 新独立包动作前TC各处不存在，未先实际ACCEPT | 同路径 | 拒绝串一致；后续新PID normal init/getters一致 |

源码与原raw中的流程trace没有先出现上述`CMP_GATE_EVALUATED`/snapshot远端门禁，它们从夹具的public-decision直接进入`CMP_DECISION_START`。

## 线上可达的最小候选条件

必须同时满足：

1. 同进程/SDK状态中已有一个非空ACCEPT TC。
2. 正式flow-control没有skip_cmp，SDK/本地/远端门禁重新打开决策资格（例如new campaign、本地无效或过期；具体必须以实际SDK返回为准）。
3. 没有当前cycle的pending SDK sync，也没有转入“远端已有TC直接恢复”的分支。
4. 后端本次返回REJECT，进入真正`executeSilentDecision`。
5. SDK新动作尚未完成落盘时，app的反射观察/回写取得了旧TC。是否在合法完整门禁流程中确实发生，尚无本轮运行证据。

还有静态可达的失败组合：SDK snapshot刷新异常`:1067-1074`只继续回调，不将既有needShowPop置false；已有true状态，加local.hasNewCampaign=true，可使门禁继续。即使remote campaign获取失败，已有localSeed仍可作为refreshedSeed`:1190-1206`。这不是证明线上已发生，只说明不能因为正常skip路径存在就断言所有线上状态不可达。

## 为什么线上可能没有显著播放故障

生产`:419-421 canContinueAfterRemoteDecision()`恒为true；AdConfig在CMP回调后继续授权，不以REJECT动作作为TCL播放失败。即使TC内容与动作短时不一致，也不一定表现成黑屏/卡住/无广告。是否改变后续请求TC或后台状态，由wire消费链审计单独确认；不能把“有广告播放”当作TC一致性的证明。

## 仍未知

- 同时序、同初始状态但走完整线上门禁与真实SDK响应时是否仍能复现。
- 真正服务端对新campaign/已有TC/终态动作的策略；仓库源码无法代替线上响应。
- 真实分支最早接受旧TC的位置及概率，不能仅根据测试最后副本不同判唯一根因。



---

## Wire impact

# TCL ACCEPT→REJECT TC 差异：影响链只读核查

核查目录：`/Users/zy/Documents/Codex/ad-app/build/tc-reject-impact-20261107/wire/`。
本轮仅读取源码、既有设备归档和既有 APK 静态证据；仅在本目录新增报告/证据清单。未改业务源码，未运行 ADB、Gradle、安装或真实网络请求。

## 1. 结论

**这是“拒绝动作、SDK 本次对外上报已是拒绝，但 app 后续取到的本地 TC 仍可能是旧同意 TC”的状态分叉，不是 REJECT 后还能继续请求广告本身有问题。**

- **已确认的源码影响链**：app TCL 广告路径直接读取 manager 内存里的 TC；非空就传给原 TCL SDK 的 `RequestParams.gdprConsent`。最终 APK 中 TCL 原序列化方法继续输出 `gdprConsent`、`gdpr=1`、`gdprSource=CMP_TCL`，没有重新按拒绝列表覆盖这串 TC。
- **条件性后果**：如果 REJECT 分叉后的旧 TC 尚在 manager 内存中，且下一次 TCL 广告构造发生在它被正确刷新之前，后续发给 TCL address/tag 链的参数将携带旧 TC。该链不会因为后台已经收到 REJECT 动作而自动改用后台的 TC。
- **没有实测到的部分**：本轮没有 REJECT 分叉后紧接一次真实广告请求的出口抓包；因此不能宣称已经抓到实际发出的旧 `gdprConsent`，更不能宣称最终 Google GAM URL、填充率或收入已被影响。
- **范围**：已证实 app TCL 这条取值链有上述条件性影响；不能据此宣称 Google pure IMA、Google independent 或 Flow SDK 本身同样中招。

## 2. 后续 TCL 广告具体在哪里受影响

| 环节 | 实际行为 | 依据（当前源码行号） |
|---|---|---|
| 取 TC | `getConsentString()` 只返回 manager 的 `consentString` 内存字段；不在 getter 中重读 SDK 文件/default prefs | `/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt:415-416` |
| 构造广告参数 | 非空即 `.setGdpr("1").setGdprConsent(consent).setGdprSource("CMP_TCL")` | `/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt:730-759`，关键 `746-752` |
| 交给 TCL SDK | `Ad.get().begin(...).setAdType(WATERFALL).setRequestParams(buildRequestParams(...))` | 同文件 `415-420` |
| 原 SDK 序列化 | `generateBaseParams()` 将对应 getter 值放入 `gdprConsent/gdpr/gdprSource`；`generateVastTagKeyPairs()` 基于同一 base map 扩展；`generateVastTagRequestParams()` 对其 Gson JSON 化 | 下节最终 APK 的 3 个 REA 方法证据 |
| 地址返回后 patch | 仅对 address 返回的 `adsUrl` 走后续 patch；此处未看到把本地 TC 再次刷新后覆盖该请求参数的逻辑 | `/Users/zy/Documents/Codex/ad-app/app/src/tclRuntime/java/com/smart/android/ad_app/TclRuntimeBridge.kt:160-177` |

必须区分字段名：本链已经确认的是 **TCL 参数 `gdprConsent`**。Google URL 独立工具的对应字段名为 **`gdpr_consent`**（`/Users/zy/Documents/Codex/ad-app/ad-sdk-tcl/src/main/java/com/smart/android/tclad/GoogleAdTagParameters.java:51-55`）；两者不是同一位置。本报告未证明后台怎样将 TCL 请求的 `gdprConsent` 映射到最终 Google URL。

### APK/原始输入身份

最终既有 TCL APK：
`/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/app-completed-durable-repair/app-tcl-completed-durable1.apk`

SHA-256：`8d6e92f4d2fe3a9e066897f9e09d7b52be2907ee8e076b40733c2ecd447e2812`。

类：`com.tcl.ff.component.overseabasebusiness.requestparams.VastAdRequestParams`。

REA 完整规范化静态证据：
`/Users/zy/Documents/Codex/ad-app/build/tc-reject-impact-20261107/wire/REA_REQUEST_PARAMS_EVIDENCE.json`。

- `generateVastTagKeyPairs()`：`ev_6e7cffec791d8ae864fe7ee952ea4e3b909b9099c02ca2a15dc30bd2fb2f19db`
- `generateBaseParams()`：`ev_e65cfccfda9e8da9f0126a85bddb5abdd1ebb698aee713962b57cf7d9e17f996`
- `generateVastTagRequestParams()`：`ev_f8e70e80b88192358760d7b987d79da7bb83922355d76704b0380453fbc40b1f`

这是最终 APK 的静态方法证据，不是广告请求运行时抓包。原始 AAR 身份另见同目录 `INPUT_IDENTITY.json`：`ad-sdk-tcl/vendor/tcl-base-2.8.02.aar` 与 `ad-sdk-fusion/vendor/base/sdk.aar` 相同 SHA-256 `1e9660a0e335e8e87e98057f5a0df520791a9754a0eb47dff789f00f7ecb56de`；其中 `VastAdRequestParams.class` 相同 SHA-256 `553da94426a7c0c3d17e3a0d77e5819b4ee1f5cbc019bc1043ea38c6a8d55e65`。不能将原 AAR 与 APK 所有类完全一致混为一谈。

## 3. “后台看起来正常”与“本地仍旧”为什么能同时存在

### 我方 `consent-report`

`/Users/zy/Documents/Codex/ad-app/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt:69-95` 构造 `channel_id/mac/ad_version/android_sdk_version/consent_action` 与公共设备字段；这里没有 TC 字符串。因此后台显示 `consent_action=REJECT` 正常，不能证明下一次广告使用的 manager 内存 TC 已是拒绝。

### 原 TCL CMP SDK 的 `/device/user/action`

两份已归档出口请求 **确实包含了拒绝后的 TC**：

- `/Users/zy/Documents/Codex/ad-app/build/device124-validation-20261107/tcl-cmp-device-evidence/d124-tcl-cmp-final-tcl-cmp-reject/local-events.jsonl:14`
- `/Users/zy/Documents/Codex/ad-app/build/tcl-cmp-ack-repair/device-evidence-003/d124-cmp-ack-fixed-003-tcl-cmp-reject/local-events.jsonl:14`

URL：`https://cmp-ads.api.leiniao.com/global-consentmanage-api/sdk/device/user/action`。

两笔的核心内容相同：

```json
{
  "consentJson": {
    "purposesConsent": [],
    "purposesLITransparency": [1, 2],
    "vendorsConsent": [],
    "vendorLegitimateInterest": [1]
  },
  "tcString": "CQtUCQAQtUCQAHtABBZHBACgAAAAAMAAAA5QAAAABQAA.IAAo",
  "actionCode": 1
}
```

这说明“SDK 在本次 REJECT 动作里根本没生成/没发送拒绝 TC”的说法不成立。它不能抵消后续 app 从另一个本地状态读取旧 TC 的问题。精简提取见同目录 `SDK_REJECT_HTTP_EVIDENCE.json`。

**证据边界**：两笔均来自测试夹具，SDK 已构造真实出口 HTTP 请求，但网络由测试 interceptor 接管并回成功；它们证明 SDK 生成的请求体，不证明线上服务端已收到或落库。

## 4. 为什么不能顺手扩大到 Google/Flow

- Google flavor 共享业务源时明确排除 hq008 的 `Hq008CmpManager.kt` 和 `AdManagerImpl.kt`：`/Users/zy/Documents/Codex/ad-app/app/gradle/google-url-tcl.gradle:6-12`。Google 两种执行路径另取各自 source set：同文件 `26-54`。
- Google 路径的 getter 委托 `GoogleUmpConsentManager`：`/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt:284-285`；其读取 Google 路径自身包内 default prefs 的 `IABTCF_TCString`：`/Users/zy/Documents/Codex/ad-app/app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleUmpConsentManager.kt:407-412`。是否存在其他 TC 问题需另证，不能用本 TCL manager 分叉作证明。
- Flow SDK 使用宿主提供的 boolean `Hq008ConsentGate`，授权后交宿主广告 callback；该段不直接调用 app 的 TCL CMP manager，也不自行读取此旧 TC：`/Users/zy/Documents/Codex/ad-app/hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/FlowRuntime.java:195-227,265-272`。宿主若自行把旧 TC 传给播放器属于宿主接入条件，不是已证实的 Flow 内部自动行为。

## 5. 对用户当前问题的准确回答

它首先可能影响的是 **特定 ACCEPT→REJECT 时序之后的下一笔 app TCL 广告请求携带哪一份 TC**，不是必然造成广告不播，也不是已经证明线上普遍异常。继续请求/展示广告是当前既定设计：hq008 TCL `canContinueAfterRemoteDecision()` 固定返回 `true`（`Hq008CmpManager.kt:419-420`），本轮不将其列为待改问题。

在尚未量到实际广告出口和服务端消费结果前，只能确认参数错误的可达链，不能把它延伸成已发生的填充率、收益或最终 Google URL 异常。线上没有可见播放问题，与这条参数状态分叉并不矛盾；但线上没有症状也不能证明该参数不会错。

## 6. 可复核清单

- 当前所引源码、既有 APK、两份原始归档 SHA-256：`/Users/zy/Documents/Codex/ad-app/build/tc-reject-impact-20261107/wire/SOURCE_IDENTITY.json`
- 两份原 CMP SDK REJECT 出口请求摘要/原文件行号：`/Users/zy/Documents/Codex/ad-app/build/tc-reject-impact-20261107/wire/SDK_REJECT_HTTP_EVIDENCE.json`
- 原始 AAR 和类字节身份：`/Users/zy/Documents/Codex/ad-app/build/tc-reject-impact-20261107/wire/INPUT_IDENTITY.json`
- 最终 APK 的 3 个 REA 完整方法证据：`/Users/zy/Documents/Codex/ad-app/build/tc-reject-impact-20261107/wire/REA_REQUEST_PARAMS_EVIDENCE.json`
