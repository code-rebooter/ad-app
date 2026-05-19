# TCL CMP SDK 接口流程梳理

本文基于当前接入的 `adsdk_overseas_cmp_sdk-2.8.02` 反编译代码整理，用于说明 TCL CMP SDK 在什么情况下会调用哪些接口，以及请求 URL、请求参数、响应结构和后续影响。

关键反编译类：

- `com.tcl.ff.component.oversea.constant.CMPServerApi`
- `com.tcl.ff.component.oversea.model.CMPRepository`
- `com.tcl.ff.component.oversea.model.requset.CMPRequestParams`
- `com.tcl.ff.component.oversea.model.requset.CmpUserConsentRequestParams`
- `com.tcl.ff.component.oversea.model.requset.CmpCampaignRequestParams`
- `com.tcl.ff.component.oversea.model.requset.CmpUserActionRequestParams`
- `com.tcl.ff.component.oversea.CmpPopStateManager`
- `com.tcl.ff.component.oversea.CmpConsentManager`
- `com.tcl.ff.component.oversea.viewmodel.CMPViewModel`

## 一、SDK 总体流程

SDK 内部 CMP 主要有 3 个服务端接口：

1. `getTCString`
   - 用来读取设备当前 consent 状态 / TC String / 是否有新 campaign
2. `getCampaign`
   - 用来拉取本轮 CMP 弹窗和生成 TC String 所需的 campaign 配置
3. `user/action`
   - 用户执行 `ACCEPT_ALL` / `REJECT` / `SAVE_SETTINGS` / `MAYBE_LATER` 后，SDK 上报动作和 TC String

正常完整链路大致是：

```text
loadPopState / loadCmpPrivacy
  -> getTCString
  -> 判断本地 TC String 是否失效、远端是否有新 campaign、是否强制弹窗
  -> 如需要弹窗/刷新 campaign，则 getCampaign
  -> campaign.data 有效后，SDK 才能展示 CMP 弹窗或生成静默 TC String
  -> 用户动作或我们静默动作完成后，user/action
```

本次问题的关键点：

```text
getTCString / 本地状态判断认为本轮可能需要 CMP
但 getCampaign 返回 data=null
导致客户端没有 campaign seed，后续无法正确执行 SDK CMP 动作
```

## 二、完整 URL 规则

SDK 的 URL 在 `CMPServerApi` 中拼接：

```java
private String host = AdOverseasDebugUtils.isDebug()
    ? AdServerHost.HOST_TEST
    : AdServerHost.CMP_HOST;
```

然后根据 `BasicParameters.get().isInternal()` 选择 internal / external path。

当前我们线上一般走 external path，即：

```text
{CMP_HOST}/global-consentmanage-api/sdk/device/getTCString
{CMP_HOST}/global-consentmanage-api/sdk/device/getCampaign
{CMP_HOST}/global-consentmanage-api/sdk/device/user/action
```

如果 `isInternal=true`，path 会变成：

```text
{CMP_HOST}/global-consentmanage-api/device/getTCString
{CMP_HOST}/global-consentmanage-api/device/getCampaign
{CMP_HOST}/global-consentmanage-api/device/user/action
```

说明：

- `{CMP_HOST}` 是 SDK base 组件里的 `AdServerHost.CMP_HOST`
- Debug 环境会改用 `AdServerHost.HOST_TEST`
- 当前反编译到的 CMP SDK 模块只展示 host 变量引用，host 具体值在 base 组件中

## 三、公共请求参数

三个接口都会继承 `CMPRequestParams.generateBaseRequestParams()` 的公共参数：

```json
{
  "deviceMaker": "<CmpConfigParams.deviceMake>",
  "deviceId": "<CmpConfigParams.deviceId>",
  "zone": "<CmpConfigParams.zone>",
  "clientType": "<CmpConfigParams.clientType>",
  "language": "<Utils.resolveLanguage(context)>",
  "appbundle": "<BasicParameters.packageName>",
  "appMd5": "<BasicParameters.signature>",
  "sdkVersion": "2.8.02",
  "appVersion": "<BasicParameters.versionName>"
}
```

