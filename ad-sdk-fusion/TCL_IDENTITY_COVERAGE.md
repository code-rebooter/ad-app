# TCL 2.8.02 身份配置覆盖说明

本补丁针对 TCL 网络请求及上报中**动态读取宿主应用身份**的入口。原厂固定值、真实设备信息、Android 系统资源和进程判断分别保留原有含义。当前补丁为 `fusion-tcl-identity-3`，身份补丁只改 base AAR 的 6 个类；原始四份 AAR 保持原文件。单包打包另行重定向 TCL 的资源字段引用，详见 `vendor/README.md`，不改变应用身份或原厂固定广告字段。

## 应用身份来源

包名 `com.google.android.adhq1001`、MD5 `D2A9B2A8A9E0AF740267C0BC356DC1A4`、App Key、项目号 `213`、partner `chhkj_1` 使用项目方提供的登记配置，集中在 `TclIdentityBridge`。

应用名称和版本使用项目方明确提供的值，不从历史 APK 推断：

- 应用名称 `Adhq1001HISIA9`。
- `versionName=2.0.10`，`versionCode=10`。

## 已替换的动态身份入口

| 路径 | 参数来源及结果 |
| --- | --- |
| TCL 初始化与配置请求 | `BasicParameters` 的 Key、partner、project getter 转到桥接类；配置接口 `adProjectId=213` |
| 广告请求中的动态身份 | `VastAdRequestParams` 从 `BasicParameters` 取得 `appPackage`、`asfm`、`appKey`、`DevicePartner`、`application`，其动态版本来源也改为桥接值 |
| 广告请求的 `appDomain` | 融合适配器传入桥接包名 |
| TCL CMP 请求 | `appbundle/appMd5/appVersion` 经共享 `BasicParameters` 返回登记包名、MD5、版本；HTTP 头共用已补丁的 `HttpRequester`，不读取 Google UMP token |
| HTTP 请求头 | `HttpRequester` 从桥接类设置包名，`SignInterceptor` 输出 `XTCL-App=com.google.android.adhq1001` |
| TCL 广告 BI | `BiInitialization` / `BiReportUtil` 所用包名、动态应用名称和版本、partner、project 来自桥接后的 `BasicParameters` |
| TCL 基础 BI 初始化 | `BaseDataInfo.init` 在宿主 Manifest 和自定义值读取完成后、打印日志前，写入登记包名、项目号、名称和版本 |
| TCL 基础 BI 网络输出 | `BaseDataInfo` 对应 getter 返回桥接值；`NetworkDataInfo.getFormatMessage` 的五项直接字段读取也转到桥接类，避免绕过 getter |
| 签名读取 | `Md5Utils.getSignatureMd5` 返回登记 MD5 大写；`GetBaseDataInfo.getAPPSecretString` 返回同一 MD5 的小写形式，保持各自原厂格式 |

## 保留的原厂固定值

`VastAdRequestParams` 类字节码保持原样：其 `appName=MovieArk`、外部接入的 `appBundle=com.tcl.movieark`、默认商店链接 `https://play.google.com/store/apps/details?id=com.tcl.movieark` 都是 TCL 写死的值，不是从宿主读取，因此不替换。

TCL SDK 版本 `2.8.02`、播放器版本、网络签名算法及其服务级配置也保留原值。广告请求固定的 `appName` 与 BI 动态应用名称是不同路径，不为了让两者一致而改变原厂固定行为。

## 保留的本地或其他用途

- `BasicParameters.a/b`、`BaseDataInfo.getAppBaseInfo/init` 查询真实安装包和 Manifest；这些宿主身份值不会作为上述网络身份字段的最终值。
- `DataReport$b.run` 比较当前 PID 的进程名和真实宿主包名；资源 URI、`Resources.getIdentifier`、系统 MediaSession、调度和编解码兼容判断继续使用真实宿主。
- 播放器的诊断信息、`com.tcl.cyberui` 的版本、`com.tcl.usercenter` 的安装判断、待安装广告 APK 的包名保持各自来源。播放器可反射加载 `UniTrackerImpl` 接收诊断信息，但本模块配套的四个 TCL AAR 不包含该可选组件，也没有声明此依赖。
- 点击广告的目标包名及交给 Google Play 的来源参数属于跳转行为，不改成 TCL 登记配置。
- Google UMP 使用宿主默认 IAB SharedPreferences；TCL 使用原厂 CMP 的授权状态，并把 IAB 镜像保存在独立文件 `smart_adsdk_tcl_cmp_iab`，避免覆盖 Google 的授权。Google 和自有后台的宿主设备信息保持原有来源。

广告请求 UA 来自 `http.agent`。附带 ExoPlayer 的通用 `Util.getUserAgent(Context, String)` 可以读取宿主版本，但在这四个 TCL AAR 中未发现调用它的指令；不因此改写整个通用工具库。播放器的实际网络行为仍需以设备请求为准。

## 产物与范围

身份核对针对融合主模块及 base 身份补丁；单包构建把该补丁内嵌进主 AAR。已核对桥接方法、BI 序列化入口、修改类的数据流以及原厂固定字段所在类保持不变。原身份复核覆盖三个 TCL AAR 的 2,405 个 class；本次增加 CMP 的 167 个类，四个输入共 2,572 个不重复类。证据保存在 `output/ad-sdk-fusion/tcl-identity/audit/`。

复核结果：

- 四个原始 AAR 的 SHA-256 与登记的原厂输入一致；media/player/cmp 构建输入使用原始文件，单包打包仅重定向必要的资源字段引用。
- base 补丁只改变 6 个类中预期的 20 个方法逻辑；其余类字节码与 AAR 资源保持原样。ASM 自动重算的最大栈深和局部变量数单独排除，不当作业务逻辑变更。
- `VastAdRequestParams`、`SignInterceptor` 及其服务级固定配置均未改写；MovieArk、固定 appBundle、商店链接和 TCL SDK 版本保持原值。
- 对配套产物的 84 项身份入口、桥接链接和配置断言通过，修改类通过 ASM 数据流核对；扩大到全部 PackageManager 调用及 ApplicationInfo/PackageInfo 字段的复查没有发现新的 TCL 动态宿主身份上报来源。
- 融合模块和四个 TCL AAR 的有效 Manifest 没有要求 TCL 登记配置的占位符；融合适配器无宿主 TCL metadata 校验，登记读取直接进入桥接类。宿主只需原有 `adAppId`、`adChannelId`，不需要额外填写 TCL 配置或单独初始化 TCL。

身份补丁已随 `v1.0.18` 发布；单包构建已随 `v1.0.19` 发布，远程主 AAR 及依赖元数据已下载核对。上述历史发布仅核对静态路径和本地产物；本次 CMP 修复的设备验证另见 `CMP_REPAIR_VALIDATION.md`，服务端结果以该记录为准。
