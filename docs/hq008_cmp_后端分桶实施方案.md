# hq008 CMP 静默链路后端分桶实施方案

本文档用于给后端实现 `hq008` 当前 CMP 静默链路的分桶控制方案。

本文内容严格以当前客户端代码为准，不假设新增客户端字段，不假设新增客户端动作，也不要求客户端先发版。

---

## 1. 结论

`hq008` 这条链路的最佳实现方式是：

- 后端负责分桶和动作决策
- 客户端负责按既有动作协议执行
- 客户端执行完成后，再调用既有 `consent-report` 回传最终动作

也就是说，这不是“纯客户端随机”，也不是“后端直接代替客户端执行 CMP”，而是：

**后端决策，客户端执行。**

这和当前客户端代码逻辑完全一致。

---

## 2. 当前客户端已经支持的能力

当前 `hq008` 客户端在收到后端 `consent-popup` 响应后，只支持以下 5 类远端动作：

- `ACCEPT_ALL`
- `REJECT`
- `SAVE_SETTINGS`
- `MAYBE_LATER`
- `SKIP_ALREADY_DECIDED`

对应客户端入口：

- [AdConfigManager.kt](/Users/zengyue/Documents/Chihi_Project/AD_APP/app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt)
- [Hq008CmpDecisionClient.kt](/Users/zengyue/Documents/Chihi_Project/AD_APP/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt)
- [Hq008CmpManager.kt](/Users/zengyue/Documents/Chihi_Project/AD_APP/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt)

当前客户端不支持新的自定义动作枚举。

所以后端方案必须建立在这 5 个动作上。

---

## 3. 你给出的业务目标与客户端动作映射

你给出的目标是：

- 同意：5%
- 接收部分：10%
- 自定义设置：3%
- 以后再说：7%
- 跳过：75%

### 3.1 当前按 100% 正式落地

当前正式方案按总和 `100%` 落地：

- 同意：5%
- 接收部分：10%
- 自定义设置：3%
- 以后再说：7%
- 跳过：75%

后端应直接按这组比例做稳定分桶，不再保留 103% 的旧口径，也不需要再做归一化。

### 3.2 与当前客户端动作的精确映射

按当前客户端能力，建议这样映射：

| 业务口径 | 客户端动作 | 说明 |
|---|---|---|
| 同意 | `ACCEPT_ALL` | 客户端已原生支持 |
| 接收部分 | `SAVE_SETTINGS` | 后端下发一套“部分接收模板” `consent_payload` |
| 自定义设置 | `SAVE_SETTINGS` | 后端下发另一套“自定义模板” `consent_payload` |
| 以后再说 | `MAYBE_LATER` | 客户端已原生支持 |
| 跳过 | `SKIP_ALREADY_DECIDED` | 客户端本轮直接跳过，不执行 CMP |

### 3.3 一个非常重要的现实限制

当前客户端在 `consent-report` 阶段，只会上报：

- `ACCEPT_ALL`
- `REJECT`
- `SAVE_SETTINGS`
- `MAYBE_LATER`

或者根本不上报（`SKIP_ALREADY_DECIDED`）

这意味着：

- 业务上的“接收部分”
- 业务上的“自定义设置”

在客户端最终回传时，**都会统一表现为 `SAVE_SETTINGS`**。

所以如果后端报表要区分：

- `接收部分`
- `自定义设置`

那就不能只看 `consent-report`，必须结合后端在 `consent-popup` 阶段自己做的分桶记录。

---

## 4. 当前客户端真实执行链路

当前 `hq008` 悬浮广告链路的执行顺序不是“直接请求 consent-popup”。

而是：

1. 先请求 `flow-control`
2. `flow-control.enabled != true` 时，整套客户流程直接结束
3. `flow-control.enabled == true` 时，等待 CMP 初始状态就绪
4. 客户端先调用 SDK `loadPopState`
5. 如果 SDK 返回 `needShowPop=false`，客户端直接跳过远端 CMP 决策
6. 只有 `needShowPop=true` 时，客户端才会请求后端 `consent-popup`
7. 后端返回 `consent_action`
8. 客户端执行动作
9. 成功后调用 `consent-report`
10. 再继续调用 `authorize`
11. 授权通过后才会进入广告下发

所以后端要明确一个事实：

**不是每次广告请求都会命中 `consent-popup`。**

只有满足下面条件时，后端分桶才会生效：

- `flow-control` 允许继续
- 客户端 CMP 状态已经初始化完成
- SDK 当前 `needShowPop=true`