我们 hq008 当前传入的 `CmpConfigParams`：

```text
deviceMaker = Build.MANUFACTURER.lowercase()，为空时 android
deviceId    = Settings.Secure.ANDROID_ID
zone        = de
clientType  = Build.MODEL，为空时 android
showPopForce = false
corner      = 24f
```

这几个字段非常关键，后台如果按设备、型号、区域、包名、签名、版本、语言做策略或风控，基本都会命中这里。

## 四、getTCString

### 触发场景

SDK 内部有两个常见入口会调它：

1. `CmpPopStateManager.loadPopState(...)`
   - 我们初始化或刷新 SDK CMP 状态时调用
   - 先看本地 TC String，再异步请求远端状态

2. `CmpConsentManager.loadCmpPrivacy(...)`
   - SDK 原生弹窗流程入口
   - `CMPViewModel` 先请求远端 consent 状态，再决定是否继续拉 campaign

我们 hq008 静默链路里，也通过反射构造同样请求主动调用该接口，用于对齐 SDK 判定。

### URL

```text
POST {CMP_HOST}/global-consentmanage-api/sdk/device/getTCString
```

internal 模式：

```text
POST {CMP_HOST}/global-consentmanage-api/device/getTCString
```

### 请求参数

公共参数 + `campaignVersion`：

```json
{
  "deviceMaker": "google",
  "deviceId": "a8814a94aeb27841",
  "zone": "de",
  "clientType": "Z1_MAX",
  "language": "en",
  "appbundle": "com.google.android.adffa",
  "appMd5": "19F256BBECFA1FC44F4267D427EF70E3",
  "sdkVersion": "2.8.02",
  "appVersion": "1.0.7",
  "campaignVersion": 123
}
```

`campaignVersion` 来源：

- SDK 内部来自 `CMPConsentDataProcessor.a().d()`
- 可以理解为本地已保存的 campaignId / campaign version
- 没有本地记录时可能是 `null` 或 `-1`

### 响应结构

SDK 反编译模型：

```json
{
  "code": 100000,
  "msg": "success",
  "data": {
    "campaignId": 123,
    "consentJson": {},
    "tcString": "...",
    "consentCookieExpiration": 31536000000,
    "createTime": 1710000000000
  }
}
```

已知 code：

```text
100000 = NORMAL_CODE
110005 = NEW_CAMPAIGN_CODE
```

SDK 判断是否有新 campaign：

```text
hasNewCampaign = code == 110005
```

### 对流程的影响

`loadPopState` 中：

- 本地 TC String 失效，直接回调 `needShowPop=true`
- 本地 TC String 有效时，会先回调当前 consentString / needShowPop，再请求 `getTCString`
- 如果远端返回 `code=110005`，SDK 会记录“有新 campaign”

`loadCmpPrivacy` 中：

- 如果网络不可用且本地没有 TC String，则进入 error
- 如果网络不可用但本地有 TC String，则直接返回 `ConsentStringSuccess` 并 `DoNotNeedPop`
- 如果网络可用，会先请求 `getTCString`
- 当本地失效、远端 `hasNewCampaign=true`、或 `showPopForce=true` 时，继续请求 `getCampaign`
- 否则走 `DoNotNeedPop`

## 五、getCampaign

### 触发场景

SDK 会在以下情况请求 campaign：

```text
本地 TC String 失效
或 getTCString 返回 code=110005，有新 campaign
或 CmpConfigParams.showPopForce=true
```

我们 hq008 当前静默链路中，如果判断本轮需要处理 CMP，也会主动通过反射构造同样请求调用 `getCampaign`，用于拿到生成 TC String 和执行 user/action 必须的 seed。

### URL

```text
POST {CMP_HOST}/global-consentmanage-api/sdk/device/getCampaign
```

internal 模式：

```text
POST {CMP_HOST}/global-consentmanage-api/device/getCampaign
```

### 请求参数

公共参数 + `showCmpForce`：

