# 后台接口变更

通用 SDK `1.0.0`；全部为 JSON POST，前缀 `/api/v2/ad/`。

所有 7 个接口携带：

| 字段 | 类型 | 值 |
| --- | --- | --- |
| ad_version | integer | 真实宿主 versionCode |
| ad_version_name | string | 真实宿主 versionName |
| sdk_version | string | SDK 发布版本，当前 1.0.0 |

| 接口 | 变更 |
| --- | --- |
| sdk/config | 请求 sdk_key 和上述版本字段；响应增加可选 refresh_interval_seconds。见[配置接口](sdk-config-api.md)。 |
| sdk/flow-control | 增加 androidid、ad_version_name、sdk_version。 |
| sdk/authorize | 增加 androidid、ad_version_name、sdk_version；Google 再带 rdid，响应增加 gam_config。 |
| consent-popup | 增加 androidid、ad_version_name、sdk_version。 |
| consent-report | 增加 androidid、ad_version_name、sdk_version。 |
| report | 增加 androidid、ad_version_name、sdk_version。 |
| consent-log-report | 增加 androidid、ad_version_name、sdk_version。 |

6 个原本传 mac 的接口均新增 androidid，Google、TCL 两路都传。androidid 为真实 Settings.Secure.ANDROID_ID，读取失败传空字符串；原有 mac、uuid、ifa 字段保持。

只有 sdk/config 上传 sdk_key，不传 mac、androidid。其他接口继续传对应的 Google 或 TCL channel_id。

## Google 取链接并入授权

新通用 SDK 不再调用 google-gam/resolve。后台把原取链接逻辑放入 Google 的 sdk/authorize 分支。

Google 授权请求在原有字段基础上新增：

```json
{
  "rdid": "8c20c0d5-0e3c-4d5b-8cf1-34b615cc4cd4",
  "androidid": "0123456789abcdef"
}
```

rdid 为客户端生成并持久复用的 UUID。后台决定如何拼接广告链接。

Google 授权成功响应：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "authorized": true,
    "request_id": "google-request-001",
    "client_ip": "203.0.113.10",
    "next_request_seconds": 300,
    "ad_callback_timeout_seconds": 60,
    "hidden_mode": false,
    "sound_mode": true,
    "gam_config": {
      "ad_tag_url": "https://example.com/gam-vast",
      "ad_load_timeout_ms": 10000,
      "ad_startup_timeout_ms": 5000
    }
  }
}
```

授权和播放策略放在 data；广告链接及两个加载超时放在 data.gam_config。code 支持 200、100000；request_id 缺失沿用客户端请求 ID。两个毫秒超时可省略，默认 20000、35000。

authorized=false 不播放；Google 授权成功但没有链接也不播放，不再请求 resolve。TCL 授权不需要 gam_config、不上传 rdid，但同样上传 androidid，仍由原厂取链接。

SDK 兼容此前 data 下平铺的广告字段；返回 gam_config 时仅从其中读取广告字段。

后台保留旧 google-gam/resolve 接口供原有 SDK 使用，并兼容旧包缺少新增字段的请求。
