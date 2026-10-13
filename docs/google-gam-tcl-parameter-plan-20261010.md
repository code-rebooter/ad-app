# Google GAM 直连 VAST：待补参数与对照记录

日期：2026-10-10（Asia/Shanghai）。适用渠道：九猫 `GOOGLE_AD_TV_DESKTOP_JM`，广告执行使用 TCL 路径，不使用 IMA。

本文仅整理待实施方案。此次没有修改广告请求代码、后台配置或 APK，也没有执行线上部署。当前版本先作为未补参数的对照；是否补参数及何时实施，之后根据记录决定。

## 2026-10-13 实际实施范围

仅 TCL 实验渠道的自有 `google-gam/resolve` 请求新增两个 string 字段，原有 `channel_id/mac` 保留：

| 字段 | 当前实现 |
| --- | --- |
| `rdid` | 按 TCL 方式，先读自有 `google_tcl_ad/aid`；为空时使用 `UUID.randomUUID().toString()` 生成并保存，后续复用。 |
| `androidid` | 读取 `Settings.Secure.ANDROID_ID`；读取失败或没有值时上传空字符串。 |

后台决定是否及如何拼接广告参数，客户端仍原样交给 TCL 执行后台返回的 `ad_tag_url`。未新增 GAID、限制跟踪状态采集或 `idtype` 等字段；自有接口字段 `rdid` 使用自建 UUID，不代表已证实雷鸟向 Google 原样转发该 UUID。

以下章节保留 2026-10-10 的参数研究与历史对照，属于参考方案，不代表全部已实施或已经确认的 IMA 固定追加清单。

## 1. 先看清单

以用户提供的正式标签为起点：**程序化请求的核心增补是 5 个新增字段，并填好已有的空 `correlator`，合计处理 6 项。**这不是“少一项就一定拿不到 VAST”的意思，也不是所有场景都固定补 8 项。[G1][G2][G3]

| 参数 | 当前正式标签 | 后续处理 | 值从哪里来 |
| --- | --- | --- | --- |
| `url` | 没有；已有 `description_url` | 增加该字段。按本次 IMA 行为复用 `description_url` 的值 | 现有标签中的 `https://ghtfor.cc`；不用再提供一个新地址 |
| `vpa` | 没有 | 自动播放填 `auto`；点击触发播放填 `click` | 本次实际播放方式 |
| `rdid` | 没有 | 有可用、允许使用的广告 ID 时传真实 GAID | 设备的 Google 广告 ID 接口 |
| `idtype` | 没有 | `rdid` 使用 Android 广告 ID 时填字符串 `adid` | ID 的类型；不是另取一个设备编号 |
| `is_lat` | 没有 | 设备限制广告跟踪时填 `1`，未限制时填 `0` | 广告 ID 接口返回的限制跟踪设置 |
| `correlator` | 已有，但为空 | 填随机正整数；同一页面／播放会话复用，新会话换值 | 客户端会话生成，不能把后台缓存中的一个值跨设备共用 |

`rdid/idtype/is_lat` 的可用性取决于设备。取不到有效 GAID，不生成随机 UUID 冒充；无法读取限制状态，不默认写成 `is_lat=0`。字段缺失不等于所有基础广告都不能投放，但会缺少 Google 程序化要求的信号。[G1][G3][G4]

### 按设备／库存追加的项

| 参数 | 何时适用 | 取值／处理 |
| --- | --- | --- |
| `devt` | 机顶盒库存 | 官方目前只列 `stb`；其他设备不填此值 |
| `dth` | 设备类型提示 | 机顶盒与 `devt=stb` 配合填写 `7`；它在全参数表中单独列为推荐项，不能称所有 VAST 请求都统一必填 |
| `ott_placement` | Google 要求声明的 CTV 展示位置 | `1`=暂停广告，`2`=电视首页／桌面，`3`=画中画，`5`=叠加；普通流内广告不要求此项。按真实位置填。[G2] |
| `msid` | 推荐补齐应用身份 | Android 应用包名：`io.android.launcher.tv.desktop` |
| `an` | 推荐补齐应用身份 | 应用真实、可读的名称，不能凭空指定 |

以上适用条件来自 Google 官方说明。[G1][G2] 如果是机顶盒，再处理 `devt/dth` 两项；如果广告实际在电视首页，再处理 `ott_placement`。不要将这些条件项与核心清单混成一个全设备通用数字。

## 2. TV 盒子和投影仪怎么填

`dth` 官方值：[G2]

