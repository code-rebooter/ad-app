# 独立 Google URL：生成前的出货固件信息纠错

以下按时间保留变更历史，当前选用产物与行为见末尾最新记录。

2026-10-23，完整独立候选新增固件输入纠错入口。它复用宿主已有HaierBuildIdentityNormalizer、HaierUserAgentNormalizer和normalizeAarUrlUa；有效原值的保留与异常值的修复沿用现有规则。

该阶段候选、源码和验证记录已迁移至仓库外研究归档。这里仅保留正式行为说明。

顺序：原标签先纠错并移除停用的AppSet字段；普通/native/反射读取和浏览器读取使用同一固件入口；随后执行原独立参数/ms/nonce计算；返回URL再交现有TCL开关与共同补丁。

覆盖字段为型号、品牌、制造商、设备代号、产品名、Android release、Build ID、原生/浏览器UA和UACH model。SDK仍取实际API级别。本段为首版范围；后续 UACH 版本及完整指纹补齐见下方记录。硬件、签名、权限、同意及播放/曝光状态没有随此变更改写。

`rdid/idtype/is_lat`的GMS读取方法保留。Facade关闭AppSet采集，输入标签的`pvid/pvid_s`也移除。该修改没有删除整个GMS依赖集。

宿主新增`Hq008XhsxAarRuntimeBridge.getAndroidSystemProperty(String,String)`供反射读取复用现有Android属性纠错函数。独立候选通过反射调用宿主固定API，缺失时报错；consumer-rules保留这些名称。

本轮通过完整Java/D8、宿主Kotlin编译和45项使用实际Kotlin纠错函数的隔离JVM检查。未新跑设备、IMA/Core或广告请求，不能把旧版样本一致性记录说成新版已通过实机对照。

同日补充：修复基础 URL 已有 `ms` 时被拒绝的问题，改为由本次非空生成值覆盖同名旧值。交付 AAR 已重建；14组保存输入的原 Core 函数片段/完整URL对照通过。此项仅验证合并，未新生成ms或运行设备。旧产物保留在候选目录的`revisions/before-base-ms-fix`，本项证据为`base-ms-check/result.json`。

同日宿主流程后续更新：完整 URL 经公共修正后，须通过 google-gam/patch 获取后台结果再交 TCL；旧 ima_tcl_url_patch 已移除。此宿主变更未激活独立生成 AAR，也未修改其信号计算。见 [后台 patch 协议](google_gam_patch_api.md)。

2026-10-24 补充：宿主 UA 改为与单独 release/model/Build.ID 共用逐项纠错结果，修正版本或型号时不再把有效 Build ID 换成模板值。运行时重复检查也会把旧模板 UA 对齐到这份原生纠错结果。116 真机原生字段与 WebView UA 的 Build ID 均为 `QP1A.191105.004`。本项未重建独立 AAR、未改完整指纹或 Build.DISPLAY；定向修复原始记录保存在仓库外研究归档中。

2026-10-24 一致性复核：app 改为明确选择 20261024 独立 AAR。系统属性别名与原生有效值统一；自拼 UACH 在编码前统一型号和非空 Android platformVersion；Field.get/嵌套 Method.invoke 复用字段入口；Google UMP 的第二条请求组装路径补入型号修正。此前“其他 UACH 字段不改”描述由本项更新：浏览器版本和架构等仍保留，系统版本现已同步处理。完整核对材料已迁移至仓库外研究归档。

2026-10-24 完整指纹补齐：当前选用 `independent-google-tv-url-20261024-fingerprint.aar`。完整指纹内品牌/产品/设备代号/系统版本/Build ID 在生成参数前与宿主有效值统一；增量版本、构建类型和标签保留原值。7 个指纹系统属性及普通/嵌套反射同步处理，TV `atv_bfp` 再从修正后的指纹截取。保留 IMA 的本地 TV 收集器同步接入。116/128 的定向本地结果及边界保存在仓库外研究归档中。前面未改指纹的描述为历史变更范围，不代表当前产物。

2026-10-24 异常指纹闭环：当前选择 `independent-google-tv-url-20261024-fingerprint-recovery.aar`。缺失/残缺指纹用修正后的基础组件加实际 INCREMENTAL/TYPE/TAGS 重建；必要原始字段不可用时，明确报错并在 ms 进程模块/缓存建立前终止自拼。指纹属性缺失也同样处理，不使用调用方 default 作为固件来源。算法源码和原生库保持原样；当前数据流为修正输入→建立/读取计算状态→生成 ms/nonce→客户端公共 URL 修正→后台 patch→TCL。闭环修复原始记录保存在仓库外研究归档中。

2026-10-25 当前正式产物升级为 `independent-google-tv-url-local-identity-20261025.aar`，广告 ID/LAT 和 AppSet 改用独立本地存储与固定生成规则，不再调用 GMS Advertising ID 或 AppSet 客户端。仓库只保留该正式 AAR，历史产物和实现对照材料均存放在仓库外研究归档中。
