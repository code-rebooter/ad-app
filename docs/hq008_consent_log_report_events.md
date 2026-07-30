# hq008 `consent-log-report` 事件说明

本文档用于说明客户端上报到：

- `POST https://api.kytira.cc/api/v2/ad/consent-log-report`

的字段含义，以及当前版本所有可能出现的 `event_type` 和 `event_message` 取值格式。

固定请求字段：

- `channel_id`
- `mac`
- `ad_version`
- `event_type`
- `event_message`
- `ad_log`（可选）

说明：

- `event_type` 是事件名，便于后台聚合统计
- `event_message` 是事件补充信息，便于排查单次链路
- `ad_log` 是结构化诊断 JSON，当前用于记录 TCL CMP SDK 内部接口的原始请求/响应，以及 LSAP AAR 网络和运行时总闸诊断
- `event_message` 当前最长会截断到 512 字符
- 下文中的 `xxx=...` 为固定格式字符串，不是 JSON

`ad_log` 当前会出现在以下 TCL CMP SDK 内部接口相关事件中：

- `CONSENT_STATUS_START` / `CONSENT_STATUS_RESULT` / `CONSENT_STATUS_FAIL`
- `CAMPAIGN_REQUEST_START` / `CAMPAIGN_REQUEST_SUCCESS` / `CAMPAIGN_REQUEST_SUPPRESSED` / `CAMPAIGN_REQUEST_FAIL`
- `USER_ACTION_START` / `USER_ACTION_SUCCESS` / `USER_ACTION_FAIL`
- `AD_SDK_HTTP_CAPTURE`

`ad_log` JSON 主要字段：

- `source`: 固定为 `tcl_cmp_sdk`
- `api`: `getTCString` / `getCampaign` / `userAction`
- `url` / `path`: TCL CMP SDK 实际请求地址
- `rawRequest`: SDK 请求体原文，最长保留 4096 字符
- `rawResponse`: SDK 响应体原文，最长保留 4096 字符
- `requestLength` / `responseLength`: 原始文本长度
- `requestTruncated` / `responseTruncated`: 是否被截断
- `durationMs`: 接口耗时
- `success`: 本次诊断记录对应的请求是否成功
- `responseCode` / `responseMsg` / `dataIsNull`: 从响应中解析出的摘要
- `campaignId` / `vendorListVersion`: campaign 响应存在有效 data 时的摘要
- `errorType` / `errorMessage`: 请求或解析失败时的异常信息
- `sdk_network_logs`: LSAP AAR patched 出口采集到的网络、native、Dex 和运行时总闸事件列表，仅 `AD_SDK_HTTP_CAPTURE` 使用

## 核心流程说明

`consent-popup` 不是无条件请求的。

客户端会先进入 CMP 门禁，并刷新 CMP 状态，计算本轮是否需要继续处理 CMP：

- 初始化阶段会调用 SDK `loadPopState` 获取 `needShowPop`
- 广告门禁阶段会先请求远端 consent status，并在本地 consent 无效、有新 campaign、或本地标记有新 campaign 时请求远端 campaign
- 广告门禁阶段最终用 `shouldFetchCampaign && !suppressDecisionFlow && campaignSeedAvailable` 作为本轮是否继续 popup 决策的判断
- 如果最终判断本轮不需要 popup 决策，则会上报 `CMP_GATE_SKIP`，不会请求我方 `consent-popup`
- 如果远端 campaign 返回频控等抑制结果，则会上报 `CAMPAIGN_REQUEST_SUPPRESSED`，随后进入 `CMP_GATE_SKIP`，不会请求我方 `consent-popup`
- 只有门禁判断本轮需要 popup 决策时，才会调用我方 `consent-popup`

简化主链路：

1. 请求 `flow-control`
2. 进入 CMP 门禁
3. 刷新远端 consent status / campaign
4. 门禁判断是否需要继续 popup 决策
5. 需要弹窗时，请求我方 `consent-popup`
6. popup 返回动作后，客户端按动作分发
7. 对 `ACCEPT_ALL` / `REJECT` / `SAVE_SETTINGS`：生成或复用 TC String，执行 SDK CMP 动作，然后补发 `user/action`，再请求我方 `consent-report`
8. 对 `MAYBE_LATER`：不生成终态 TC String，直接尝试 SDK MaybeLater 或补发 `user/action`，成功后请求我方 `consent-report`
9. 对 `SKIP_ALREADY_DECIDED`：只记录本轮已处理，不执行 SDK 动作、不补发 `user/action`、不请求 `consent-report`
10. CMP 流程结束后，请求 `authorize`

