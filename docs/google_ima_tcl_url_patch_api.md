# IMA → TCL 参数调整开关

适用渠道：`google_ad_tv_desktop_v260904_1`、`google_ad_tv_desktop_v260904_x98k`、`google_ad_tv_desktop_localcalendar`、`google_ad_tv_desktop_kaneshort`。接口仍为 `POST /api/v2/ad/google-gam/resolve`，在现有 `data` 对象内新增 `ima_tcl_url_patch`。这是单个开关，不是后台规则列表。

## 返回类型与含义

后台建议统一返回 JSON 整数 `0` 或 `1`。客户端读取原始 JSON 值后再解释，不直接用 Gson 的 Boolean 字段接数字。

| 接口值 | 是否执行之前那套 TCL 专用调整 |
|---|---|
| `0`、`"0"`、`false`、`"false"` | 否 |
| `1`、`"1"`、`true`、`"true"` | 是 |
| 字段缺失、`null`、其他数字、无效字符串、对象、数组 | 否（默认关闭） |

数值 `1.0` 与整数 `1` 等值，也识别为开启；字符串会先去掉两端空白，true/false 不区分大小写。后台正式协议仍以整数 0/1 为准，不需要依赖这些兼容写法。非法值不会使整个广告配置解析失败。

## 执行顺序

```text
后台 ad_tag_url → IMA Core 生成完整 URL（广告 HTTP 前拦截）
                         ↓
               ima_tcl_url_patch 开关
             ┌───────────┴────────────┐
          0 / 未传                    1
       保留 IMA URL             执行原来的 TCL 调整
             └───────────┬────────────┘
                         ↓
             统一执行现有补丁的 URL 参数修正 + 空 rdid 兜底
                         ↓
               释放 IMA → TCL.setVastUrl
                         ↓
                TCL 广告 HTTP / 解析 / 播放 / 上报
```

开关关闭只跳过 TCL 专用调整；最后的公共参数修正始终执行。不存在绕过公共修正的分支。

## 后台返回示例

关闭 TCL 调整，仍执行公共修正：

```json
{
  "code": 100000,
  "msg": "success",
  "data": {
    "ad_tag_url": "https://pubads.g.doubleclick.net/gampad/ads?iu=/your/ad_unit&output=vast&env=vp&gdfp_req=1&correlator=",
    "ad_load_timeout_ms": 20000,
    "ad_startup_timeout_ms": 35000,
    "ima_tcl_url_patch": 0
  }
}
```

要试验之前那套 TCL 调整，只把上述字段改成：

```json
"ima_tcl_url_patch": 1
```

每次 resolve 返回的开关只用于本次请求，既不持久化也不沿用上一轮值。下一次广告机会重新读取接口，所以后台从 1 改回 0 后，下一次请求即可切回；正在播放的广告不被中途改写。

## 两层处理的区别

| 处理层 | 开关是否控制 | 实际范围 |
|---|---|---|
| 之前那套 TCL 调整 | 是 | 复用现有 `GoogleImaTclUrlPolicy`：按固定清单清理 IMA 字段、保留相关数据与后台业务字段、恢复 output、校准播放状态和应用信息。 |
| 现有公共补丁的 URL 参数修正 | 否，始终执行 | 直接复用 `HaierAarUaPayloadNormalizer.kt` 的 `normalizeAarUrlUa`。识别 UA、型号、品牌、厂商、系统版本、SDK 版本、Build 等现有字段别名，使用 TCL 当前的公共运行时 bridge 提供一致的有效值；随后执行本文的空 rdid 兜底。 |

公共规则从原文件生成，只有运行时 bridge 名称替换成 `Hq008XhsxAarRuntimeBridge`；字段别名、修正判断、编码处理不另维护一份。不引入海尔网络审计或业务入口。

除下面说明的空 `rdid` 兜底外，没有出现的设备字段不会因为公共修正而凭空新增；未识别的 query 参数、重复项及其原始编码保持不变。`ms`、`uach` 等封装数据不属于现有函数可识别的明文身份字段，因此原样保留，不拆改内部数据。这一点与“整个 URL 必须经过公共修正”并不冲突：会检查整条 query，仅修改已有规则识别的字段。

开关开启时仍执行此前的实验清理方案，包括已讨论过的会话/测量字段。这个开关提供后台对照能力，不代表已经证明删除这些字段对正式填充、频控、测量或收益没有影响。

## 空 rdid 的最终兜底（当前交付 1.0.11 / versionCode 12）

两个开关分支在现有公共修正之后、交给 TCL 之前，都会检查最终 URL 的 `rdid`：

- 已有非空值：保持原值，也不改其 `idtype`。
- 缺失、空串或只有空白：读取本应用 `google_tcl_ad/aid`；没有时按 TCL 原厂方式 `UUID.randomUUID().toString()` 生成并保存，后续请求和进程重启继续复用。将该 UID 填入 `rdid`，同步设置 `idtype=aaid`，不新增名为 `uid` 的参数。
- 最终 URL 明确设置 `is_lat=1`、`ltd=1`、`tfcd=1`、`tfua=1` 或 `dnt=1` 时不补标识（也兼容对应 true 写法）。已有全零 `rdid` 不替换。不会把限制跟踪状态改成允许。

空值兜底使用 TCL 原厂的“本地持久 UUID + aaid 类型”组合；自建 UID 不等于系统 Google 广告 ID，也不从后台旧 URL 恢复旧广告 ID。中间构建 1.0.2 曾使用 dpid，后按本次要求改为 aaid；正式交付版本接续同包已有 code 11，使用 1.0.11 / versionCode 12，不迁移或重新生成已经保存的 UID。`ms/uach` 与其他参数片段保持原始编码；清除应用数据或卸载后 UID 可能重新生成。

`AD_SDK_REQUEST.adLog.final_url` 记录完成这一步后真正交给 TCL 的 URL，仍随流程终态一次汇总上报；FILLED 状态上报保持现有流程。

参考：UID 生成及持久化行为核对自仓库固定版本 `vendor/tcl-base-2.8.02.aar` 的 `DeviceInfoGeneratorImpl` 构造器；同一类的 `getDeviceType()` 原厂固定返回 `aaid`。

## 日志与验证

业务诊断记录：实际开关 0/1、公共修正是否发生变化，以及输入标签、原始 IMA、公共修正前、最终 URL 的哈希和长度；不把完整信号串写入正式业务日志。

Debug 验证页保留上述各阶段完整 URL，供设备抓取核对。测试覆盖接口的整数/字符串/布尔兼容、缺失默认、切回关闭、两个分支的公共修正顺序、封装信号与编码保留，以及原来的 TCL 调整规则。

当前仓库只有客户端，没有后端服务或后台页面代码。本次客户端接入完成后，后端仍需按本文增加返回字段；未在本次操作中修改线上接口配置。