| 值 | 设备类型 |
| --- | --- |
| `1` | 功能手机 |
| `2` | 智能手机 |
| `3` | 桌面电脑 |
| `4` | 平板 |
| `5` | 联网电视／CTV |
| `6` | 游戏主机 |
| `7` | 机顶盒 |

- X88 等机顶盒：`devt=stb&dth=7`。
- 投影仪外接盒子，广告运行在盒子上：按盒子填写。
- 投影仪内置 Android TV／Google TV，广告运行在内置系统上：按 CTV 归类可使用 `dth=5`，不传 `devt=stb`。这是根据 CTV 分类作出的判断；官方没有独立的“投影仪”枚举。
- 普通 Android 投影仪：仅有 Android 系统不足以确定它属于哪类库存，具体型号／投放位置确定后再定，不统一写死成盒子。

## 3. 值怎样获取

### `url`

本次 IMA 3.35.1 实际请求显示：

| 输入 | IMA 最终发送的 `url` |
| --- | --- |
| 沿用用户正式标签参数，带 `description_url=https://ghtfor.cc` | `https://ghtfor.cc` |
| 官方测试标签，不带 `description_url` | `io.android.launcher.tv.desktop.adsenseformobileapps.com/` |

所以针对现有正式标签，待实施做法是**保留 `description_url`，再把同一个已解码的值填到 `url`**。现有值已经有了，缺的是最终请求中的字段名；不是要求再找网站。`url` 和 `description_url` 是不同字段。[G1][G2]

```text
description_url=https%3A%2F%2Fghtfor.cc
url=https%3A%2F%2Fghtfor.cc
```

这一做法复现本次 IMA 请求的取值。地址仍应真实代表相应库存；复用它本身不能证明页面内容／库存配置已经满足 Google 要求。[G1]

### `rdid/idtype/is_lat`

不用 IMA也能读取。独立调用 Google Play 服务的 `AdvertisingIdClient`，在后台线程执行：[G4][G5]

```kotlin
// 获取方式示例；须在后台线程执行，并处理不可用情况。
val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
val rdid = info.id
val idtype = "adid" // 仅当 rdid 确实使用 Android 广告 ID 时
val isLat = if (info.isLimitAdTrackingEnabled) "1" else "0"
```

目标 Android 13 及以上需声明 `com.google.android.gms.permission.AD_ID`。没有 Google Play 服务、调用失败、ID 为空或全零时，不把该值当成可用 GAID。API 正常返回的限制跟踪状态可照实使用；调用失败不等于“未限制”。`is_lat=0` 仅表示未开启设备级限制，不代表已获得所有隐私同意。[G3][G4][G5]

本次 X88 的 **IMA 最终请求**记录为：

| 参数 | 2026-10-10 实测值 |
| --- | --- |
| `rdid` | `43702484-cff0-40b3-a680-38809cfd7dd0` |
| `idtype` | `adid` |
| `is_lat` | `0` |

这些是历史实测值，不是后续所有设备的配置常量。当前 TCL 实验版尚未增加对应读取／补值逻辑。

## 4. TCL 标识与 Google 标识的区别

核对 TCL 2.8.02 原厂取参代码后确认：`DeviceInfoGeneratorImpl` 默认读取本地 `tcl_ad/aid`，没有值时生成并保存随机 UUID；`getDeviceType()` 固定返回 `aaid`。`VastAdRequestParams` 把 `getAid()` 的结果用于 `did`。

因此，雷鸟标签中的 `did` 与 `ifa_type=aaid`，不能仅凭字段名字认定为真实 GAID。雷鸟服务端后续如何处理该标识，现有客户端抓包没有证明。

**Google 的 `rdid` 不使用这套随机 UUID 代替 GAID。**TCL 原厂广告请求关联用的 `requestId` 也不是 GAID，更不是 Google `correlator`。[G3]

当前实验版仍由 TCL 原厂在 VAST URL 上追加 `requestId`；本次没有改变此行为。后续如调整 Google 参数，应将这件事单独记录，不把“原厂会追加”称为“Google 必需”。

## 5. 当前版本与补参数版本怎样比较

### 当前对照版本 A

现有交测文件：`google-jm-tcl-1.0.9-release.apk`，版本 `1.0.9 / 10`，SHA-256：

```text
09c48d52645b303268c9a2d53da235d20f714d68df5cff90b8460f602f736b17
```

这是此前交测文件的身份记录，不表示本次已经发到线上。现有实验链路保留后台标签参数，原厂追加 `requestId`，没有自动补齐上述 Google 字段。