---

## 5. 后端应复用的现有接口

当前客户端已经固定使用以下两个接口：

### 5.1 决策接口

`POST /api/v2/ad/consent-popup`

客户端请求字段当前是：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| channel_id | string | 是 | 来自 `BuildConfig.CHANNEL` |
| mac | string | 是 | 设备 MAC，拿不到时默认 `00:00:00:00:00:00` |
| ad_version | int | 是 | 当前客户端 `versionCode` |
| consent_expired | boolean | 是 | 当前本地 consent 是否过期 |

当前客户端请求代码在：

- [Hq008CmpDecisionClient.kt](/Users/zengyue/Documents/Chihi_Project/AD_APP/app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt)

### 5.2 结果回传接口

`POST /api/v2/ad/consent-report`

客户端请求字段当前是：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| channel_id | string | 是 | 来自 `BuildConfig.CHANNEL` |
| mac | string | 是 | 设备 MAC |
| ad_version | int | 是 | 当前客户端 `versionCode` |
| consent_action | string | 是 | 最终执行动作 |

注意：

- 当前客户端不会在 `consent-report` 里带 `strategy_id`
- 当前客户端不会在 `consent-report` 里带 `bucket_id`
- 当前客户端不会在 `consent-report` 里区分“接收部分”和“自定义设置”

所以如果后端现在不改接口，就必须自己在 `consent-popup` 阶段保存决策记录。

---

## 6. `consent-popup` 响应格式要求

当前客户端只认下面这个响应结构：

```json
{
  "code": 100000,
  "msg": "success",
  "data": {
    "consent_action": "SAVE_SETTINGS",
    "consent_payload": {
      "purpose_consent_ids": [1, 3, 4],
      "purpose_li_ids": [2, 7],
      "custom_purpose_consent_ids": [],
      "custom_purpose_li_ids": [],
      "special_feature_ids": [1],
      "vendor_consent_ids": [12, 25, 99],
      "vendor_li_ids": [8, 19]
    }
  }
}
```

其中：

- `consent_action` 必须是上述 5 个既有值之一
- 当 `consent_action=SAVE_SETTINGS` 时，`consent_payload` 才有意义

### 6.1 `SAVE_SETTINGS` 的 payload 规则

客户端会对这些 ID 做本地合法性过滤，只保留 SDK 当前 campaign 里真实存在的 ID。

也就是说后端可以下发“部分接收模板”或“自定义模板”，客户端会做一次兜底清洗。

字段如下：

- `purpose_consent_ids`
- `purpose_li_ids`
- `custom_purpose_consent_ids`
- `custom_purpose_li_ids`
- `special_feature_ids`
- `vendor_consent_ids`
- `vendor_li_ids`

---

## 7. 推荐的后端实现方式

## 7.1 总体策略

后端不要把这 5 个比例做成“每次请求随机”。

应该做成：

- 基于稳定设备标识
- 基于稳定策略版本
- 基于稳定分桶

这样同一设备在同一策略版本下，命中的动作稳定，不会一会儿同意、一会儿跳过。

### 推荐分桶输入

建议至少使用以下字段做 hash：

- `channel_id`
- `mac`
- 可选：`ad_version`
- 可选：当前策略版本号

如果后端希望“策略一更新就重新洗牌”，可以把策略版本号拼进 hash 输入。

如果后端希望“同一设备长期稳定”，就不要把策略版本号拼进 hash 输入。

## 7.2 分桶步骤

以目标比例为例，后端实现建议如下：

1. 使用正式比例 5/10/3/7/75
2. 计算稳定 hash
3. 映射到 `0..9999` 或 `0..99`
4. 按区间返回动作

例如映射到 `0..99` 时，区间可以写成：

- `0..4` -> `ACCEPT_ALL`
- `5..14` -> `SAVE_SETTINGS`，使用“接收部分模板”
- `15..17` -> `SAVE_SETTINGS`，使用“自定义设置模板”
- `18..24` -> `MAYBE_LATER`
- `25..99` -> `SKIP_ALREADY_DECIDED`

建议把这组分桶区间固化到后端配置里，并配一个明确的 `strategy_version` 供后端自己落表使用。

---

## 8. “接收部分”与“自定义设置”的后端实现建议

由于当前客户端只有一个 `SAVE_SETTINGS` 动作，所以后端建议维护两套模板：

### 8.1 部分接收模板

业务语义：

- 用于“接收部分” 10%

返回方式：

