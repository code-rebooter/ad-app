# Google 链路对齐核对

2026-10-23 补充：当前应用全部 Google 渠道已统一为 IMA 取链 → 公共修正 → 后台 patch → TCL，旧 IMA 播放和纯 TCL 实验执行代码已删除。授权合并取链见 [当前协议](google_ima_tcl_authorize_api.md)，字段见 [请求字段同步](google-ad-backend-fields-20261023.md)。以下保留 10 月 16 日的历史核对结果，不代表当前实现或全部独立 SDK 已对齐。

核对日期：2026-10-16。对象：独立 Google 实验版、通用 SDK 的 Google 分支。
核对依据：实际构建源集、请求构造、响应消费、播放器装配与回调代码。本轮未修改业务代码，未重新打包或抓包。

**结论：两套尚未完整对齐。配置接口应继续仅供通用 SDK 使用。**

## 1. 六个原有接口的新增请求字段

以下接口前缀均为 `/api/v2/ad/`。表内列出通用 SDK 已实现、独立实验版对应接口尚未上传的字段。

| 接口 | 独立实验版对应接口缺少的字段 |
| --- | --- |
| `sdk/flow-control` | `androidid`、`ad_version_name`、`sdk_version` |
| `sdk/authorize` | `androidid`、`ad_version_name`、`sdk_version`、`rdid` |
| `consent-popup` | `androidid`、`ad_version_name`、`sdk_version` |
| `consent-report` | `androidid`、`ad_version_name`、`sdk_version` |
| `report` | `androidid`、`ad_version_name`、`sdk_version` |
| `consent-log-report` | `androidid`、`ad_version_name`、`sdk_version` |

字段取值：

| 字段 | 通用 SDK 当前实现 |
| --- | --- |
| `androidid` | 真实 `Settings.Secure.ANDROID_ID`，读取失败传空字符串 |
| `ad_version` | 宿主 `versionCode`，原有字段仍保留 |
| `ad_version_name` | 宿主 `versionName` |
| `sdk_version` | SDK 自身版本名称，通用 SDK 当前为 `1.0.0` |
| `rdid` | 首次生成随机 UUID 并持久保存，以后复用；只在 Google 授权请求上传 |

独立实验版并非没有 `rdid`、`androidid`：这两个字段已在其独立的 `google-gam/resolve` 请求上传，但尚未进入 `sdk/authorize`。
两套 `rdid` 的生成与复用规则相同；它不是读取系统广告 ID。若同步接口，应复用现有存储，避免另生成一份。
同步 `sdk_version` 时应使用实际交付 SDK 的自身版本，不能直接套用另一模块的版本值。

## 2. 广告链接的返回位置不同

| 项目 | 独立实验版 | 通用 SDK 的 Google 分支 |
| --- | --- | --- |
| 取链接流程 | `sdk/authorize` 后再请求 `google-gam/resolve` | 直接读取授权响应，无独立 resolve 请求 |
| 链接返回位置 | `resolve.data.ad_tag_url` | `authorize.data.gam_config.ad_tag_url` |
| `gam_config` 中的超时字段 | 不读取授权响应的该对象 | 解析 `ad_load_timeout_ms`、`ad_startup_timeout_ms` |
| 授权成功但没有链接 | 仍需后续 resolve 才能取得链接 | 本次跳过，不再请求 resolve |

独立实验版的授权响应模型没有 `gam_config`。后台仅把链接迁移到该对象时，现有独立实验版无法使用这个新位置。
通用 SDK 还兼容旧的授权响应平铺广告字段；存在 `gam_config` 时，以该对象为准。
两套播放适配层均使用原引擎内部超时配置，并未把上述两个后台超时值设置给播放器。

## 3. 已有字段的取值及上报行为也有差异

