# IMA / 参考方案 实际参数对照与待补标记

2026-10-16 整理，依据历史抓包：IMA 为 2026-10-10 的项目补丁版 3.35.1 + Media3 1.8.0；参考方案 为 2026-10-08 的 2.8.02。IMA 的 iu 使用 Google 官方公开测试位，其他输入沿用原 15 项参数结构。

**客户身份与样本值已脱敏**：参考方案为匿名名称；应用 A/B、包名、站点、广告位、设备及会话标识等均为替代值；保留原字段、增删改类型、空值状态和统计。示例值不能用于真实投放。

| 实际链路 | 抓包结果 |
| --- | --- |
| IMA → Google | 输入 15 项 → 最终 48 项：新增 34、替换 2、移除 1、保留 12。 |
| 参考方案 后台 → 客户端 → 参考服务广告地址 | 后台返回 33 项；客户端保留全部值，只追加 requestId，最终 34 项。返回字段中：17 项与 POST 同值、9 项值不同、7 项为返回新增字段。 |

**没有抓到“参考服务服务器 → Google”的内部请求。下表的同类字段是对照/候选关系，不是已证实的后台转发规则。**

## 之前补参文档的标记

**✅ 补**＝此前方案中准备补齐的字段，仍未实施，不表示 Google 所有请求一律必传。**△ 条件**＝按设备/广告位置决定。**? 待核**＝重要候选，保留审查，当前不能确定取值。**○ 可选**＝业务另行决定。

| Google 字段 | IMA 实发/动作 | 参考方案 样本同类信息 | 我方拟补值或判断 | 标记 |
| --- | --- | --- | --- | --- |
| `msid` | 新增 `com.example.app.a` | app_bundle=com.example.app.b | 实际投放应用的包名/应用标识；示例值不可直接用于上线 | **✅ 补** |
| `an` | 新增 `com.example.app.a` | app_name=应用 B | 实际投放应用的名称 | **✅ 补** |
| `url` | 新增 `https://example.com` | url=com.example.resource | 真实资源 URL；若代表同一资源，可复用原 description_url 的值 | **✅ 补** |
| `hl` | 新增 `en` | 最终 URL 无；取链 POST contentLanguage=en、deviceLanguage=en-US | 真实广告语言；英文为 `en` | **✅ 补** |
| `vpa` | 未发送 | 未发送 | 当前自动播放填 `auto`；点击触发填 `click`。这是场景方案项 [G1][G2] | **✅ 补** |
| `correlator` | 空值 → `100000000000003`（替换） | cb=1000000000001，为时间戳；两者不是同一字段 | 补随机正整数；按观看上下文更新，不照抄样本数字或 参考方案 时间戳 | **✅ 补** |
| `devt` | 未发送 | 未发送 | 实际为机顶盒时 `stb`；不统一套到电视/投影仪 [G1][G2] | **△ 条件** |
| `dth` | 未发送 | 未发送 | 机顶盒 `7`；其他设备按真实类别 [G1][G2] | **△ 条件** |
| `ott_placement` | 未发送 | 无同名字段；placement=1 不能直接换成它 | 真实 CTV/OTT 主页为 `2`；其他位置按其真实格式，不能仅凭包名决定 [G2] | **△ 条件** |
| `rdid` | 新增 `<广告标识 A1>` | did=<设备标识 B> | 我方当前上传本地持久 UUID；参考方案→Google 的实际转发/适用 ID 类型未证实 | **? 待核** |
| `idtype` | 新增 `adid` | ifa_type=aaid；POST IFAType=aaid | IMA 实测 `adid`，参考方案 实测 `aaid`；我方该用什么须与 rdid 的真实来源匹配 | **? 待核** |
| `is_lat` | 新增 `0` | POST dnt=0，返回及最终 URL dnt 为空 | 需真实平台限制跟踪状态；不能用空 dnt 推出固定 `0` | **? 待核** |
| `min_ad_duration` | 未发送 | min_dur=5（秒） | 只有决定限制单条广告至少 5 秒时填 `5000`（毫秒） [G2] | **○ 可选** |
| `max_ad_duration` | 未发送 | max_dur=60（秒） | 只有决定限制单条广告最多 60 秒时填 `60000`（毫秒） [G2] | **○ 可选** |

**这里的 ✅ 是 5 个新增字段 msid/an/url/hl/vpa，加上已有 correlator 补值。devt/dth/ott_placement 是 3 个条件项。rdid/idtype/is_lat 整组保留待核，未从清单删掉。**

`vpa/devt/dth/ott_placement` 在两份最终 URL 中均未出现；它们来自此前方案及官方场景说明，不能写成 IMA/参考方案 本次实际追加。`hl` 仅在 IMA 最终 URL 出现，参考方案 的语言值出现在取链 POST。

## 全部 URL 字段逐项对照

