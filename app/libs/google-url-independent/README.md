# 独立 Google TV URL 生成器

当前明确选用 20261025 本地身份版。构建源码、反编译材料和验证输出保存在仓库外研究归档中，不进入版本控制。

- 文件：`independent-google-tv-url-local-identity-20261025.aar`
- SHA-256：`6035713c0ae1263a6cac26ac37b34807b64ca385b8087ab417ad5c5d562ac060`
- 入口：`io.codex.imarepro.facade.AndroidIndependentUrlFacade`
- 不再依赖 GMS Advertising ID、AppSet、Base 或 Tasks 客户端；UMP 单独保留其运行所需的 Basement。
- 新增指纹入口：`Hq008XhsxAarRuntimeBridge.getAndroidBuildFingerprint()`；TV 参数、普通/嵌套反射及 7 个指纹属性入口共用对应字段修正结果；缺失/残缺时从实际构建字段重建，无法重建则在 ms 模块初始化前终止生成。
- 宿主 API：`Hq008XhsxAarRuntimeBridge.correctAndroidClientHints(Map)`；消费者规则保留该静态入口。
- UACH 型号和非空 Android 系统版本在编码前使用宿主有效值，直接/嵌套反射也复用宿主固件规则。
- AAID/LAT 与 AppSet 使用 independent 私有存储，按已固定的 GMS 23.48.16 与 AppSet 16.0.2 本地规则生成；普通 URL 和 V2 信号共用同一份 AAID/LAT。有效 `rdid` 存在时不输出 AppSet；全零或空 ID 不可作为有效设备 ID 时补 `pvid/pvid_s`。原版 Core 在全零 ID 情况下仍保留零值 `rdid`，因此该回落分支允许零 `rdid` 与 `pvid/pvid_s` 同时存在。
- 本地 WebView 只执行随库本地逻辑。ms/nonce 计算源码与原生库未改。
- 库内没有 IMA SDK，不加载远程 Core。UMP/CMP 属于宿主保留流程。

仓库只保留当前构建明确引用的 AAR。历史 AAR、构建记录、Manifest/DEX 扫描和设备 URL 验证材料均存放在仓库外研究归档中。