- `consent_action = SAVE_SETTINGS`
- `consent_payload = partial_accept_template`

### 8.2 自定义设置模板

业务语义：

- 用于“自定义设置” 3%

返回方式：

- `consent_action = SAVE_SETTINGS`
- `consent_payload = custom_setting_template`

### 8.3 报表注意事项

当前客户端回传时，这两类最后都会报成：

- `consent_action = SAVE_SETTINGS`

所以后端如果要在报表里区分：

- `partial_accept`
- `custom_setting`

建议在后端自己的分桶记录表里保存：

- `device_key`
- `channel_id`
- `ad_version`
- `strategy_version`
- `bucket_type`
- `returned_consent_action`
- `returned_payload_hash`
- `popup_request_time`

然后在收到 `consent-report` 时，用设备维度把它关联回去。

---

## 9. 与当前客户端去重逻辑的对齐要求

当前客户端已经不是“看到请求就一定执行”。

它有两层关键去重：

### 9.1 同一 CMP 轮次去重

客户端会生成 `cmpCycleKey`，由以下信息拼出来：

- `campaignId`
- consent 过期时间
- 是否新 campaign
- 当前 `needShowPop`
- 当前 `consent_expired`

同一轮里如果已经执行过终态动作：

- `ACCEPT_ALL`
- `REJECT`
- `SAVE_SETTINGS`

后面再次进来会直接跳过。

如果同一轮里已经执行过：

- `SKIP_ALREADY_DECIDED`

后面再次进来也会直接跳过。

### 9.2 user/action 上报去重

客户端还会按：

- `campaignId|actionType`

生成本地哈希，用于避免重复补发 SDK `user/action`。

这意味着后端不需要额外为“客户端重复触发”兜一层复杂重试去重；当前客户端已经做了本地控制。

---

## 10. 当次处理后，下次轮询如何继续

这一节是后端实现时最容易遗漏的部分。

当前 `hq008` 的后续轮询不是“上次命中过什么动作，下次一定还能再进 `consent-popup`”。真实行为要先经过客户端本地门禁。

### 10.1 先看是否还能进入后端分桶

下次轮询到来时，客户端仍然会按这个顺序处理：

1. 先走本地轮询调度
2. 请求 `flow-control`
3. 初始化并读取 SDK `loadPopState`
4. 只有 `needShowPop=true` 才会请求后端 `consent-popup`

当前 `hq008` 本地轮询策略是：

- 首次延迟：5 秒
- 后续轮询：900 到 1200 秒之间随机一个固定值

对应代码：

- [Hq008LocalSchedulePolicy.kt](/Users/zengyue/Documents/Chihi_Project/AD_APP/app/src/main/java/com/smart/android/ad_app/Hq008LocalSchedulePolicy.kt)
- [ScheduleManagerImpl.kt](/Users/zengyue/Documents/Chihi_Project/AD_APP/app/src/main/java/com/smart/android/ad_app/ScheduleManagerImpl.kt)

所以从后端视角，下一次是否还能收到 `consent-popup`，先取决于：

- 轮询是否发生
- `flow-control` 是否仍然开启
- SDK 当前是否仍然认为 `needShowPop=true`

### 10.2 如果本次已经命中终态动作

这里的终态动作是：

- `ACCEPT_ALL`
- `REJECT`
- `SAVE_SETTINGS`

当前客户端行为是：

1. 执行动作
2. 记录本轮 `cmpCycleKey` 已执行结果
3. 成功后上报 `consent-report`

下次轮询时有两种可能：

#### 情况 A：SDK 已经返回 `needShowPop=false`

这是后端最希望出现的常态。

此时客户端会：

- 直接跳过远端 CMP 决策
- 不再请求 `consent-popup`
- 继续后续授权和广告链路

#### 情况 B：SDK 仍然返回 `needShowPop=true`

这种情况下客户端仍然可能请求到后端 `consent-popup`，但如果当前还是同一个 `cmpCycleKey`，客户端会因为本轮已经执行过终态动作而直接跳过再次执行。

也就是说：

- 后端可能还能收到请求
- 但客户端不会再次重复执行终态动作

所以后端不需要为了“终态动作重复执行”额外加复杂互斥，客户端本地已经挡住了。

### 10.3 如果本次命中的是 `MAYBE_LATER`

这是最需要单独说明的一类。

当前客户端对 `MAYBE_LATER` 的处理是：

1. 执行一次 “以后再说”
2. 记录本轮最后动作为 `MAYBE_LATER`
3. 成功后上报 `consent-report`

