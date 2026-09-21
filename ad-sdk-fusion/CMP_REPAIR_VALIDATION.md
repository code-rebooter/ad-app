# TCL CMP 修复记录

`v1.0.19` 只嵌入 TCL base/media/player，TCL ConsentResolver 直接放行，遗漏主应用 `app/src/hq008` 的完整 CMP。这次修复仅修改融合模块及其打包任务，单家 `ad-sdk-modern` 与宿主公开 API 保持原样。

## 修复范围

- 原厂 CMP 2.8.02 AAR、资源、混淆规则内嵌主 AAR，不增加 Maven 子模块。
- 从主应用 `Hq008CmpManager` 迁移启动恢复、原生状态/活动读取、TC 恢复、有效期判断、后台决策执行、原生 user/action 同步、待补状态持久化及重试、consent-report。
- 后台决策支持 ACCEPT_ALL、REJECT、SAVE_SETTINGS（七组 ID）、MAYBE_LATER、SKIP_ALREADY_DECIDED；请求失败沿用主应用 MAYBE_LATER 回退。
- TCL 广告仅附带 TCL CMP 的 TC，来源为 CMP_TCL。IAB 镜像使用独立偏好文件，避免覆盖 Google UMP。
- 决策及上报使用 `_TCL` 渠道，与 Google 共用 API 域名；宿主取消后不再由旧 CMP 回调推进广告授权。
- CMP 本地日志接入 `persist.sys.ad.log`；本轮 CMP 事件及详细请求响应进入有长度限制的流程日志，上传仍遵循后台 `popup_log_enabled`。
- CMP 动态网络身份经原有 BasicParameters/HttpRequester 补丁读取登记信息，原厂固定名称和链接保持原样。

## 本地验证

2026-09-21，33 项定向 JVM 检查通过：后端协议及原始错误、CMP 等待与取消、两渠道串行与结果聚合、CMP 请求响应进入最终流程上报。融合 Release AAR 和本地 Maven 发布成功。

最终 AAR 内四份 TCL JAR 共 2,572 个不重复类，其中 CMP 167 个。全部 126 个供应商资源字段可解析；POM/module metadata 无 TCL 子模块依赖。公开 API 的 12 份 Java 文件与单家 SDK 相同。

验证产物、测试日志和设备记录保存在工作区 `output/ad-sdk-fusion/cmp-fix/`。

## 设备事实与验证边界

仅操作 `192.168.0.135:5555`（Dreamlink mstar，SDK 29）；未操作 X88。使用仅依赖本地 Maven 融合 AAR 的 Release Demo，开启 R8、硬件加速，未覆盖真实后台配置。

设备启动出现 CMP_INIT_START、CMP_SNAPSHOT_LOAD_START、CMP_SNAPSHOT_LOAD_RESULT 和 CMP_SNAPSHOT_UPDATED：原生 CMP 初始化及本地状态读取完成，当前 needShowPop=true、consentLength=0。

实际广告轮次中，Google/TCL 各自请求共享域名的 flow-control 和 authorize。两家后台均返回 skip_cmp=true、popup_log_enabled=false，按服务端配置跳过本轮 CMP 决策与流程日志上传。Google 返回原始 IMA 303 后进入 TCL，TCL 返回原始 -1000；宿主 onFinished 仅一次，status=ERROR，message 保留原始 IMA 文案。未发生应用崩溃。

这次真机结果证明初始化、两家串行和错误回传路径，**不代表广告实播成功，也未验证远端 CMP 决策至 user/action/consent-report 的完整成功链路**。该链路真机验证需要后台允许本轮进入 CMP（skip_cmp=false）。