## 一、Flow Control 阶段

### `FLOW_CONTROL_START`

- `event_message` 格式：
  - `channelId=<channel_id>`
- 含义：
  - 开始请求 `flow-control` 接口

### `FLOW_CONTROL_RESULT`

- `event_message` 格式：
  - `enabled=true`
  - `enabled=false`
- 含义：
  - `flow-control` 接口正常返回，并记录开关结果

### `FLOW_CONTROL_FAIL`

- `event_message` 格式：
  - `<error message>`
- 含义：
  - `flow-control` 接口请求失败

### `AAR_SDK_GATE_CHANGED`

- `event_message` 格式：
  - `source=flow_control,previous=false,enabled=true`
  - `source=flow_control,previous=true,enabled=false`
  - `source=flow_control_fail,previous=true,enabled=false`
- 含义：
  - 仅 LSAP 三渠道可能出现。
  - `flow-control` 结果触发 LSAP AAR 运行时总闸状态变化。
  - `enabled=false` 后，patched AAR 的网络、WebView、UDP、native/Dex Java 入口和新定时调度会被运行时总闸拦截。
  - 请求失败沿用现有业务策略按关闭处理，因此也可能触发 `source=flow_control_fail`。
  - 如果本次结果和上一次总闸状态一致，不会上报该事件。

## 二、CMP 门禁阶段

### `CMP_GATE_START`

- `event_message` 格式：
  - `adType=FLOATING,hidden=true`
  - `adType=FLOATING,hidden=false`
- 含义：
  - 开始进入 hq008 的 CMP 门禁流程

### `CMP_GATE_READY`

- `event_message` 格式：
  - `consentLength=<number>`
- 含义：
  - CMP 初始状态已就绪，准备刷新/检查 SDK 与远端 CMP 状态
  - 该事件之后不代表一定会请求 popup，还要经过广告门禁阶段的远端 status/campaign 判断

### `CMP_GATE_FINISH`

- `event_message` 格式：
  - `consentLength=<number>`
- 含义：
  - CMP 决策流程已结束，准备进入后续授权流程

### `CMP_GATE_STOP`

- `event_message` 可能值：
  - `reason=flow_control_fail`
  - `reason=flow_control_disabled`
- 含义：
  - CMP 门禁流程提前结束
  - `flow_control_fail`：`flow-control` 接口失败
  - `flow_control_disabled`：`flow-control` 返回关闭

### `CMP_GATE_SKIP`

- `event_message` 可能值：
  - `reason=sdk_no_popup_needed`
  - `reason=campaign_seed_missing`
- 含义：
  - 当前 CMP 门禁判断本轮不需要再次处理 CMP / 不需要 popup 决策
  - 出现该事件时，本轮不会请求我方 `consent-popup`
  - 该事件也可能出现在远端 campaign 抑制后，此时前面会有 `CAMPAIGN_REQUEST_SUPPRESSED`
  - `campaign_seed_missing` 表示本轮需要先拿到 TCL CMP campaign seed，但远端未返回有效 campaign data，因此跳过 popup 决策

## 三、远端 Consent Status 阶段

### `CONSENT_STATUS_START`

- `event_message` 格式：
  - `campaignVersion=<number>`
  - `campaignVersion=-1`
- 含义：
  - 开始请求远端 CMP consent 状态
  - `-1` 表示本地没有可用的 campaignVersion

### `CONSENT_STATUS_RESULT`

- `event_message` 格式：
  - `code=<number>,hasData=true`
  - `code=<number>,hasData=false`
- 含义：
  - 远端 CMP consent 状态接口返回结果
  - `code` 可能来自响应体中的 `code` 或 `error_code`
  - `hasData=true` 表示返回里存在 `data`
  - `hasData=false` 表示没有 `data`

### `CONSENT_STATUS_FAIL`

- `event_message` 格式：
  - `<error message>`
- 含义：
  - 远端 CMP consent 状态请求失败

## 四、远端 Campaign 阶段

### `CAMPAIGN_REQUEST_START`

- `event_message` 格式：
  - `forcePopup=false`
- 含义：
  - 开始请求远端 CMP campaign 种子

### `CAMPAIGN_REQUEST_SUCCESS`

- `event_message` 格式：
  - `campaignId=<number>,vendorListVersion=<number>`
