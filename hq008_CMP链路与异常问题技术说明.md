# hq008 CMP 链路与异常问题技术说明

## 1. 文档目标

本文档用于说明 `hq008` 当前 CMP 处理链路、广告授权链路，以及本次“同意率超过 100%”问题的形成原因、修复内容和当前剩余风险。

本文档面向：

- 管理层：快速了解当前链路和异常问题的处理结果
- 客户对接人：明确当前链路的真实行为和可对外解释口径
- 研发与测试：作为后续继续调整 CMP 行为和统计逻辑的依据

---

## 2. 当前完整流程图

![hq008 CMP 完整流程图](docs/hq008_cmp_flowchart_hd.png)

可单独打开原图：

- [高清 PNG](/Users/zengyue/Documents/Chihi_Project/AD_APP/docs/hq008_cmp_flowchart_hd.png)
- [矢量 SVG](/Users/zengyue/Documents/Chihi_Project/AD_APP/docs/hq008_cmp_flowchart_hd.svg)

---

## 2.1 产品视角流程图

![hq008 CMP 产品视角流程图](docs/hq008_cmp_product_flow_hd.png)

可单独打开原图：

- [高清 PNG](/Users/zengyue/Documents/Chihi_Project/AD_APP/docs/hq008_cmp_product_flow_hd.png)
- [矢量 SVG](/Users/zengyue/Documents/Chihi_Project/AD_APP/docs/hq008_cmp_product_flow_hd.svg)

---

这张图是给老板和产品看的，重点不是 SDK 内部细节，而是：

1. 当前无感 CMP 执行，对应的是页面上的哪个按钮语义
2. 请求的是我方服务器接口后，返回不同动作时的产品含义分别是什么
3. 哪些动作等价于“已经完成选择”
4. 哪些动作等价于“这次先不做最终选择”
5. CMP 结束后，后续广告流程怎么继续

从产品角度，可以把当前无感动作理解成下面几类按钮语义：

- `Accept All`：一键同意全部
- `Save Settings`：保存二级设置页里当前的自定义选择
- `Maybe Later`：二级设置页里的“这次先不选”
- 返回退出：本次不继续停留在页面，效果等同于暂不做最终选择
- `Manage`：不是当前无感流程单独执行的最终结果，而是 `Save Settings` 的前置页面语义

其中有两个特别要点：

1. `Save Settings` 不是固定结果，它取决于二级设置页里当时勾了什么
2. `Maybe Later` 和返回退出，都不属于“已经做完最终授权选择”
3. 当前无感主流程结束后，不是流程就停了，还会继续进入广告授权和广告请求阶段

---

这张图对应的是当前真实逻辑：

1. 启动阶段只负责把当前 CMP 状态准备好，方便后面广告链路判断
2. 启动时不再直接默认静默同意，只做状态准备和本地 consent 预注入
3. 广告轮询真正开始前，客户端会先请求我方 `sdk/flow-control`
4. 只有服务器返回 `enabled=true`，才继续进入这套客户的 CMP、授权和广告流程
5. 然后客户端才会看 SDK 当前返回的 `needShowPop`
6. 只有 `needShowPop=true` 时，才会请求我方 `consent-popup`
7. 后台当前可返回五种动作：`ACCEPT_ALL`、`REJECT`、`SAVE_SETTINGS`、`MAYBE_LATER`、`SKIP_ALREADY_DECIDED`
8. 动作真正执行成功后，才会按需要补发 SDK `user action` 并调用我方 `consent-report`
9. 当前去重不是“这台设备是否做过选择”，而是“这一轮 CMP 是否已经处理过”
10. 最后广告 SDK 会把 CMP 生成的 `consentString` 通过 `setGdprConsent(...)` 带入广告请求

## 3. 当前链路技术说明

### 3.0 无感动作和页面按钮的对应关系

为了方便非技术同学理解，可以把当前无感执行逻辑，理解成“后台替用户走了页面上某个按钮的语义”，但当前主流程**不是把页面真实展示出来让用户点击**。

#### 第一层页面按钮语义

- `Accept All`
  - 页面语义：一键同意全部
  - 无感对应：后台返回 `ACCEPT_ALL` 时，客户端无感执行这一类结果
  - 结果：生成同意状态，当前这轮 CMP 结束

