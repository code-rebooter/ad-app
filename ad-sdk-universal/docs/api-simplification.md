# 当前接口流程

仅新 ad-sdk-universal；Google 取链接已并入授权，配置已独立缓存刷新。

我方接口均为 JSON POST，前缀 `/api/v2/ad/`，携带真实宿主 ad_version、ad_version_name 和 sdk_version（当前 1.0.0）。

| 接口 | 时机 |
| --- | --- |
| sdk/config | 初始化无有效缓存或到期刷新；间隔由后台控制，默认 24 小时。 |
| sdk/flow-control | 每轮 Google、TCL 各一次；控制 enabled、skip_cmp、popup_log_enabled。 |
| consent-popup | skip_cmp=false 时按各路条件请求。 |
| consent-report | 实际 CMP 结果及失败补报，保留原渠道。 |
| sdk/authorize | 门禁/CMP 放行后各一次；Google 通过 data.gam_config 返回广告链接和加载超时。 |
| report | 每路完整成功四次状态上报；失败/取消按到达阶段上报。 |
| consent-log-report | 每路结束一次，popup_log_enabled=false 时关闭；当前缺省值仍为 true。 |

6 个传 mac 的业务接口均增加 androidid，两路都传真实 ANDROID_ID，读取失败为空；sdk/config 不传设备 ID。

初始化：缓存或请求配置 → 安装两路渠道及 TCL 登记 → 成功回调，不自动播放。

Google：门禁 → 条件 CMP 决策/处理/回报 → 授权返回链接 → TCL 引擎解析播放。

TCL：原厂 CMP 初始化 → 门禁 → 条件 CMP 决策/处理/回报 → 授权 → 原厂初始化/取链接/解析播放。

一次 play 依次执行两路，结束回调一次。两路各自上报；取消阻止下一路。

两路完整成功、skip_cmp=true、无重试/补报：2 门禁 + 2 授权 + 8 状态 = 12 次；日志开启再加 2 次，为 14 次。配置和第三方请求另计。门禁均关闭且日志开启时仍为 6 次/轮。

第三方协议保持：TCL 配置、取链、CMP、原厂 BI、UniPlayer Tracker；两路 adurl、Wrapper、素材和 XML 跟踪。Google 阶段继续原有隔离策略，TCL 阶段保留原厂路径。

日志默认关闭和 report 详细诊断受日志开关控制尚未修正。详见[实现状态](api-final-plan.md)。