- 含义：
  - 远端 CMP campaign 种子获取成功
  - 这不代表一定会请求我方 `consent-popup`，后续仍要看广告门禁最终算出的 `needShowPop`/`cmpNeedShowPop`

### `CAMPAIGN_REQUEST_SUPPRESSED`

- `event_message` 格式：
  - `reason=<reason>`
- 含义：
  - 远端 campaign 明确表示本轮不需要继续走展示 / 决策
  - 出现该事件时，客户端会抑制后续 popup 决策流程，不请求我方 `consent-popup`
- 当前已知 `reason`：
  - `frequency_control`

### `CAMPAIGN_REQUEST_FAIL`

- `event_message` 格式：
  - `<error message>`
- 含义：
  - 远端 CMP campaign 请求失败

## 五、Popup 请求阶段

### `POPUP_REQUEST_START`

- `event_message` 格式：
  - `consent_expired=true`
  - `consent_expired=false`
- 含义：
  - 开始请求 `consent-popup`
  - 只有广告门禁判断本轮需要 popup 决策时，才会出现该事件

### `POPUP_REQUEST_SUCCESS`

- `event_message` 格式：
  - `action=ACCEPT_ALL`
  - `action=REJECT`
  - `action=SAVE_SETTINGS`
  - `action=MAYBE_LATER`
  - `action=SKIP_ALREADY_DECIDED`
  - `action=EMPTY`
- 含义：
  - `consent-popup` 接口正常返回
  - `action=EMPTY` 表示接口回调成功，但没有返回有效动作

### `POPUP_REQUEST_FAIL`

- `event_message` 格式：
  - `<error message>`
- 含义：
  - `consent-popup` 接口请求失败

### `POPUP_CALLBACK_FAIL`

- `event_message` 格式：
  - `<error message>`
- 含义：
  - `AdConfigManager` 收到 `popup` 失败回调
  - 这是 popup 进入业务分发前的失败记录

## 六、Popup 动作分发阶段

### `POPUP_ACTION_ACCEPT_ALL`

- `event_message` 固定值：
  - `payload=false`
- 含义：
  - popup 返回 `ACCEPT_ALL`，客户端准备执行该动作

### `POPUP_ACTION_REJECT`

- `event_message` 固定值：
  - `payload=false`
- 含义：
  - popup 返回 `REJECT`，客户端准备执行该动作

### `POPUP_ACTION_SAVE_SETTINGS`

- `event_message` 格式：
  - `purpose=<number>,vendor=<number>`
- 含义：
  - popup 返回 `SAVE_SETTINGS`
  - `purpose` 为目的同意列表数量
  - `vendor` 为 vendor 同意列表数量

### `POPUP_ACTION_MAYBE_LATER`

- `event_message` 固定值：
  - `payload=false`
- 含义：
  - popup 返回 `MAYBE_LATER`，客户端准备执行该动作

### `POPUP_ACTION_SKIP_ALREADY_DECIDED`

- `event_message` 固定值：
  - `payload=false`
- 含义：
  - popup 返回 `SKIP_ALREADY_DECIDED`

### `POPUP_ACTION_UNKNOWN`

- `event_message` 可能值：
  - `EMPTY`
  - `<unknown action string>`
- 含义：
  - popup 返回了空动作或未知动作

### `POPUP_ACTION_FALLBACK`

- `event_message` 格式：
  - `fallback=MAYBE_LATER,reason=request_error:<error>`
  - `fallback=MAYBE_LATER,reason=unknown_action:EMPTY`
  - `fallback=MAYBE_LATER,reason=unknown_action:<action>`
- 含义：
  - popup 阶段未得到可执行动作，客户端兜底改执行 `MAYBE_LATER`
- 注意：
  - 只有已经进入 popup 阶段后才会触发 fallback
  - 如果本轮 CMP 门禁判断不需要 popup 决策，则不会请求 popup，也不会触发 popup fallback
- 当前触发场景：
  - popup 请求失败
  - popup 返回空动作
  - popup 返回未知动作

## 七、CMP 决策执行阶段

### `CMP_PROVIDER_MISSING`

- `event_message` 固定值：
  - `remoteDecisionProvider=null`
- 含义：
  - 未配置远端 CMP 决策提供器

### `CMP_DECISION_EMPTY`

- `event_message` 固定值：
  - `provider_callback=null`
- 含义：
  - 远端 CMP 决策回调为空

### `CMP_DECISION_SKIPPED`