- `Manage`
  - 页面语义：进入二级设置页
  - 无感对应：当前主流程不会单独执行一个“只进入 Manage”的结果
  - 结果：它只是 `Save Settings` 的前置页面语义

- `Maybe Later`
  - 页面语义：二级设置页里的“这次先不做最终选择”
  - 无感对应：后台返回 `MAYBE_LATER` 时，客户端无感执行这一类结果
  - 结果：当前不会形成最终同意或最终拒绝，后续是否再弹由 SDK 频控和后台策略共同决定

- 返回退出
  - 页面语义：不继续停留在当前 CMP 页面
  - 无感对应：当前没有单独的“返回退出”后台动作；产品含义上最接近 `MAYBE_LATER`
  - 结果：从产品效果上，等同于这次没有做最终选择

#### 第二层设置页面语义

- `Essential Cookies`
  - 页面语义：必要项说明入口
  - 无感对应：当前没有单独执行这个入口的后台动作
  - 结果：它不是最终授权按钮

- 各类 Purpose / Vendor 开关
  - 页面语义：用户自己决定放开什么、关掉什么
  - 无感对应：后台通过 `consent_payload` 直接给出这些开关最终结果
  - 结果：这些开关共同组成最后的自定义设置结果

- `Save Settings`
  - 页面语义：保存二级页面当前勾选状态
  - 无感对应：后台返回 `SAVE_SETTINGS` 时，客户端无感执行这一类结果
  - 结果：形成一次自定义授权结果，并结束当前这轮 CMP

### 3.1 启动阶段

`hq008` 当前会在两个入口初始化 CMP 管理器：

- `APP.onCreate()`
- `AdProvider.onCreate()`

初始化阶段会执行以下动作：

1. 初始化 `Hq008CmpManager`
2. 尝试读取本地 consent 文件
3. 如果本地已有 consent，则先预注入给 SDK
4. 请求 SDK 当前静默状态 `loadPopState`
5. 读取 SDK 返回的：
   - `consentString`
   - `needShowPop`
6. 生成本轮 `cmpCycleKey`
7. 将当前 CMP 状态标记为 `ready`

当前版本和旧版本最大的区别是：

- 启动时**不再无条件默认静默同意**
- 启动阶段不再直接执行最终同意或拒绝，只做状态准备
- 是否继续处理 CMP，要等广告链路触发时再结合后台返回一起决定

### 3.2 广告门禁阶段

当 `AdConfigManager` 请求 `hq008` 的悬浮广告链路时，当前顺序已经变成：

1. 先请求我方 `POST /api/v2/ad/sdk/flow-control`
2. 如果服务器返回 `enabled=false`，直接停止这套客户的 `CMP / 授权 / 广告` 全链路
3. 只有 `enabled=true`，才继续调用 `runWhenConsentStateReady()`
4. 等 CMP 初始状态 `ready`
5. 如果 SDK 当前 `needShowPop=false`，直接跳过本轮远端 CMP 决策
6. 如果 SDK 当前 `needShowPop=true`，请求我方后台 `consent-popup`
7. 后台返回动作后，客户端再尝试执行对应 CMP 动作
8. CMP 处理结束后，才继续请求 `sdk/authorize`

也就是说，当前 `hq008` 的广告链路不是“先 authorize 再管 CMP”，而是：

**先过总开关，再完成 CMP 决策，最后进入广告授权。**

### 3.3 SDK 流程总开关当前口径

我方后台接口：

- `POST /api/v2/ad/sdk/flow-control`

这个接口的作用是：

- 在每次 `hq008` 悬浮广告流程开始前
- 先判断这套客户 SDK 流程本轮是否允许继续执行

当前客户端请求体：

```json
{
  "channel_id": "TCL_FFA",
  "mac": "",
  "app_version": "1.0.2",
  "ad_version": 1
}
```

当前客户端只关注：

- `data.enabled = true`：允许继续整套客户流程
- `data.enabled = false`：本轮停止 `CMP / authorize / 广告` 全链路

当前异常处理口径也已经固定：