用户提供的正式标签如下；此前后台曾切换到测试位，实际观察前应记录 `resolve` 最终返回的广告位，不能仅凭后台口头配置判断缓存已经更新：

```text
https://pubads.g.doubleclick.net/gampad/ads?iu=/23334778486/TVDesktop/video-1&description_url=https%3A%2F%2Fghtfor.cc&tfcd=0&npa=0&ad_type=audio_video&sz=1x1%7C300x250%7C320x480%7C400x300%7C640x360%7C640x430%7C640x480&gdfp_req=1&unviewed_position_start=1&output=vast&env=vp&impl=s&plcmt=1&vpmute=0&app_package=io.android.launcher.tv.desktop&correlator=
```

### 后续待决定的版本 B

推荐由客户端在进入 TCL 取链响应桥接前，处理 Google 域名标签的补值：现有 `url` 信息来自标签；GAID、限制状态和会话值来自本设备／本次会话。后端不需要另找 `url`，现有 `resolve` 请求合约也不必仅为这些补值而先改动。

这一位置是待实施方案，不是已经完成的代码。用 URL 查询参数工具逐项设置，避免重复字段和重复编码；不按文字拼接包含未编码值的字符串。

已有字段也要核对实际含义：`vpmute` 对应真实是否静音；`plcmt` 对应真实广告位置，电视首页不能直接认定为 `plcmt=1` 流内库存。[G1][G2]

不要复制 IMA 自身的 `sdkv/js/mpt/mpv/omid_p/sdk_apis`、长信号 `ms` 等值来冒称 IMA 或 OM SDK 集成。新增 PAL／OM SDK 是另一项能力工作，不包含在本参数补值方案中。

### 对照时记录什么

保持真实、可见的展示，记录两组的广告位、时间段、设备／网络、应用版本和实际播放状态。除待比较参数外，尽量使用相同配置；若更改静音或展示位置，也要单独记下来。

| 记录 | 能说明什么 |
| --- | --- |
| `resolve` 实际返回标签；进入 Google 的最终字段 | 确认正式／测试广告位，以及参数是否实际生效 |
| HTTP 状态、VAST 是否含 `Ad`、错误信息 | 区分网络错误、空广告和解析错误；HTTP 200 不等于有广告 |
| 加载、开始、完成／中断及真实 XML 跟踪 | 判断广告执行链路是否正常 |
| GAM 报表中的请求、填充、展示和后续调整 | 判断正式库存的实际结果；与客户端状态分别核对 |

官方测试位能出广告，不证明正式位必有填充；当前不补也可能有广告。补齐字段的意义是提供 Google 程序化要求的请求信息，不能承诺填充或收益。隐藏播放／仅收到跟踪 HTTP 200，不能作为有效展示与结算的证明。

## 6. 官方依据与本地证据

官方文档核对日期：2026-10-10。

- [G1] Google Ad Manager，CTV 参数及适用条件：`https://support.google.com/admanager/answer/15788066?hl=en`
- [G2] Google Ad Manager，全量视频标签参数：`https://support.google.com/admanager/answer/10678356?hl=en`
- [G3] Google Ad Manager，可重置设备标识与会话 ID：`https://support.google.com/admanager/answer/6238701?hl=en`
- [G4] `AdvertisingIdClient`，读取方式、后台线程与失败情况：`https://developers.google.com/android/reference/com/google/android/gms/ads/identifier/AdvertisingIdClient`
- [G5] `AdvertisingIdClient.Info`，广告 ID、限制状态与 AD_ID 权限：`https://developers.google.com/android/reference/com/google/android/gms/ads/identifier/AdvertisingIdClient.Info`

本地证据：

- [IMA 最终请求与完整参数](/Users/zy/Documents/Codex/ad-app/artifacts/ima-final-request-audit-20261010/report.md)
- [IMA 四组测试结果](/Users/zy/Documents/Codex/ad-app/artifacts/ima-final-request-audit-20261010/verified-summary.json)
- [原厂 TCL ID 生成代码](/Users/zy/Documents/Codex/ad-app/artifacts/tcl-side-effects-audit-20261009/com.tcl.ff.component.overseabase.base.device.DeviceInfoGeneratorImpl.txt:14)
- [原厂 TCL 取参代码](/Users/zy/Documents/Codex/ad-app/artifacts/tcl-original-engine-audit-20261009/com.tcl.ff.component.overseabasebusiness.requestparams.VastAdRequestParams.txt:131)
- [九猫交测包与真实链路记录](/Users/zy/Documents/Codex/ad-app/artifacts/google-tcl-channel-experiment-20261010/jiu-mao/report.md)