但 `MAYBE_LATER` **不是终态动作**。

这带来两个结果：

#### 结果 A：如果下次轮询时 SDK 返回 `needShowPop=false`

客户端不会再请求后端 `consent-popup`。

#### 结果 B：如果下次轮询时 SDK 仍返回 `needShowPop=true`

客户端仍可能再次请求后端 `consent-popup`。

并且在同一个 `cmpCycleKey` 下：

- 如果后端再次返回 `MAYBE_LATER`，客户端会识别为“相同动作已做过”，本轮跳过重复执行
- 如果后端改为返回终态动作 `ACCEPT_ALL / REJECT / SAVE_SETTINGS`，客户端是允许继续执行的

这点非常重要。

也就是说，`MAYBE_LATER` 适合做“本次先不终结，但允许后续再转终态”的策略。

### 10.4 如果本次命中的是 `SKIP_ALREADY_DECIDED`

当前客户端对 `SKIP_ALREADY_DECIDED` 的处理是：

1. 记录本轮已跳过
2. 不执行 CMP
3. 不上报 `consent-report`

如果后续仍在同一个 `cmpCycleKey` 下，即使后端下一次想改回别的动作，客户端也会直接跳过，不再执行。

也就是说：

**`SKIP_ALREADY_DECIDED` 在当前轮次内是一个强跳过动作。**

所以后端要谨慎使用：

- 如果你希望这台设备“本轮完全别动”，用它
- 如果你只是“本次暂时不做决定，下次还想重新给动作”，不要用它，应该用 `MAYBE_LATER`

### 10.5 如果本次没有命中任何动作

这里包括两种情况：

#### 情况 A：客户端根本没有请求到 `consent-popup`

例如：

- `flow-control` 关闭
- `needShowPop=false`
- 初始化异常但客户端继续放行

这种情况下后端这次没有参与，所以下次轮询依旧按正常门禁重新判断。

#### 情况 B：客户端请求到了 `consent-popup`，但后端返回异常或未知动作

例如：

- 接口失败
- 返回空数据
- `consent_action` 不在支持枚举里

客户端行为是：

- 本次跳过执行
- 不记录有效终态
- 继续后续链路

下次轮询如果再次进入 `consent-popup`，仍然会重新请求后端，不会因为这次失败而被永久卡住。

### 10.6 后端建议采用的完整规则

为了和当前客户端行为完全对齐，后端建议按下面规则设计：

1. 设备命中终态桶后，保持稳定返回同一终态动作，直到后端主动切策略版本
2. 设备命中 `MAYBE_LATER` 桶后，允许后续在再次命中时升级为终态动作
3. 设备命中 `SKIP_ALREADY_DECIDED` 桶后，把它视为“本轮静默不处理”，不要指望同轮里再改别的动作生效
4. 后端统计上要区分“命中过分桶”与“客户端实际执行成功”
5. 不要把“没有再次收到 `consent-popup`”误判为后端没生效，很多情况下是客户端本地 `needShowPop=false`

---

## 11. `SKIP_ALREADY_DECIDED` 的正确使用方式

当前客户端对 `SKIP_ALREADY_DECIDED` 的行为是：

1. 记录本轮已跳过
2. 不执行 CMP 动作
3. 不调用 `consent-report`
4. 继续后续授权链路

所以这个动作适合以下场景：

- 后端刻意不希望本轮改动用户同意状态
- 后端希望大量流量直接放过 CMP 静默动作
- 后端想把这部分流量计入“跳过”

但要注意：

**这部分流量不会出现在 `consent-report` 成功日志里。**

如果后端报表要统计“跳过率”，必须靠：

- `consent-popup` 决策日志
- 或后端自己的分桶日志

不能只看 `consent-report`。

---

## 12. `MAYBE_LATER` 的当前客户端语义

当前客户端对 `MAYBE_LATER` 的行为是：

1. 优先尝试 SDK 原生 `MAYBE_LATER`
2. 如果反射失败，补发一次 SDK `user/action`
3. 成功后上报 `consent-report`

所以从后端视角看，`MAYBE_LATER` 是一个真实可执行动作，不是“什么都不做”。

---

## 13. 推荐的后端落表字段

为了在不改客户端的前提下把统计跑顺，建议后端至少落以下字段：