1. 请求失败 -> 按关闭处理
2. 响应码非 `200` -> 按关闭处理
3. 解析失败或 `data` 为空 -> 按关闭处理

也就是说，`flow-control` 当前是整套客户流程的第一道总开关。

### 3.4 consent-popup 当前口径

我方后台接口：

- `POST /api/v2/ad/consent-popup`

当前客户端请求体：

```json
{
  "channel_id": "TCL_FFA",
  "mac": "00:00:00:00:00:00",
  "app_version": "1.0.2",
  "consent_expired": false
}
```

当前服务端返回支持五种状态：

```json
{
  "consent_action": "ACCEPT_ALL"
}
```

或：

```json
{
  "consent_action": "REJECT"
}
```

或：

```json
{
  "consent_action": "SAVE_SETTINGS",
  "consent_payload": {
    "purpose_consent_ids": [1, 3],
    "purpose_li_ids": [2],
    "custom_purpose_consent_ids": [],
    "custom_purpose_li_ids": [],
    "special_feature_ids": [],
    "vendor_consent_ids": [12, 18],
    "vendor_li_ids": [30]
  }
}
```

或：

```json
{
  "consent_action": "MAYBE_LATER"
}
```

或：

```json
{
  "consent_action": "SKIP_ALREADY_DECIDED"
}
```

当前客户端不会自行决定本次该执行什么动作，而是按后台返回处理。

### 3.5 sdk/authorize 当前口径

我方后台接口：

- `POST /api/v2/ad/sdk/authorize`

这个接口的作用是：

- 在 CMP 这轮处理结束后
- 继续向我方后台确认本轮广告授权是否允许继续
- 同时拿回 `authorized`、`hidden_mode`、`request_id` 等广告链路控制字段

当前客户端请求体会带一整套设备与应用信息，其中和当前链路最相关的关键字段如下：

```json
{
  "request_id": "client-1715420000000-ab12cd34",
  "uuid": "xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx",
  "channel_id": "TCL_FFA",
  "app_version": "1.0.2",
  "app_id": "com.google.android.adffa",
  "bundle": "com.google.android.adffa",
  "ifa": "xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx",
  "mac": "00:11:22:33:44:55"
}
```

这里要特别注意三点：

1. `app_version` 现在已经和 `flow-control / consent-popup / consent-report` 一样同步上报
2. `request_id` 会贯穿授权和后续广告排查
3. `mac / ifa / app_id / bundle` 仍按客户端当前真实值上报

### 3.6 consent_expired 当前计算方式

本次新增的 `consent_expired`，是客户端额外上报给后台的状态字段。

它表达的不是“这次要不要直接同意或拒绝”，而是：

- 客户端本地保存的 consent 现在是否还可以继续沿用

判断规则如下：

1. 本地没有 consent 文件 -> `true`
2. 本地没有 `tc_string_expire_time` -> `true`
3. 当前时间已经超过 `tc_string_expire_time` -> `true`
4. 否则 -> `false`

这个字段的作用，是把“当前 consent 是否缺失或过期”交给后台参考。

它本身不会直接改变客户端行为。

### 3.7 consent-report 当前口径

我方后台接口：

- `POST /api/v2/ad/consent-report`

这个接口的作用是：

- 当客户端已经真正完成一次 CMP 动作后
- 再把这次最终结果上报给我方服务端

当前客户端请求体：

```json
{
  "channel_id": "TCL_FFA",
  "mac": "00:00:00:00:00:00",
  "app_version": "1.0.2",
  "consent_action": "ACCEPT_ALL"
}
```

或：

```json
{
  "channel_id": "TCL_FFA",
  "mac": "00:00:00:00:00:00",
  "app_version": "1.0.2",
  "consent_action": "REJECT"
}
```

或：

```json
{
  "channel_id": "TCL_FFA",
  "mac": "00:00:00:00:00:00",
  "app_version": "1.0.2",
  "consent_action": "SAVE_SETTINGS"
}
```

或：

```json
{
  "channel_id": "TCL_FFA",
  "mac": "00:00:00:00:00:00",
  "app_version": "1.0.2",
  "consent_action": "MAYBE_LATER"
}
```

当前接入原则是：

