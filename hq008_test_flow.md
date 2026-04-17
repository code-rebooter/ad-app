# hq008 正式链路测试流程

## 基本信息

- 包名：`com.google.android.adcl`
- 渠道：`TCL_DEMO`
- 授权接口：`POST /api/v2/ad/sdk/authorize`
- 上报接口：`POST /api/v2/ad/report`
- 当前正式逻辑：
  - 先请求 `authorize`
  - `authorized=false` 时不展示广告
  - `authorized=true` 时进入广告播放
  - `hidden_mode` 控制无感播放状态
  - `next_request_seconds` 动态更新下一次轮询间隔
  - `request_id` 透传到后续播放与上报链路

## 测试前准备

1. 安装 `hq008` 正式包到测试设备。
2. 确认设备网络正常。
3. 确认应用进程可被宿主场景或 `ContentProvider` 拉起。
4. 后台提前准备好可控返回数据，至少包含以下三组：
   - `authorized=false`
   - `authorized=true, hidden_mode=true`
   - `authorized=true, hidden_mode=false`
5. 后台每组返回都要明确给出：
   - `authorized`
   - `hidden_mode`
   - `next_request_seconds`
   - `request_id`

## 测试重点

本次主要验证 4 件事：

1. `authorize` 是否成功触发并正确处理返回值。
2. `hidden_mode` 是否能正确控制无感播放。
3. `next_request_seconds` 是否能正确更新轮询间隔。
4. 后续上报里的顶层 `request_id` 是否等于 `authorize` 返回的 `request_id`。

## 建议测试顺序

### 1. 验证 authorize 成功触发

预期：

- 客户端先发起 `authorize` 请求。
- 设备日志中能看到 `hq008 authorize success` 或 `hq008 authorize denied`。
- 返回字段能正常被客户端读取。

需要核对：

- `authorized`
- `hidden_mode`
- `next_request_seconds`
- `request_id`

### 2. 验证 authorized=false

后台返回：

```json
{
  "authorized": false,
  "hidden_mode": true,
  "next_request_seconds": 600,
  "request_id": "test-denied-001"
}
```

预期：

- 不展示广告
- 不进入播放链路
- 不应有播放开始日志
- 调度器更新为服务端返回的 `next_request_seconds`

### 3. 验证 authorized=true 且 hidden_mode=true

后台返回：

```json
{
  "authorized": true,
  "hidden_mode": true,
  "next_request_seconds": 1200,
  "request_id": "test-hidden-001"
}
```

预期：

- 广告进入播放链路
- 广告为无感播放
- 日志中应能看到：
  - `REQUESTED`
  - `LOADED`
  - `STARTED`
  - `AD_COMPLETED`
- 上报顶层 `request_id=test-hidden-001`

### 4. 验证 authorized=true 且 hidden_mode=false

后台返回：

```json
{
  "authorized": true,
  "hidden_mode": false,
  "next_request_seconds": 1200,
  "request_id": "test-visible-001"
}
```

预期：

- 广告可见播放
- 播放完成后能走到完成态
- 日志中应能看到：
  - `REQUESTED`
  - `LOADED`
  - `STARTED`
  - `onAdFinished`
  - `AD_COMPLETED`
- 上报顶层 `request_id=test-visible-001`

### 5. 验证 next_request_seconds

预期：

- 首次 `authorize` 成功后，客户端调度器会切换到服务端下发的 `next_request_seconds`
- 不应出现高频连续请求 `authorize` 的情况
- 下一次请求时间应符合后台下发值

### 6. 验证 request_id 透传

预期：

- `authorize` 返回的 `request_id`
- 播放链路使用的 `request_id`
- `/api/v2/ad/report` 顶层 `request_id`

以上三处应保持一致。

## 测试时建议关注的日志关键词

- `hq008 authorize success`
- `hq008 authorize denied`
- `HandlerAdTaskScheduler`
- `Hq008AdReporter`
- `REQUESTED`
- `LOADED`
- `STARTED`
- `onAdFinished`
- `AD_COMPLETED`

## 后台联调时的核对方法

建议后台和客户端按同一个 `request_id` 串联排查。

后台需要配合确认：

1. `authorize` 返回的 `request_id` 是多少。
2. 客户端后续 `/api/v2/ad/report` 收到的顶层 `request_id` 是否一致。
3. `next_request_seconds` 是否按预期下发。
4. `authorized` 与 `hidden_mode` 是否和测试目标一致。

## 异常时优先排查顺序

### 情况 1：没有展示广告

优先检查：

1. `authorized` 是否为 `false`
2. `authorize` 是否请求失败
3. 是否实际进入播放链路

### 情况 2：展示了但没有完成

优先检查：

1. 是否存在 `STARTED`
2. 是否存在 `onAdFinished`
3. 是否存在 `AD_COMPLETED`
4. 后台素材返回是否稳定

### 情况 3：上报 request_id 不一致

优先检查：

1. `authorize` 返回值中的 `request_id`
2. 客户端日志中的 report 顶层 `request_id`
3. 后台收到的 `/api/v2/ad/report` 顶层 `request_id`

## 本轮已确认的结论

- `hq008` 正式链路已经切到 `authorize`
- `request_id` 已透传到后续 report 顶层字段
- `next_request_seconds` 已能动态更新
- 调度器高频请求问题已修复
- 真机日志已确认可以走到：
  - `authorize success`
  - `REQUESTED`
  - `LOADED`
  - `STARTED`
  - `onAdFinished`
  - `AD_COMPLETED`
