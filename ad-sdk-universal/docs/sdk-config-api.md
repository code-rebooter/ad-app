# SDK 配置接口

`POST https://api.kytira.cc/api/v2/ad/sdk/config`，JSON。

请求：

```json
{
  "sdk_key": "客户接入key",
  "ad_version": 123,
  "ad_version_name": "1.2.3",
  "sdk_version": "1.0.0"
}
```

版本字段由 SDK 自动填写，宿主版本使用真实值。

成功响应：

```json
{
  "code": 100000,
  "data": {
    "refresh_interval_seconds": 86400,
    "google": {
      "channel_id": "Google渠道号"
    },
    "tcl": {
      "channel_id": "TCL渠道号",
      "package_name": "com.example.registered",
      "app_name": "Registered App",
      "version_name": "2.0.10",
      "version_code": 10,
      "signature_md5": "d2a9b2a8a9e0af740267c0bc356dc1a4",
      "app_key": "TCL原厂AppKey",
      "project_id": 213,
      "partner_name": "TCL登记partner"
    }
  }
}
```

除 refresh_interval_seconds 可选，其余字段必填。字符串不能为空；version_code 为整数 ≥ 0，project_id 为整数 > 0，signature_md5 为 32 位十六进制。两路渠道独立；TCL 登记版本与真实宿主版本分开。

refresh_interval_seconds 为正整数秒：3600＝1 小时、86400＝1 天、604800＝7 天。缺失、非数值、非整数、非正数或超出毫秒计时范围时默认 86400。

SDK 已实现按 key 持久缓存配置和成功获取时间，初始化优先使用有效缓存，到期独立刷新；不按每轮广告重新请求。应用持续运行也会刷新，重启不重置时间。设备恢复后通过本地检查在 1 分钟内检查到期，播放前也会检查，不累计补发多次。

每次成功取配置后使用最新间隔；后台改间隔要到客户端下次成功请求时生效。刷新失败保留旧配置，按 1、2、4 分钟逐步退避，最多每小时重试。首次没有有效缓存且请求失败，初始化报错，宿主可重试。

更新在当前轮结束后、下一轮开始前应用。若上一轮原厂初始化或 CMP 同步仍未结束，播放返回等待配置切换的错误，宿主稍后重试；不会同时切换原厂登记。未完成的 CMP 补报保留原渠道。

HTTP 非 2xx、code 非 100000 或必填配置无效，均不替换有效缓存。业务总开关仍由每路 sdk/flow-control 实时控制。
