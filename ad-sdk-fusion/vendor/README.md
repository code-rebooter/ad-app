# TCL 二进制依赖

原厂版本：2.8.02。来自项目已有 TCL demo 的 app/libs。目录下的 `sdk.aar` 是保留的原始输入文件。

这三个子项目分别发布 AAR，供 ad-sdk-fusion 的 Maven POM 传递引用；不改变原有单家 SDK 的依赖。

| 目录 | 原始文件 | SHA-256 |
| --- | --- | --- |
| base | adsdk_overseas_base_business-2.8.02.aar | `1e9660a0e335e8e87e98057f5a0df520791a9754a0eb47dff789f00f7ecb56de` |
| media | adsdk_overseas_media_ad-2.8.02.aar | `22426d990e7e27e1fea05b2c014ea4960d3fa5da5efe2b92f945704ab3abc9ca` |
| player | adsdk_overseas_player-2.8.02.aar | `74d2c79e0af1a62697e36bc84929d28504ca264d69856505fdd3606e9a743907` |

## 融合版身份配置补丁

`base` 子项目的 `patchTclFusionAar` 在构建时生成 `base/build/patched/sdk.aar`；本地项目依赖和 Maven 发布均指向该生成文件。`media`、`player` 使用原始 AAR。

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
./gradlew :ad-sdk-fusion-tcl-base:patchTclFusionAar --offline --console=plain --no-daemon
```

这份补丁 AAR 需要与包含 `TclIdentityBridge` 的新版融合主模块配套使用，不能搭配之前生成的融合主 AAR。

当前补丁版本为 `fusion-tcl-identity-3`，共修改 6 个类。只替换 TCL 网络交互中动态读取的宿主应用身份；原厂 `MovieArk`、`com.tcl.movieark`、默认商店链接及 SDK 自身版本保持原样，`VastAdRequestParams` 类没有改写。覆盖路径和保留真实宿主信息的位置见 [身份配置覆盖核对](../TCL_IDENTITY_COVERAGE.md)。