1. 不是后台先决定结果后立刻上报
2. 而是客户端动作真正执行成功后才上报
3. `SKIP_ALREADY_DECIDED` 不会上报 `consent-report`
4. 如果本次根本没有执行成功，就不会上报这条结果

### 3.8 动作执行阶段

当前客户端已经支持以下五类远端动作：

- `ACCEPT_ALL`
- `REJECT`
- `SAVE_SETTINGS`
- `MAYBE_LATER`
- `SKIP_ALREADY_DECIDED`

其中：

- `ACCEPT_ALL`：静默同意全部
- `REJECT`：静默拒绝非必要项
- `SAVE_SETTINGS`：按后台下发的 `consent_payload` 执行自定义设置
- `MAYBE_LATER`：执行一次“稍后再说”
- `SKIP_ALREADY_DECIDED`：本轮明确跳过，不做任何 CMP 动作

对 `ACCEPT_ALL / REJECT / SAVE_SETTINGS` 三类动作，当前链路会执行以下事情：

1. 读取当前 campaign 种子
2. 生成对应 TC String
3. 尝试反射调用 SDK 原生动作
4. 补发 SDK `user action`
5. 调用我方 `consent-report`
6. 记录本轮已执行动作

对 `MAYBE_LATER`：

1. 优先尝试 SDK 原生 Exit 语义
2. 如果 SDK 原生方式不可用，再直接补发一次 `user action`
3. 成功后调用我方 `consent-report`
4. 记录本轮已执行动作

对 `SKIP_ALREADY_DECIDED`：

1. 只记录本轮跳过
2. 不执行 CMP
3. 不调用 `consent-report`

### 3.9 当前去重逻辑

当前版本为了避免重复统计，去重逻辑已经不是旧版那种“看本地是否做过最终选择”，而是改成按**当前 CMP 轮次**去重。

当前轮次键 `cmpCycleKey` 由这些信息拼出来：

- `campaignId`
- consent 过期时间
- 是否新 campaign
- 当前 `needShowPop`
- 当前 `consent_expired`

其行为是：

1. 同一轮里如果已经执行过 `ACCEPT_ALL`
2. 或已经执行过 `REJECT`
3. 或已经执行过 `SAVE_SETTINGS`
4. 或已经命中过 `SKIP_ALREADY_DECIDED`
5. 后续本轮再次触发时，都会直接跳过

`MAYBE_LATER` 不属于终态动作，它会单独按本次上报哈希再做一次去重。

### 3.10 广告播放阶段

广告播放阶段仍由 `AdManagerImpl` 和 TCL 视频广告 SDK 执行：

1. 先通过 `sdk/flow-control` 总开关
2. 完成 CMP 决策
3. 请求 `sdk/authorize`
4. 如果 `authorized=true`，再进入广告 SDK
5. 把当前 `consentString` 通过 `setGdprConsent(...)` 传给广告 SDK
6. 请求广告素材
7. 上报请求、加载、开始、完成、错误等事件

当前播放链路会继续透传 `authorize` 返回的 `request_id`，用于后续串联排查。

---

## 4. 本次问题现象

客户提供的 CMP 报表中，曾出现以下异常：

- `Accept all` 大于 `Deliver total`
- `Accept all%` 超过 `100%`
- 部分区域出现 `Deliver total = 0`，但 `Accept all > 0`

基于本次收到的报表数据，汇总结果如下：

- 总 `Deliver total = 71,517`
- 总 `Accept all = 90,479`
- 总体同意率约为 `126.5%`
- 总记录数 `173`
- 异常记录数 `122`

部分典型异常样例：

- `AT`：`Deliver total = 13`，`Accept all = 41`
- `AZ`：`Deliver total = 145`，`Accept all = 583`
- 存在部分区域 `Deliver total = 0`，但 `Accept all > 0`

这说明问题不是单次报表错误，而是同意事件出现了系统性重复累计。

---

## 5. 根因分析

### 5.1 直接原因

本次异常的直接原因是：

- 旧版本同意事件虽然做了“去重”
- 但去重键使用了不稳定字段
- 去重键中包含了每次可能变化的 `TC String`

这会导致：

