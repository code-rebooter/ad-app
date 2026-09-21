# TCL 2.8.02 身份配置覆盖核对

核对对象为融合模块实际使用的 base 补丁 AAR、原厂 media AAR、原厂 player AAR，共 2,405 个 class。检查了包名、签名、初始化参数的调用与字段来源，并追踪到请求参数和请求头的生成位置。

第一版补丁确有遗漏：`HttpRequester` 构造函数直接读取宿主包名，存入 `SignInterceptor.BuildConfig`，最终发送为 `XTCL-App` 请求头。`fusion-tcl-identity-2` 已补上此入口，base AAR 修改类数由 4 个变为 5 个。

## 使用统一登记配置的路径

| 路径 | 参数来源及结果 |
| --- | --- |
| TCL 初始化与 SDK 配置请求 | `BasicParameters` 的 Key、partner、project getter 全部转到桥接类；配置接口 `adProjectId=213` |
| 广告请求 | `VastAdRequestParams` 从上述 getter 取得 `appPackage`、`asfm`、`appKey`、`DevicePartner`、`application` |
| 广告请求的 `appDomain` | `TclAdPlayer` 显式传入 `TclIdentityBridge.getPackageName()`；原厂参数生成器将其写入请求 |
| HTTP 请求头 | `HttpRequester` 从桥接类设置包名，`SignInterceptor` 输出 `XTCL-App=com.google.android.adhq1001` |
| TCL 广告 BI 参数 | `BiInitialization` / `BiReportUtil` 的应用包名、partner、project 来自 `BasicParameters` |
| TCL 基础 BI | `BaseDataInfo.init` 最终写入登记包名和项目号；`GetBaseDataInfo.getAPPSecretString` 返回登记 MD5 的小写形式 |
| 直接签名读取入口 | `Md5Utils.getSignatureMd5` 返回登记 MD5 的大写形式；原私有签名散列辅助方法不再由该入口调用 |

TCL 业务登记配置仍为 `com.google.android.adhq1001` / 项目 `213` / partner `chhkj_1`，MD5 和 App Key 使用项目方提供的对应值。

## 保留原有含义的包名

以下不是 TCL 登记身份的读取入口，保持真实系统或广告数据来源：

- `BasicParameters.a/b`、`BaseDataInfo.getAppBaseInfo/init` 中的安装信息、应用名称和 Manifest 查询。
- `DataReport$b.run` 中的主进程判断，比较当前 PID 的进程名和真实宿主包名。
- ExoPlayer 原始资源 URI、`Resources.getIdentifier`、下载任务调度、系统 `MediaSessionCompat` 和编解码兼容判断。
- 原厂播放器的本地诊断日志、指定应用 `com.tcl.cyberui` 的版本读取、待安装 APK 的包名解析。
- 点击广告时的目标应用包名，以及打开 Google Play 时传入的真实宿主来源。
- 融合模块读取宿主自己的 IAB SharedPreferences；Google 和自有后台的宿主设备信息采集。

原厂 `VastAdRequestParams.generateBaseParams()` 对外部接入还会固定输出 `appBundle=com.tcl.movieark`，默认商店 URL 也指向该包名；这与另外发送的 `appPackage` / `asfm` 是不同字段。本次保留这段原厂逻辑，没有将其当作漏掉的宿主包名读取而替换。

## 已确认的范围

对生成的真实补丁字节码和融合主 AAR 执行了 36 项身份入口/桥接链接断言，结果通过；5 个修改类的方法通过 ASM 数据流检查。最终 `:ad-sdk-fusion:assembleRelease` 构建通过，主 AAR 包含匹配的桥接类。

本轮没有运行整套回归或操作设备，也没有发送真实广告请求。因此可以确认本版本已追踪的登记身份读取路径和构建产物，不能据此断言 TCL 服务端已经接受配置或真实广告一定播放成功。

本地证据保存在项目 `output/ad-sdk-fusion/tcl-identity/audit/`，包括扫描代码、最终引用清单、断言结果和产物 SHA-256。
