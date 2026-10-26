# 应用广告流程接口字段同步（2026-10-23）

本次改动范围为 app 客户端的现有接口调用；不修改后台服务，也不新增接口。字段同步本身不改变通用 SDK 库本体；当前全部 Google flavor 固定使用独立 URL AAR 并共用这些字段。

## androidid 覆盖

| 接口 | 当前应用处理 |
| --- | --- |
| `/api/v2/ad/sdk/flow-control` | 新增 `androidid` |
| `/api/v2/ad/sdk/authorize` | 新增 `androidid` |
| `/api/v2/ad/consent-popup` | 新增 `androidid` |
| `/api/v2/ad/consent-report` | 新增 `androidid` |
| `/api/v2/ad/consent-log-report` | 新增 `androidid`，同时新增 `request_id` |
| `/api/v2/ad/report` | 普通广告状态和触摸点击的两个调用点均新增 `androidid` |
| `/api/v2/ad/google-gam/resolve` | 应用当前全部 Google 渠道的 resolve 调用已删除，不再发送；其他独立 SDK 不在此次范围内 |
| `/api/v2/ad/vast` | app 内未找到调用点，忽略 |
| `/api/v2/ad/google-gam/patch` | 同日按新增文档接入；包含 androidid、mac、request_id 和单独的本地 rdid，见 [patch 协议](google_gam_patch_api.md) |

字段名统一为 `androidid`，值为真实 `Settings.Secure.ANDROID_ID`；读取失败或返回 null 时仍上传空字符串。原有 mac、uuid、ifa、ad_version 等字段保留。该值只加入自有后台请求，不额外写入 Google 广告 URL。

## 全部 Google 渠道的其他字段

2026-10-23 渠道合并后，字段覆盖全部 12 个 Google flavor。原四个 IMA → TCL 渠道与历史桌面、锁屏、tcl_test 渠道均走同一实现，见 `app/src/google_ad_tv_desktop/README.md`。

七个当前 IMA/TCL 流程接口（flow-control、authorize、consent-popup、consent-report、consent-log-report、report、google-gam/patch）额外上传：

- `ad_version_name`：实际宿主 APK 的 `BuildConfig.VERSION_NAME`。
- `sdk_version`：本链路实际执行广告的 TCL SDK 版本 `2.8.02`；本应用没有切入通用 SDK，不能填写其 `1.0.0`。
- `sdk/authorize` 另外上传 `rdid`：复用 `google_tcl_ad/aid` 的本地持久 UID；已有值直接复用，没有时生成并保存一次。它与现有 TCL 空 ID 兜底共用存储和读取实现。这里是自有后台字段，不把它称为 GMS 广告 ID，也不改变完整广告 URL 中原有的 GMS 取值流程。

## TCL 渠道的版本字段同步（2026-10-26）

TCL 渠道同样上传 `ad_version_name` 和 `sdk_version`：

- `ad_version_name`：当前宿主 APK 的 `BuildConfig.VERSION_NAME`。
- `sdk_version`：实际 TCL 执行 SDK 版本 `2.8.02`。

覆盖 `hq008`、`hq008XHSX`、`tcl_aishang`、`ad_ytx01`、`ad_ytx01_sxk`、`ad_ytx01_jx`、`ad_album_101_001`、`hq008Noneu`、`hq008Noneuc2`、`tcl_poly` 共 10 个渠道。

现有 flow-control、authorize、consent-popup、consent-report、consent-log-report、report 六类接口均调用公共参数方法，普通广告状态及触摸点击的 report 调用都生效。此前已接入的 `androidid` 和日志 `request_id` 继续保留；`rdid` 仍仅用于 Google 授权。LSAP 渠道使用独立 SDK，此次不将其标记为 TCL `2.8.02`。

## consent-log-report 的 request_id

每轮流程开始分配本地 request_id，授权请求复用它；授权返回非空服务器 request_id 后，本轮后续状态及日志沿用服务器值。尚未到授权就终止的流程仍有本地 ID。

日志上传使用本轮终态快照保存的 request_id，不在发送时读取下一轮的可变 ID。步骤裁剪不丢 ID；过期授权响应不能改名新一轮。上传频率保持原有一次流程终态汇总，日志开关仍生效。

## 验证范围

此前接口接入时的定点检查覆盖授权配置、字段值/空值、UID 复用以及日志 ID 的生命周期与过期回调；未连接设备或请求线上接口。编译和检查原始记录保存在仓库外研究归档中。

2026-10-26 TCL 补齐后的公共字段测试已通过：`./gradlew :app:testGoogle_ad_tv_desktop_v260904_1DebugUnitTest --tests com.smart.android.ad_app.Hq008ApiRequestFieldsTest --offline --console=plain`，6 项测试全部成功。覆盖 10 个 TCL 渠道、12 个 Google 渠道，以及 Android ID 不可用、LSAP/无关渠道边界和 UID 复用；公共参数方法的 7 个调用点覆盖上述六类 TCL 接口。