- 同一设备
- 同一类同意动作
- 仅因为 `TC String` 重新生成
- 就被误判成一次新的同意事件

最终导致 `Accept all` 被重复累计。

### 5.2 为什么会超过 100%

报表中的 `Accept all%` 本质是：

`Accept all / Deliver total`

当分子 `Accept all` 因重复上报持续放大，而分母 `Deliver total` 没有同步增长时，就会出现：

- 超过 `100%`
- 甚至远高于 `100%`

因此问题不是展示公式错误，而是上游同意统计被冲坏。

### 5.3 旧版本为什么更容易放大异常

旧版本还有两个放大因素：

1. 启动阶段就可能直接走默认静默同意
2. 去重逻辑只看“本地是否已经做过最终选择”，不能准确表达“这一轮 CMP 是否已经处理过”

所以旧版本更容易在冷启动、重复轮询或 campaign 变化时，产生额外同意统计。

---

## 6. 当前修复说明

### 6.1 修复目标

当前修复目标分成三层：

1. 先修正同意统计重复计数问题
2. 再把 CMP 决策时机收敛到广告前置门禁，由后台统一控制
3. 再给整套客户流程增加一个可实时生效的总开关
4. 再把动作类型扩展到与 SDK 当前页面能力相匹配

### 6.2 已完成修复内容

当前版本已完成以下调整：

1. 去重不再依赖会变化的 `TC String`
2. 去重改为依赖当前 `cmpCycleKey`
3. 启动阶段不再默认静默同意
4. 广告请求改为先走 `sdk/flow-control` 总开关，再走 CMP 前置门禁和 `sdk/authorize`
5. 新增 `sdk/flow-control` 客户端
6. 新增 `consent-popup` 客户端
7. 在 `consent-popup` 请求中新增 `consent_expired`
8. `sdk/flow-control / consent-popup / consent-report / sdk/authorize` 请求统一补上 `app_version`
9. 在动作真正执行成功后，新增 `consent-report` 上报
10. 当前客户端动作已补齐为：
   - `ACCEPT_ALL`
   - `REJECT`
   - `SAVE_SETTINGS`
   - `MAYBE_LATER`
   - `SKIP_ALREADY_DECIDED`
11. `needShowPop=false` 时，客户端当前会直接跳过本轮远端 CMP 决策
12. `flow-control` 返回关闭或异常时，客户端当前会直接停止整套客户流程

### 6.3 修复后的预期结果

当前版本上线后，按客户端逻辑预期：

1. 不会再因为 `TC String` 变化重复累计同意次数
2. 不会在冷启动时自动静默同意
3. 不会在同一轮 CMP 内重复执行终态动作
4. 后台可以实时控制整套客户流程是否继续执行
5. 后台也可以按返回值控制同意、拒绝、保存设置、稍后再说或直接跳过
6. 当前“因为客户端重复触发而导致同意率超过 100%”的问题，按现有已知根因判断，不应再出现

---

## 7. 当前影响评估

### 7.1 对历史数据的影响

历史异常数据已经被客户报表记录，无法通过客户端修复自动回滚。

因此应按“修复上线时间点”切分：

- 修复前数据：视为异常统计期
- 修复后数据：作为新的观察基线

### 7.2 对客户说明的建议口径

对客户可解释为：

- CMP 同意事件统计曾存在重复计数
- 已完成去重和触发时机修复
- 后续新数据应恢复正常

不建议直接对客户暴露内部静默实现细节。

### 7.3 对内部的真实结论

内部需要明确：

1. 本次“同意率 > 100%”问题，根因已确认是客户端同意事件重复计数
2. 当前修复已经覆盖这条已知根因
3. 现在整套客户流程已增加后台总开关控制
4. CMP 决策也已改为后台控制
5. `sdk/flow-control / consent-popup / consent-report / sdk/authorize` 现在都已统一补齐 `app_version`
6. 当前同意、拒绝、保存设置、稍后再说成功后，也会按规则上报我方 `consent-report`
7. 当前是否再次处理过期 consent，仍取决于后台返回和 SDK 当前 `needShowPop`

---

## 8. 当前风险与后续建议

### 8.1 统计层风险