按原始字段名对照；例如 IMA 的 rdid 与 参考方案 的 did 分别列出。同名也不保证语义和使用规则相同。空值、未发送、移除分别标记；83 个字段完整保留，ms 等动态信号仅保留长度说明。

| URL 字段 | Google 输入 → IMA 最终（动作） | 参考方案 最终 URL 值 | 参考方案 字段来源 | 我方处理 |
| --- | --- | --- | --- | --- |
| `sdkv` | `h.3.535.0/n.android.3.35.1/com.example.app.a`（新增） | 未发送 | — | 不抄 IMA 的 SDK 身份 |
| `osd` | `2`（新增） | 未发送 | — | IMA 运行时字段；先不手拼 |
| `frm` | `0`（新增） | 未发送 | — | IMA 运行时字段；先不手拼 |
| `sdr` | `1`（新增） | 未发送 | — | IMA 运行时字段；先不手拼 |
| `hl` | `en`（新增） | 未发送 | — | **✅ 补**；见上表 |
| `is_lat` | `0`（新增） | 未发送 | — | **? 待核**；见上表 |
| `idtype` | `adid`（新增） | 未发送 | — | **? 待核**；见上表 |
| `rdid` | `<广告标识 A1>`（新增） | 未发送 | — | **? 待核**；见上表 |
| `ms` | 动态长串（715 字符，信号值已脱敏）（新增） | 未发送 | — | IMA 动态信号；不复制样本串 |
| `is_amp` | `0`（新增） | 未发送 | — | 本次 IMA 字段；先不手拼 |
| `uach` | `WyJBbmRyb2lkIiwiIiwiIiwiIiwiIixudWxsLDAsbnVsbCwiIixudWxsLDBd`（新增） | 未发送 | — | IMA 浏览器提示编码；不复制样本串 |
| `js` | `ima-android.3.35.1`（新增） | 未发送 | — | 不抄 IMA 身份 |
| `an` | `com.example.app.a`（新增） | 未发送 | — | **✅ 补**；见上表 |
| `msid` | `com.example.app.a`（新增） | 未发送 | — | **✅ 补**；见上表 |
| `mv` | `85354030.com.android.vending`（新增） | 未发送 | — | IMA 本次运行环境字段；先不手拼 |
| `u_so` | `l`（新增） | 未发送 | — | IMA 本次运行环境字段；先不手拼 |
| `ctv` | `1`（新增） | 未发送 | — | 实测值为 1；先不据此认定为 native 必传 |
| `mpt` | `google/exo.ext.ima`（新增） | 未发送 | — | 不冒称 google/exo.ext.ima 播放器 |
| `mpv` | `1.8.0`（新增） | 未发送 | — | 不冒称 Media3 1.8.0 播放器版本 |
| `sdki` | `5`（新增） | 未发送 | — | IMA 运行时字段；先不手拼 |
| `ptt` | `20`（新增） | 未发送 | — | IMA 运行时字段；先不手拼 |
| `vo` | `0`（新增） | 未发送 | — | IMA 运行时字段；先不手拼 |
| `sdk_apis` | `7,8`（新增） | 未发送 | — | 有真实相应 API/测量能力才声明，不照抄 7,8 |
| `omid_p` | `Google1/android.3.35.1`（新增） | 未发送 | — | 实际完成 OMID 集成后填真实身份，不抄 Google1 |
| `sid` | `<会话标识 A1>`（新增） | 未发送 | — | 保留候选：按真实会话/库存处理；不是持久设备 ID |
| `eid` | `95322027,95331589,95332046`（新增） | 未发送 | — | IMA 实验信号；不复制样本 |
| `url` | `https://example.com`（新增） | `com.example.resource` | 后台返回：与 POST 同值 | **✅ 补**；见上表 |
| `dlt` | `1000000000008`（新增） | 未发送 | — | IMA 动态时序；不复制样本 |
| `idt` | `552`（新增） | 未发送 | — | IMA 动态时序；不复制样本 |
| `dt` | `1000000000009`（新增） | 未发送 | — | IMA 动态时序；不复制样本 |
| `eo_id` | `<动态标识 A1>`（新增） | 未发送 | — | IMA 动态标识；不复制样本 |
| `eo_id_ts` | `1000000001`（新增） | 未发送 | — | IMA 动态时序；不复制样本 |
| `pvsid` | `1000000000000006`（新增） | 未发送 | — | IMA 动态标识；不复制样本 |
| `scor` | `1000000000000001`（新增） | 未发送 | — | 保留候选：有对应视频流/排除功能时单独处理，不照抄样本 |
| `output` | `vast` → `xml_vast4`（替换） | 未发送 | — | 原有 vast；本次无须为模仿 IMA 强制改为 xml_vast4 |
| `correlator` | **空值** → `100000000000003`（替换） | 未发送 | — | **✅ 补**；见上表 |
| `impl` | `s` → 未发送（移除） | 未发送 | — | 原有 s；IMA 移除不等于自解析也必须移除 |
| `iu` | `/21775744923/external/single_preroll_skippable`（保留） | 未发送 | — | 沿用客户广告位；此处是测试位 |
| `description_url` | `https://example.com`（保留） | 未发送 | — | 原有；核对真实资源信息 |
| `tfcd` | `0`（保留） | 未发送 | — | 原有；按真实年龄处理配置，不抄样本常量 |
| `npa` | `0`（保留） | 未发送 | — | 原有；按真实隐私状态处理 |
| `ad_type` | `audio_video`（保留） | 未发送 | — | 原有；与实际可播放的广告类型一致 |
| `sz` | `1x1\|300x250\|320x480\|400x300\|640x360\|640x430\|640x480`（保留） | 未发送 | — | 原有；按真实广告槽/素材配置 |
| `gdfp_req` | `1`（保留） | 未发送 | — | 原有 |
| `unviewed_position_start` | `1`（保留） | 未发送 | — | 原有 |
| `env` | `vp`（保留） | 未发送 | — | 原有 |
| `plcmt` | `1`（保留） | 未发送 | — | 原有；按真实场景核对，不直接使用 参考方案 placement 的数字 |
| `vpmute` | `0`（保留） | 未发送 | — | 原有；核对真实初始声音状态，IMA 本次未纠正输入 |
| `app_package` | `com.example.app.a`（保留） | 未发送 | — | 原有；当前渠道换真实包名；与 msid 是不同键 |
| `ciu_szs` | `300x250,728x90`（另外 3 组输入保留；full_test 未发送） | 未发送 | — | 仅另外 3 组测试输入携带，IMA 原样保留；不是本次新增 |
| `device_model` | 未发送 | `SampleTV` | 后台返回：与 POST 同值 | 参考方案 设备上下文；暂不向 Google 新增同名 query |
| `rating` | 未发送 | `R` | 后台返回：与 POST 不同 | 有真实内容才按分级/内容配置处理 |
| `skip` | 未发送 | **空值** | 后台返回：与 POST 不同 | 参考方案 本次空值；不据此新增 Google skip |
| `gdpr_consent` | 未发送 | **空值** | 后台返回：与 POST 同值 | 按真实同意结果处理，不抄空值 |
| `ua` | 未发送 | `Dalvik/2.1.0 (Linux; U; Android 13; SampleTV Build/EXAMPLE_BUILD)` | 后台返回：与 POST 同值 | 当前 VAST 请求已有 User-Agent 头；不额外照抄同名 query |
| `content_title` | 未发送 | `示例影片` | 后台返回：与 POST 不同 | 有真实内容时考虑 ct_ti；不抄样本影片 |
| `coppa` | 未发送 | `0` | 后台返回：与 POST 不同 | 真实年龄处理；不直接映射为永久 tfcd=0 |
| `ic` | 未发送 | `IAB1` | 后台返回：新增字段 | 有真实内容分类才配置；不抄 IAB1 |
| `cb` | 未发送 | `1000000000001` | 后台返回：与 POST 同值 | 仅与 correlator 作用相近；不照抄时间戳 |
| `channel_name` | 未发送 | `示例影片` | 后台返回：与 POST 不同 | 有真实内容时考虑 ct_ch；不是我方统计 channel_id |
| `content_id` | 未发送 | `<内容标识 B>` | 后台返回：与 POST 不同 | 有内容源时考虑 cmsid/vid；不抄参考服务内容 ID |
| `ip` | 未发送 | `203.0.113.10` | 后台返回：新增字段 | 实际连接暴露出口 IP；不照抄此 query 或后台出口 |
| `h` | 未发送 | `0` | 后台返回：与 POST 同值 | 与 w 一起核对尺寸；不向 Google 追加 h=0 |
| `network_name` | 未发送 | `示例内容网络` | 后台返回：新增字段 | 有真实内容时考虑 ct_ne；不抄样本内容网络 |
| `pod_max_dur` | 未发送 | `120` | 后台返回：与 POST 同值 | 只有使用对应广告串功能才考虑 pmxd；120 秒不是设备 ID/会话值 |
| `dnt` | 未发送 | **空值** | 后台返回：与 POST 不同 | ? 仅 is_lat 语义候选；空值不转换成 0 |
| `TDID` | 未发送 | **空值** | 后台返回：新增字段 | 空值且身份来源未证实；先不传 |
| `gdpr` | 未发送 | `0` | 后台返回：与 POST 不同 | 按真实地区/隐私状态处理，不抄样本 0 |
| `app_name` | 未发送 | `应用 B` | 后台返回：与 POST 同值 | 不新增同名；应用名候选对应 an |
| `device_make` | 未发送 | `ExampleVendor` | 后台返回：与 POST 同值 | 参考方案 设备上下文；暂不向 Google 新增同名 query |
| `max_dur` | 未发送 | `60` | 后台返回：与 POST 同值 | ○ 可选 → max_ad_duration；60 秒需换为 60000 毫秒 |
| `content_genre` | 未发送 | `Drama,Thriller,Crime` | 后台返回：与 POST 不同 | 有真实内容才考虑 PPS/已配置映射；不抄题材 |
| `app_bundle` | 未发送 | `com.example.app.b` | 后台返回：与 POST 同值 | 不新增同名；应用标识候选对应 msid |
| `w` | 未发送 | `0` | 后台返回：与 POST 同值 | 与 h 一起核对尺寸；不向 Google 追加 w=0 |
| `app_store_url` | 未发送 | `https://play.google.com/store/apps/details?id=com.example.app.b` | 后台返回：与 POST 同值 | 应用资料核对；不据此新增 Google 同名字段 |
| `us_privacy` | 未发送 | `1---` | 后台返回：新增字段 | 按真实美国隐私配置处理，不抄 1--- |
| `min_dur` | 未发送 | `5` | 后台返回：与 POST 同值 | ○ 可选 → min_ad_duration；5 秒需换为 5000 毫秒 |
| `placement` | 未发送 | `1` | 后台返回：与 POST 同值 | 不能仅按数值映射为 plcmt/ott_placement |
| `ifa_type` | 未发送 | `aaid` | 后台返回：与 POST 同值 | ? idtype 语义候选；参考方案 此处字面值确定为 aaid |
| `did` | 未发送 | `<设备标识 B>` | 后台返回：与 POST 同值 | ? rdid 语义候选；Google 侧转发方式未证实 |
| `vt` | 未发送 | `示例影片` | 后台返回：新增字段 | 样本为内容标题别名；不额外新增 Google vt |
| `cid` | 未发送 | `<内容标识 B>` | 后台返回：新增字段 | 样本为内容 ID 别名；不额外新增 Google cid |
| `requestId` | 未发送 | `<请求标识 B>` | 客户端在返回 URL 后追加 | 原厂客户端已追加；后台无须重复拼；不是 correlator |