```json
{
  "deviceMaker": "google",
  "deviceId": "a8814a94aeb27841",
  "zone": "de",
  "clientType": "Z1_MAX",
  "language": "en",
  "appbundle": "com.google.android.adffa",
  "appMd5": "19F256BBECFA1FC44F4267D427EF70E3",
  "sdkVersion": "2.8.02",
  "appVersion": "1.0.7",
  "showCmpForce": false
}
```

### 成功响应结构

SDK 反编译模型：

```json
{
  "code": "100000",
  "msg": "success",
  "data": {
    "campaignId": 123,
    "name": "...",
    "logoImg": "...",
    "popType": 1,
    "messagePopup": {
      "firstLayer": {},
      "secondLayer": {},
      "updateTime": 1710000000000
    },
    "gvlSource": {
      "vendorListVersion": 224,
      "tcfPolicyVersion": 4,
      "purposes": [],
      "specialFeatures": [],
      "vendors": []
    },
    "updateTime": 1710000000000,
    "consentCookieExpiration": 31536000000,
    "CMP_ID": 493
  }
}
```

SDK 拿到 `data.gvlSource` 后会执行：

```text
gvlSource.makeUpData()
```

然后将 campaign 写入内存状态，用于：

- 展示 SDK CMP 弹窗
- 生成 TC String
- 构造 `user/action` 的 `consentJson`

### 异常 / 无数据响应

我们日志里关注到的典型异常是：

```json
{
  "code": 30000,
  "msg": "暂无活动",
  "data": null
}
```

这类响应的关键问题：

```text
接口请求成功了，但没有 campaign data
客户端无法拿到 campaignId / gvlSource / vendorListVersion
因此不能正确生成 TC String，也不能正确执行后续 user/action
```

当前 hq008 已整改：

```text
如果本轮需要 campaign seed，但 getCampaign 没有返回有效 data，
则上报 CMP_GATE_SKIP reason=campaign_seed_missing，
并跳过本轮 consent-popup / consent-report。
```

## 六、user/action

### 触发场景

SDK 弹窗或我们静默链路执行以下动作后，会调 `user/action`：

```text
ACCEPT_ALL
REJECT / ACCEPT_ESSENTIAL
SAVE_SETTINGS / SAVE_AND_EXIT
MAYBE_LATER
```

SDK 内部通过 `CMPViewModel` 处理对应 intent，然后调用：

```text
CMPRepository.uploadUserAction(...)
```

### URL

```text
POST {CMP_HOST}/global-consentmanage-api/sdk/device/user/action
```

internal 模式：

```text
POST {CMP_HOST}/global-consentmanage-api/device/user/action
```

### 请求参数

公共参数 + action 字段：

```json
{
  "deviceMaker": "google",
  "deviceId": "a8814a94aeb27841",
  "zone": "de",
  "clientType": "Z1_MAX",
  "language": "en",
  "appbundle": "com.google.android.adffa",
  "appMd5": "19F256BBECFA1FC44F4267D427EF70E3",
  "sdkVersion": "2.8.02",
  "appVersion": "1.0.7",
  "actionCode": 3,
  "createTime": 1710000000000,
  "campaignId": 123,
  "tcString": "...",
  "consentJson": {
    "version": 2,
    "cmpId": 493,
    "cmpVersion": 1,
    "consentScreen": 1,
    "tcfPolicyVersion": 4,
    "consentLanguage": "en",
    "vendorListVersion": 224,
    "purposesConsent": [1, 2, 3],
    "vendorsConsent": [1, 2, 3],
    "isServiceSpecific": true,
    "useNonStandardStacks": false,
    "specialFeatureOptIns": [],
    "purposesLITransparency": [],
    "purposeOneTreatment": false,
    "publisherCC": "HK",
    "vendorLegitimateInterest": [],
    "disclosedVendors": null,
    "allowedVendors": null,
    "pubPurposesConsent": null,
    "numberOfCustomPurposes": null,
    "customPurposesConsent": [],
    "customPurposesLITransparency": [],
    "pubPurposesLITransparency": null
  }
}
```

字段来源：

