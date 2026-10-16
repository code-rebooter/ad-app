# 接口简化实现状态

仅修改新 ad-sdk-universal。SDK 版本 1.0.0；所有我方接口携带真实宿主 ad_version、ad_version_name 和 sdk_version。

已实现：

- Google 取链接并入 sdk/authorize；新 SDK 不再请求 google-gam/resolve。授权成功响应通过 data.gam_config 返回广告链接及加载超时；rdid 随 Google 授权上传。6 个传 mac 的接口均增加 androidid，Google、TCL 两路都传。
- sdk/config 按 key 持久缓存，使用成功获取时间和 refresh_interval_seconds 独立刷新，默认 24 小时；失败保留缓存并退避。
- 当前轮使用固定配置；下一轮前更新两路渠道及 TCL 登记。等待此前原厂初始化/CMP 工作完成后切换，并隔离不同登记的原厂广告配置及同意缓存。未完成 CMP 补报保留原渠道。

保留 7 个接口：sdk/config、sdk/flow-control、consent-popup、consent-report、sdk/authorize、report、consent-log-report。

Google：门禁 → 条件 CMP 决策、处理和回报 → 授权返回链接 → 原厂解析播放。

TCL：原厂 CMP 初始化 → 门禁 → 条件 CMP 决策、处理和回报 → 授权 → 原厂取链接、解析播放。

状态、CMP 结果、调试日志保持独立。两路完整成功、skip_cmp=true、日志关闭、无补报/重试时为 12 次我方请求/轮；日志开启为 14 次。配置和第三方请求另计。

本次未改的日志问题：popup_log_enabled 的缺省值及 FlowTrace 初始值仍为 true；report 仍可能携带详细 diagnostic_info。默认关闭及详细诊断随日志开关控制的修正尚待单独处理。

后台变更见[接口字段](backend-api-changes.md)、[配置接口](sdk-config-api.md)。本地构建和模拟请求核验不代表后台部署或新 SDK 真机联调已完成。