| 字段 | 说明 |
|---|---|
| request_id | 后端自己的请求流水号 |
| channel_id | 渠道 |
| mac | 设备标识 |
| ad_version | 客户端版本 |
| consent_expired | 请求时是否过期 |
| strategy_version | 当前分桶策略版本 |
| bucket_type | 业务桶，如 `accept_all` / `partial_accept` / `custom_setting` / `maybe_later` / `skip` |
| consent_action | 返回给客户端的动作 |
| payload_hash | `consent_payload` 哈希，便于区分两种 `SAVE_SETTINGS` |
| popup_requested_at | `consent-popup` 请求时间 |
| cycle_scope | 后端按自身规则识别的轮次范围，便于和客户端 `cmpCycleKey` 概念对齐 |
| report_received_at | `consent-report` 接收时间 |
| report_action | 客户端回传的动作 |
| report_success | 是否收到成功回传 |
| final_state_guess | 后端推断的当前状态，如 `terminal` / `later` / `skipped` / `unknown` |

这样即使客户端不带 `strategy_version`，后端也仍然可以依靠：

- `channel_id`
- `mac`
- `ad_version`
- 时间窗口

做大部分关联。

---

## 14. 当前版本下的最佳落地方案

如果目标是“今天就能跟当前客户端对上”，推荐直接这样做：

### 13.1 决策接口

继续使用现有：

- `POST /api/v2/ad/consent-popup`

### 13.2 返回动作

- 同意 -> `ACCEPT_ALL`
- 接收部分 -> `SAVE_SETTINGS` + 部分接收模板
- 自定义设置 -> `SAVE_SETTINGS` + 自定义模板
- 以后再说 -> `MAYBE_LATER`
- 跳过 -> `SKIP_ALREADY_DECIDED`

### 13.3 统计口径

后端报表拆成两层：

1. `consent-popup` 分桶命中口径
2. `consent-report` 实际执行成功口径

不要只看 `consent-report`。

### 13.4 设备分配方式

用稳定 hash 分桶，不要每次随机。

### 13.5 建议的后端状态机

虽然当前客户端没有把状态机字段显式回传给后端，但后端实现上建议自己维护一份简化状态：

- `INIT`
- `TERMINAL_APPLIED`
- `LATER_APPLIED`
- `SKIPPED_IN_CYCLE`
- `UNKNOWN`

推荐迁移规则：

1. 首次进入 `consent-popup` 时按稳定分桶返回动作
2. 若收到 `consent-report=ACCEPT_ALL/REJECT/SAVE_SETTINGS`，记为 `TERMINAL_APPLIED`
3. 若收到 `consent-report=MAYBE_LATER`，记为 `LATER_APPLIED`
4. 若本次返回 `SKIP_ALREADY_DECIDED`，记为 `SKIPPED_IN_CYCLE`
5. 下次同设备再次请求时：
   - `TERMINAL_APPLIED`：继续返回同一终态动作或直接仍走稳定桶；客户端会自行去重
   - `LATER_APPLIED`：可继续返回 `MAYBE_LATER`，也可升级为终态动作
   - `SKIPPED_IN_CYCLE`：建议同轮仍返回 `SKIP_ALREADY_DECIDED`

这里的“同轮”因为后端拿不到客户端 `cmpCycleKey`，建议后端用自己的近似规则识别，例如：

- 同设备
- 同渠道
- 同策略版本
- 同 campaign 活跃窗口
- 同 consent 未过期阶段

后端不需要和客户端算出完全一样的轮次键，但要有“轮次”意识。

---

## 15. 如果后续想把统计做得更准

这部分不是当前必须改，但建议作为后续增强项：

### 建议新增但非本期必需的字段

`consent-popup` 返回：

- `strategy_version`
- `bucket_type`
- `decision_id`

`consent-report` 回传：

- `strategy_version`
- `decision_id`

这样后端就能直接闭环，不用再靠设备和时间窗口做关联。

但这属于后续客户端增强，不属于当前版本必需项。

---

## 16. 本方案与当前客户端代码的对齐结论

本方案与当前客户端完全对齐的点有：

- 使用既有 `consent-popup`
- 使用既有 `consent-report`
- 仅使用客户端已支持的 5 个动作值
- `接收部分` 与 `自定义设置` 都映射为 `SAVE_SETTINGS`
- 允许客户端在 `needShowPop=false` 时直接跳过远端决策
- 允许客户端按 `cmpCycleKey` 做本轮去重
- 允许客户端按 `campaignId|actionType` 做静默 `user/action` 去重
- `SKIP_ALREADY_DECIDED` 不会触发 `consent-report`

因此，这份方案可以直接给后端实施，不要求客户端先改协议。