虽然客户端重复计数根因已修复，但仍建议继续观察新数据：

- `Accept all` 是否仍显著高于 `Deliver total`
- 是否仍大面积出现 `Accept all% > 100%`
- 是否仍出现大量 `Deliver total = 0` 且 `Accept all > 0`

如果后续仍出现异常，则需要进一步排查：

- 客户统计口径
- SDK 服务端计数口径
- 分母聚合逻辑

### 8.2 consent 过期风险

当前客户端只负责把 `consent_expired` 上报给后台，不自行根据过期状态续期。

这意味着：

- 当前是否在 consent 过期后再次执行同意、拒绝、保存设置或稍后再说
- 由后台 `consent-popup` 返回结果和 SDK 当前 `needShowPop` 一起决定

当前方案的优点是：

- 逻辑简单
- 客户端不自行猜测 SDK 合规策略
- 后台可根据地区、阶段和客户诉求动态控制

### 8.3 save settings 配置风险

`SAVE_SETTINGS` 的真实执行效果，依赖后台下发的 `consent_payload` 是否符合当前 SDK 支持的 purpose、vendor 和 special feature 范围。

如果后台下发的 ID 超出 SDK 当前允许范围，客户端会做过滤，最终实际生效结果可能与后台原始下发值不完全一样。

### 8.4 建议后续动作

建议后续分两步推进：

1. 先观察修复后的新报表是否恢复正常
2. 再根据后台对 `sdk/flow-control`、`consent_expired` 和 `consent_action` 的策略需要，决定是否扩展更多状态或调整投放节奏

当前不建议客户端自行引入更复杂的续期规则，以免再次出现“统计修复”和“业务策略”耦合过深的问题。

---

## 9. 关键代码位置

当前版本涉及的关键位置如下：

- `app/src/hq008/java/com/smart/android/ad_app/Hq008SdkFlowControlClient.kt`
- `app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt`
- `app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt`
- `app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt`
- `app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt`
- `app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt`
- `app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt`

相关测试位置：

- `app/src/test/java/com/smart/android/ad_app/Hq008FlowControlContractTest.kt`
- `app/src/test/java/com/smart/android/ad_app/Hq008CmpSilentUploadDedupKeyTest.kt`
- `app/src/test/java/com/smart/android/ad_app/Hq008CmpFinalDecisionContractTest.kt`
- `app/src/test/java/com/smart/android/ad_app/Hq008CmpAdGateContractTest.kt`
- `app/src/test/java/com/smart/android/ad_app/Hq008CmpDecisionClientContractTest.kt`
- `app/src/test/java/com/smart/android/ad_app/Hq008AppVersionContractTest.kt`

---

## 10. 结论

当前 `hq008` CMP 链路已经从“启动阶段容易直接默认同意”的旧逻辑，调整为“总开关前置 + 广告前置门禁 + 后台策略控制 + 按 CMP 当前轮次去重”的新逻辑。

本次“同意率超过 100%”问题的已知根因已经明确并完成修复：

- 旧版属于同意事件去重口径不稳定
- 导致同一设备的同意事件被重复计数
- 进而使 `Accept all` 异常高于 `Deliver total`

当前版本的关键特征是：

1. 启动时不再默认静默同意
2. 每次悬浮广告轮询前，先请求 `sdk/flow-control` 决定整套客户流程是否继续
3. 先看 SDK 当前 `needShowPop`，再决定是否请求后台动作
4. 后台当前可返回 `ACCEPT_ALL / REJECT / SAVE_SETTINGS / MAYBE_LATER / SKIP_ALREADY_DECIDED`
5. 请求里已额外上报 `consent_expired`
6. `sdk/flow-control / consent-popup / consent-report / sdk/authorize` 当前都已带上 `app_version`
7. 动作真正执行成功后，会再按规则上报一次我方 `consent-report`
8. 当前去重逻辑已经改成“同一轮 CMP 只执行一次”

后续重点只需要继续关注三件事：

1. 修复后的新数据是否回归正常
2. 后台是否需要基于 `consent_expired` 进一步动态调整策略
3. `SAVE_SETTINGS` 与 `MAYBE_LATER` 在真实地区和真实投放上的统计表现是否符合预期