- `event_message` 可能值：
  - `action=<action>,reason=sdk_no_popup_needed`
  - `action=SKIP_ALREADY_DECIDED`
- 含义：
  - 本轮 CMP 动作被跳过
  - 第一种表示 SDK 判断当前无需弹窗
  - 第二种表示远端明确返回 `SKIP_ALREADY_DECIDED`

### `CMP_SEED_MISSING`

- `event_message` 格式：
  - `action=<action>`
- 含义：
  - 已拿到远端动作，但本地缺少执行该动作所需的 campaign seed

### `CMP_DECISION_BUILD_FAIL`

- `event_message` 格式：
  - `action=<action>`
- 含义：
  - 已拿到远端动作，但构建本地执行配置失败

### `CMP_DECISION_UNKNOWN`

- `event_message` 格式：
  - `action=<action>`
- 含义：
  - 收到未知的 CMP 动作

## 八、SDK 动作执行阶段

### `SDK_ACTION_START`

- `event_message` 格式：
  - `reportAction=<action>,sdkAction=<action>,campaignId=<number>`
- 含义：
  - 开始执行本轮 SDK CMP 动作
  - `reportAction` 是对外记录/上报动作
  - `sdkAction` 是 SDK 实际执行动作
- 注意：
  - `ACCEPT_ALL` / `REJECT` / `SAVE_SETTINGS` 会出现该事件
  - `MAYBE_LATER` 当前不会上报 `SDK_ACTION_START`，它会直接进入 `USER_ACTION_*`
  - `SKIP_ALREADY_DECIDED` 不会出现该事件

### `SDK_ACTION_PREPARE_FAIL`

- `event_message` 格式：
  - `reportAction=<action>,sdkAction=<action>,reason=tc_string_empty`
- 含义：
  - 执行动作前，TC String 生成失败，导致动作无法正常继续

## 九、`user/action` 阶段

### `USER_ACTION_START`

- `event_message` 格式：
  - `action=<action>,campaignId=<number>,hash=<hash>`
- 含义：
  - 开始补发 / 执行 SDK `user/action`
- 注意：
  - `ACCEPT_ALL` / `REJECT` / `SAVE_SETTINGS` 在 SDK 动作准备完成后进入这里
  - `MAYBE_LATER` 直接进入这里
  - `SKIP_ALREADY_DECIDED` 不进入这里

### `USER_ACTION_SUCCESS`

- `event_message` 格式：
  - `action=<action>,campaignId=<number>,hash=<hash>`
- 含义：
  - `user/action` 成功

### `USER_ACTION_FAIL`

- `event_message` 格式：
  - `action=<action>,campaignId=<number>,hash=<hash>,error=<error message>`
- 含义：
  - `user/action` 失败

### `USER_ACTION_SKIPPED`

- `event_message` 格式：
  - `reason=dedup,action=<action>,hash=<hash>`
- 含义：
  - 当前动作命中去重，跳过重复上报
- 目前有两种来源：
  - `MAYBE_LATER` 命中去重
  - 一般 `user/action` 补发命中去重

### `PENDING_USER_ACTION_FOUND`

- `event_message` 格式：
  - `action=<action>,campaignId=<number>,hash=<hash>`
- 含义：
  - 启动或刷新时发现本地存在待补偿的 `user/action`

## 十、`consent-report` 阶段

### `CONSENT_REPORT_START`

- `event_message` 格式：
  - `action=<action>`
- 含义：
  - 开始请求 `consent-report`
- 注意：
  - `user/action` 成功或命中去重后，才会进入 `consent-report`
  - `SKIP_ALREADY_DECIDED` 不进入 `consent-report`

### `CONSENT_REPORT_SUCCESS`

- `event_message` 格式：
  - `action=<action>`
- 含义：
  - `consent-report` 请求成功

### `CONSENT_REPORT_FAIL`

- `event_message` 格式：
  - `action=<action>,error=<error message>`
- 含义：
  - `consent-report` 请求失败

### `PENDING_CONSENT_REPORT_FOUND`

- `event_message` 格式：
  - `action=<action>,cycleKey=<cycleKey>`
- 含义：
  - 启动或刷新时发现本地存在待补偿的 `consent-report`

## 十一、Authorize 阶段

### `AUTHORIZE_START`

- `event_message` 格式：
  - `requestId=<request_id>`
- 含义：
  - 开始请求 `authorize`

### `AUTHORIZE_RESULT`