| 项目 | 独立实验版 | 通用 SDK 的 Google 分支 |
| --- | --- | --- |
| 授权请求的 UA | 实际实验构建命中诊断分支，发送 `ext`，不发送顶层 `ua` | 发送顶层 `ua`，没有同一份 `ext` |
| 授权的 `make`、`model`、`osv` | 经现有设备信息修正方法取得 | 直接读取系统 `Build` 值 |
| 状态上报的 `make`、`model` | 经现有设备信息修正方法取得 | 直接读取系统 `Build` 值 |
| `LOADED` / `STARTED` 状态上报 | 跟随原回调，可能重复 | 过滤重复后上报 |
| 取消广告 | 播放适配层记录取消日志并释放 | 会话结束同时按 `AD_ERROR / CANCELLED` 上报 |
| 日志内容 | 应用级流程汇总，含本地化步骤与原始消息 | 会话级流程汇总，字段结构不同 |
| 设备信息采集 | 多处请求时读取 | 通用设备信息在进程内首次采集后缓存；Google 授权的 `rdid`、`androidid` 单独读取 |

独立实验版授权请求的 `ext` 当前包含：

`ua_original`、`ua_effective`、`webview_ua`、`ua_observed`、`ua_aar_cached`、`ua_aar_effective`、`ua_drift_detected`、`ua_aar_drift_detected`、`ua_repaired`、`ua_checked_at_ms`。

这些 UA 字段属于自有后台授权请求；不能据此判断广告 HTTP 请求头缺少 UA。
两套原引擎的公共 UA、设备身份及设备型号修正方法已复用；上述差异发生在给自有后台采集设备字段的位置。

两套均保留状态上报和日志上报，`popup_log_enabled=false` 均关闭流程日志上传，不关闭独立状态上报。字段缺失时，两套当前均默认允许日志上传。

## 4. 播放外围与门禁差异

| 项目 | 独立实验版 | 通用 SDK 的 Google 分支 |
| --- | --- | --- |
| 宿主回调超时起点 | 调用原引擎 start() 后；不包含前面的 resolve 请求 | 会话开始、flow-control 请求之前；包含整段流程 |
| 默认回调超时 | 180 秒 | 180 秒 |
| 服务端回调超时覆盖 | 接受 30–600 秒 | 接受 30–600 秒，计时仍从会话开始算 |
| 启播 | 每次收到加载回调立即调用 start | 过滤重复，等待容器尺寸有效后仅调用一次 |
| 显示容器 | 调整宿主容器透明度，清理宿主子视图 | 使用 SDK 自己的子容器，开始播放且首帧到达后显示 |
| 结束释放 | release 后清理容器 | 按会话持有关系 stop、release 各一次，再移除自有子容器 |
| 原引擎请求入口绑定 | 临时切换 HTTP 单例 | 同时切换 HTTP 单例和广告对象缓存的请求器，处理双广告来源切换 |
| 应用调度 | 自带悬浮窗、外部播放状态门禁及请求周期调度 | 宿主提供容器；SDK 返回下次请求间隔供宿主使用 |

两套均保留总开关和授权；`skip_cmp=true` 跳过 CMP，要求 UMP 执行的动作均按无法执行处理。两套 Google 决策均先去除动作字符串首尾空白并转大写，通用 SDK 在响应解析层完成这一步。
当前两套构建配置均排除 IMA、Media3、UMP。

## 5. 已复用的部分与同步范围

- 下层均使用同一版本的原引擎执行 VAST、Wrapper、视频播放和 XML 跟踪；不是两套自写解析器。
- 广告链接输入对象在去除包名和模块导入差异后，代码相同；公共补丁使用同一处理方法。
- 原引擎内部解析相同，不代表接口、回调过滤、计时和宿主管理也相同。
- `sdk/config`、密钥注册、双来源渠道配置、低频配置刷新保留为通用 SDK 专属功能。
- 对齐独立实验版时，接口字段及授权取链接协议需要单独同步；播放外围差异需要明确处理，不能只补字段就宣称全部对齐。
- 独立实验版复用了其他渠道的接口实现。后续改动须限定实验构建，避免影响原有渠道。

以上请求字段发给自有后台。两套均未在客户端额外把 `rdid`、`androidid` 等字段拼到 Google 广告链接上；最终链接由后台返回。
