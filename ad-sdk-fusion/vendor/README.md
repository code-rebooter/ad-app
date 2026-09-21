# TCL 二进制依赖

原厂版本：2.8.02。来自项目已有 TCL demo 的 app/libs。目录下的 `sdk.aar` 是保留的原始输入文件。

这三份 AAR 仅作为构建输入，代码、资源和 consumer rules 均合入 `ad-sdk-fusion`，不再独立发布 Maven 模块。原有单家 SDK 保持独立。

| 目录 | 原始文件 | SHA-256 |
| --- | --- | --- |
| base | adsdk_overseas_base_business-2.8.02.aar | `1e9660a0e335e8e87e98057f5a0df520791a9754a0eb47dff789f00f7ecb56de` |
| media | adsdk_overseas_media_ad-2.8.02.aar | `22426d990e7e27e1fea05b2c014ea4960d3fa5da5efe2b92f945704ab3abc9ca` |
| player | adsdk_overseas_player-2.8.02.aar | `74d2c79e0af1a62697e36bc84929d28504ca264d69856505fdd3606e9a743907` |

## 融合版身份配置补丁

融合模块的 `patchTclFusionAar` 生成 `build/tcl/base-patched.aar`，再由 `prepareEmbeddedTcl` 与原始 media/player 一起打包。最终只发布融合主 AAR。

补丁源码：`buildSrc/src/main/groovy/com/smart/lsap/TclFusionAarPatchTask.groovy`。身份配置：融合模块 `TclIdentityBridge.java`。补丁不内嵌第二份配置，所有入口共用桥接类的登记信息。

修改入口：

- `BasicParameters`：包名、签名 MD5、App Key、partner name、project ID，以及原先从宿主读取的应用名称、版本名称、版本号。
- `Md5Utils.getSignatureMd5`：TCL 授权使用的 32 位大写 MD5。
- `GetBaseDataInfo.getAPPSecretString`：TCL BI 使用的同一 MD5，保留原厂小写格式。
- `BaseDataInfo.init`：宿主信息与自定义配置读取后、初始化日志打印前，统一 TCL BI 的应用包名、项目号、名称和版本；对应 getter 同样返回桥接配置。
- `NetworkDataInfo.getFormatMessage`：直接读取字段生成网络消息的入口，同步使用上述五项桥接配置。
- `HttpRequester`：HTTP 请求头 `XTCL-App` 的包名，与请求参数共用同一登记身份。

`BasicParameters` 内部查询宿主安装信息和资源的代码仍使用真实宿主包名。补丁不修改宿主 APK 的包名或签名，不改变 Google 和自有后台的设备信息采集。

补丁 AAR 内的 `META-INF/fusion-tcl-identity.properties` 记录原始 AAR / 补丁 classes.jar 的 SHA-256、补丁版本和修改类数。原始二进制不匹配时构建会停止，避免把 2.8.02 的变换应用到其他版本。

只生成补丁 AAR：

```sh
./gradlew :ad-sdk-fusion:patchTclFusionAar --offline --console=plain --no-daemon
```

这份中间补丁 AAR 不对外发布。最终主 AAR 内含 `libs/tcl-base.jar`、`libs/tcl-media.jar`、`libs/tcl-player.jar`，以及合并的资源、consumer rules 和身份桥接类。

当前补丁版本为 `fusion-tcl-identity-3`，共修改 6 个类。只替换 TCL 网络交互中动态读取的宿主应用身份；原厂 `MovieArk`、`com.tcl.movieark`、默认商店链接及 SDK 自身版本保持原样，`VastAdRequestParams` 类没有改写。覆盖路径和保留真实宿主信息的位置见 [身份配置覆盖核对](../TCL_IDENTITY_COVERAGE.md)。

## 单个 AAR 的打包方式

`TclFusionBundleTask` 提取三份输入的资源和混淆规则，并把三份 classes.jar 嵌入主 AAR。TCL 原资源命名空间的字段引用改指融合模块的 R 类，使 Android 宿主能为单个 AAR 生成正确资源 ID；不修改资源名、广告参数和固定字符串。原始 AAR 文件保持不变。原 media/player Manifest 的 `allowBackup=false` 由融合 Manifest 保留，网络权限原已声明。

`settings.gradle` 不再包含三个 TCL 子项目；JitPack 仅执行融合主模块的发布任务。生成的 POM 和 Gradle module metadata 不包含 `ad-sdk-fusion-tcl-*` 依赖。

已发布的 `v1.0.18` 使用原来的分模块方式；这里描述的是之后的单包构建方式，尚未发布新版本。

本地验证：单包 AAR 构建及 Maven 发布通过；仅使用该依赖的宿主 Release APK（R8 混淆开启）构建通过。主 POM/module metadata 无 TCL 子模块依赖，2,405 个 TCL 类完整内嵌，20 个资源字段存在；只重定向 29 处资源字段引用，其他类内容保持原样，84 项身份断言通过。未进行设备实播。