- `event_message` 格式：
  - `requestId=<request_id>,authorized=true,hidden=true`
  - `requestId=<request_id>,authorized=false,hidden=true`
  - 以及其他布尔组合
- 含义：
  - `authorize` 接口正常返回后的原始授权结果

### `AUTHORIZE_FAIL`

- `event_message` 格式：
  - `requestId=<request_id>,error=<error message>`
- 含义：
  - `authorize` 请求失败

### `AUTHORIZE_CALLBACK_FAIL`

- `event_message` 格式：
  - `<error message>`
- 含义：
  - `AdConfigManager` 收到 `authorize` 失败回调

### `AUTHORIZE_CALLBACK_EMPTY`

- `event_message` 固定值：
  - `dto=null`
- 含义：
  - `authorize` 回调到业务层时响应对象为空

### `AUTHORIZE_DENIED`

- `event_message` 格式：
  - `requestId=<request_id>`
- 含义：
  - `authorize` 明确返回拒绝，不下发广告

### `AUTHORIZE_ALLOWED`

- `event_message` 格式：
  - `requestId=<request_id>,hidden=<true|false>`
- 含义：
  - `authorize` 明确返回允许，并记录最终隐藏模式

## 十二、LSAP AAR 诊断事件

### `AD_SDK_HTTP_CAPTURE`

- `event_message` 格式：
  - `requestId=<request_id>,eventCount=<number>,criticalEventCount=<number>,terminalReason=<reason>`
- `ad_log` 主要结构：
  - `schema_version`
  - `request_id`
  - `terminal_reason`
  - `event_count`
  - `critical_event_count`
  - `sdk_network_logs`
- 含义：
  - 仅 LSAP 三渠道可能出现。
  - 一次 LSAP 广告流程结束时，把 patched AAR 出口采集到的网络、WebView、UDP、native、Dex 和运行时总闸事件合并到一条诊断日志。
  - 运行时总闸关闭后，`sdk_network_logs` 内可能出现 `AAR_SDK_GATE_BLOCKED` 或 `AAR_HTTP_BLOCKED`。
  - 如果 payload 过大，会按 `gzip+base64` 形式写入压缩字段。

## 十三、排查建议

后台对账时，建议优先按下面顺序串联：

1. `FLOW_CONTROL_*`
2. LSAP 三渠道同时看 `AAR_SDK_GATE_CHANGED`
3. `CMP_GATE_*`
4. `CONSENT_STATUS_*`
5. `CAMPAIGN_REQUEST_*`
6. `CMP_GATE_SKIP` 或 `POPUP_REQUEST_*`
7. `POPUP_ACTION_*`
8. `SDK_ACTION_*`
9. `USER_ACTION_*`
10. `CONSENT_REPORT_*`
11. `AUTHORIZE_*`
12. LSAP 三渠道广告流程结束后看 `AD_SDK_HTTP_CAPTURE`

关键判断：

- 如果出现 `CMP_GATE_SKIP`，说明 CMP 门禁判断本轮不需要 popup 决策，本轮不应期待 `POPUP_REQUEST_*`
- 如果出现 `CAMPAIGN_REQUEST_SUPPRESSED`，说明远端 campaign 抑制了本轮决策，本轮不应期待 `POPUP_REQUEST_*`
- 如果出现 `CMP_GATE_SKIP reason=campaign_seed_missing`，说明缺少执行 CMP 动作必需的 campaign seed，本轮不应期待 `POPUP_REQUEST_*` 和 `CONSENT_REPORT_*`
- 如果出现 `POPUP_REQUEST_START`，才表示客户端已经正式调用我方 `consent-popup`

重点关注这几类异常闭环：

- 出现 `POPUP_REQUEST_FAIL` 后，是否紧接着出现 `POPUP_ACTION_FALLBACK`
- 出现 `POPUP_ACTION_UNKNOWN` 后，是否紧接着出现 `POPUP_ACTION_FALLBACK`
- 出现 `USER_ACTION_FAIL` 后，后续是否出现 `PENDING_USER_ACTION_FOUND`
- 出现 `CONSENT_REPORT_FAIL` 后，后续是否出现 `PENDING_CONSENT_REPORT_FOUND`
- LSAP 三渠道如果后台关闭 flow-control，应看到 `AAR_SDK_GATE_CHANGED enabled=false`，随后 AAR 后续出口应在 `AD_SDK_HTTP_CAPTURE` 的 `sdk_network_logs` 内出现拦截事件