## 其他 3 组 IMA 实测差异

| 测试输入 | correlator 输入 → 实发 | 最终 URL 字段数 | url 实发 |
| --- | --- | --- | --- |
| `empty` | **空值** → `100000000000002` | 42 | `com.example.app.a.adsenseformobileapps.com/` |
| `fixed` | `123456789` → `1000000000000005` | 42 | `com.example.app.a.adsenseformobileapps.com/` |
| `missing` | 未发送 → `1000000000000003` | 42 | `com.example.app.a.adsenseformobileapps.com/` |
| `full_test` | **空值** → `100000000000003` | 48 | `https://example.com` |

四组请求的最终键集合除 full_test 的输入差异外，没有发现另一批隐藏新增键；其他 3 组多出的 ciu_szs 来自测试链接输入。sid、correlator、ms、eid、时间等值会随样本变化。本次仅覆盖这四组，不能据此声称覆盖 IMA 所有条件分支。

## 请求头与证据

| 信息 | 本次 IMA | 参考方案 / 当前自解析链路 | 处理 |
| --- | --- | --- | --- |
| User-Agent | 最终请求头为 Dalvik / Android 13 / SampleTV 字符串 | 参考方案 历史 URL 的 ua 是同一串；当前 VAST 客户端代码已设置 User-Agent 头 | 当前不新增 ua query |
| Sec-CH-UA、Referer | 有 WebView Client Hints 和 imasdk.googleapis.com Referer | 本次 参考方案 捕获文件没有请求头字段；不能从该文件认定其有/无这些头 | 不复制 IMA 的 WebView 身份头 |
| IP | 由实际网络连接体现 | 参考方案 历史 URL 还带 ip=203.0.113.10 | 不把此样本 IP 复制给 Google |

- [脱敏对照 JSON](../artifacts/parameter-comparison-share-20261016/comparison-redacted.json)：四组 IMA 与 参考方案 的字段和值类型对照；身份和动态信号均为替代值。
- [后台补参表（脱敏）](../artifacts/parameter-comparison-share-20261016/parameter-enrichment.md)。原始抓包仅用于内部核验，不包含在分享资料中。

官方场景/字段定义于 2026-10-16 复核；这部分用于解释方案，不代替抓包证据：

- [G1] CTV 参数：`https://support.google.com/admanager/answer/15788066?hl=en`
- [G2] VAST 参数总表：`https://support.google.com/admanager/answer/10678356?hl=en`
- [G3] 设备标识与会话：`https://support.google.com/admanager/answer/6238701?hl=en`

本次只更新对照文档；未改 SDK、后台、广告 URL 或已交付 APK。