- `actionCode`: SDK 动作码
- `campaignId`: 来自 `getCampaign.data.campaignId`
- `tcString`: SDK `CMPConsentDataProcessor` 当前生成/保存的 TC String
- `consentJson`: 由 `GvlBean` 和当前 consent 选择状态组装
- `consentScreen`: SDK 当前使用的 consent screen，当前静默链路为 `1`
- `cmpId`: 固定写入 `493`
- `cmpVersion`: 固定写入 `1`
- `version`: 固定写入 `2`
- `publisherCC`: 固定写入 `HK`

注意：

```text
如果没有 getCampaign.data，就没有可靠的 campaignId / gvlSource / vendorListVersion。
这种情况下 user/action 的请求体无法完整构造。
```

### 响应

SDK 原始代码中 `user/action` 调用后没有解析强类型 response：

```java
HttpRequester.get().postJsonSync(url, body);
```

也就是说 SDK 只发请求，不关心响应模型。

我们 hq008 的静默补发链路会额外检查响应是否为服务端成功，用于避免误判。

## 七、SDK 动作码

从 SDK intent 和我们当前映射看，核心动作含义如下：

```text
ACCEPT_ALL        -> 接受全部
REJECT            -> 仅必要 / 拒绝
SAVE_SETTINGS     -> 保存自定义设置
MAYBE_LATER       -> 稍后决定 / 退出
```

当前 hq008 静默链路使用：

```text
ACCEPT_ALL actionCode = 3
consentScreen = 1
```

其他动作通过 SDK 反射路径或我们已有映射处理，最终都依赖同一个 `user/action` 接口。

## 八、与我们 hq008 当前链路的关系

我们没有完全使用 SDK 原生弹窗，而是做了自己的业务门禁和后台决策：

```text
flow-control
  -> TCL getTCString / getCampaign
  -> 判断本轮是否有完整 campaign seed
  -> 我方 consent-popup 决策
  -> 执行 SDK CMP 动作 / 补发 TCL user/action
  -> 我方 consent-report
```

整改后关键规则：

```text
如果本轮需要 TCL campaign seed，但 getCampaign 没返回有效 data：
  1. 上报 CAMPAIGN_REQUEST_FAIL missing campaign data
  2. 上报 CMP_GATE_SKIP reason=campaign_seed_missing
  3. 不请求我方 consent-popup
  4. 不请求我方 consent-report
  5. 只通过 consent-log-report.ad_log 带回 TCL 原始请求/响应
```

这样可以避免：

```text
参数没拿完整 -> 仍请求我方 popup -> popup 返回 action -> SDK 无 seed 无法执行 -> 无 consent-report
```

## 九、排查风控 / 配置问题时重点看什么

如果怀疑 TCL 后台根据参数风控或配置不返回 campaign，需要对比同一次链路里的：

```text
getTCString.rawRequest
getTCString.rawResponse
getCampaign.rawRequest
getCampaign.rawResponse
```

重点字段：

```text
deviceMaker
deviceId
zone
clientType
language
appbundle
appMd5
sdkVersion
appVersion
campaignVersion
showCmpForce
```

判断方式：

- `getTCString code=110005` 但 `getCampaign data=null`
  - 说明状态接口认为有新 campaign，但 campaign 接口不给配置
  - 高度怀疑后台 campaign 配置覆盖不完整、策略拦截、风控、区域/包名/签名/设备型号不匹配

- `getCampaign code=30000 msg=暂无活动 data=null`
  - 请求成功但没有活动配置
  - 需要 TCL / 后台按原始 request 查该设备为什么无活动

- 同一份 `getCampaign.rawRequest` 线下模拟能返回 data，线上设备返回 null
  - 继续查请求头、出口 IP、设备状态、服务端动态风控或环境 host

## 十、当前 ad_log 能拿到什么

当前版本我们已经在 `consent-log-report` 新增 `ad_log` 字段，TCL CMP 三个接口都会记录：

```text
api
url / path
rawRequest
rawResponse
requestLength / responseLength
requestTruncated / responseTruncated
durationMs
success
responseCode / responseMsg / dataIsNull
campaignId / vendorListVersion
errorType / errorMessage
```

`rawRequest` / `rawResponse` 最长保留 4096 字符。像下面这种异常响应会完整保留：

```json
{
  "code": 30000,
  "msg": "暂无活动",
  "data": null
}
```

