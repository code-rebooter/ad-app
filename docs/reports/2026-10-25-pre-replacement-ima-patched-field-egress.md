# 改造前 IMA取链与最新自拼方案对照

> 归档说明：左侧风险描述针对未接入本地 `ms`、本地标识和 WebView Client Hints 修正前的原始 IMA 链路，不代表提交 `253e80c` 中已经本地化的 IMA 参考实现。2026-10-26 起正式 Google flavor 仅编译 independent，自此文档只用于说明最初的替换背景。

范围：从改造前原始 IMA 开始生成广告 URL，到应用取得最终 URL为止。不包含后台 patch、TCL请求和播放。

| 位置 | 补丁相关字段 | 不一致原因 | 结果 |
| --- | --- | --- | --- |
| IMA资源请求 | UA中的型号、Android版本、Build ID | WebView UA使用修正值；浏览器 Client Hints由WebView独立生成 | loader、Core JS、OM SDK JS请求在取链完成前已把 UA、包名和 Client Hints发给 Google |
| `ms` | `MODEL`、`BRAND`、`MANUFACTURER`、`DEVICE`、`PRODUCT`、`Build.ID`、Android release | 动态 VM/JNI在 `ms`生成阶段直接或反射读取系统原始字段，没有进入宿主固件修正入口 | 原始字段参与 `ms`生成；最终 URL明文补丁不会修改已经生成的 `ms` |
| `uach` | `model`、`platformVersion` | 远程 Core通过 `navigator.userAgentData.getHighEntropyValues()`读取，旧 IMA没有接入型号和系统版本修正入口 | 读取结果编码进最终 URL的 `uach` |
| `atv_bfp`和其他指纹输入 | `FINGERPRINT`内的品牌、产品、设备代号、Android版本、Build ID | 本地 `atv_bfp`使用修正指纹；GMS/GASS仍读取系统原始 `FINGERPRINT`及Build字段 | 最终 URL的 `atv_bfp`与GASS、动态信号使用的设备身份不是同一读取入口 |
| GASS远程材料接口 | `MODEL`、`BRAND`、`FINGERPRINT`、Android版本 | GASS运行在Google Play services进程，不受应用内IMA补丁控制 | 刷新条件满足时，`create_lite` 请求向Google发送这些原始字段；现有成功样本未触发该POST |

## 当前最新自拼方案

当前使用 `independent-google-tv-url-local-identity-20261025.aar`，SHA-256：`6035713c0ae1263a6cac26ac37b34807b64ca385b8087ab417ad5c5d562ac060`。

| 旧 IMA问题 | 最新自拼方案处理 |
| --- | --- |
| 取链前下载 IMA loader、远程 Core和OM SDK脚本 | 库内不包含 IMA SDK，不下载远程 Core或OM脚本；本地 WebView只执行随库逻辑 |
| `ms`生成时动态 VM/JNI读取原始Build字段 | `MODEL/BRAND/MANUFACTURER/DEVICE/PRODUCT/Build.ID/Android release`在直接读取、普通反射和嵌套反射阶段统一进入宿主修正入口，再生成 `ms` |
| `uach.model/platformVersion`绕过固件修正 | 在 `uach`编码前统一型号和非空Android系统版本 |
| `FINGERPRINT`、`atv_bfp`和动态读取来源不一致 | TV参数、直接/反射读取及7个指纹属性入口共用修正指纹；残缺指纹按实际构建字段重建，无法重建时在 `ms`初始化前停止生成 |
| GASS读取原始Build字段并保留远程材料接口 | 不调用GASS；原GASS摘要输入改为本地读取实际APK并计算SHA-256；不创建GKS客户端 |

`ms/nonce`的计算源码和原生库没有修改，变化的是输入来源和执行边界。

## 替换原因

1. 补丁在 `ms`、`uach`、`atv_bfp`生成前生效，不再依赖最终 URL明文补丁补救。
2. 同一字段的直接读取、反射读取、UA、UACH、指纹和TV参数使用同一修正结果。
3. 取链阶段不再下载和执行远程 IMA Core、OM脚本，也不再调用GASS/GKS。
4. 生成规则固定在本地AAR，可按文件版本和SHA复现，不受远程脚本版本变化影响。

## 结论

改造前原始 IMA取链只统一了已定位的原生 getter、WebView UA和最终 URL明文字段，没有统一 `ms`、`uach`及GMS/GASS内部读取。

取链阶段已经实际发送给 Google的是IMA资源请求中的UA、包名和Client Hints。`ms`、`uach`、`atv_bfp`在取链阶段写入最终URL，其中 `ms` 和 `uach`生成后无法通过明文URL补丁修正。GASS的原始字段POST在刷新条件满足时执行，现有样本未触发。
