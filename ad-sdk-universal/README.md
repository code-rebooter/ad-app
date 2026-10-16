# 通用广告 SDK（key 接入，当前验证阶段）

独立 module `ad-sdk-universal`，独立公开包 `com.smart.android.adsdk.universal`。原有 app、ad-sdk-fusion、ad-sdk-tcl 和其他 SDK 的源码、依赖接入保持原样；本 module 不依赖它们的 Gradle module，只读取已有的原厂 AAR 输入及依赖对齐清单。客户接入时只选择本 SDK，不能在同一 APK 中再打包另一份包含同名 TCL 原厂类的 SDK。

SDK 自身发布版本当前为 `1.0.0`，在本 module 的 build.gradle 定义；宿主可直接调用 `AdSdk.getSdkVersion()` 查询，无需先初始化。全部我方接口自动上传真实宿主的 `ad_version`、`ad_version_name` 和本 SDK 的 `sdk_version`，不使用 TCL 登记版本代替宿主版本。

## 客户接入

```groovy
implementation project(':ad-sdk-universal')
```

初始化和播放分别调用。初始化成功只表示 SDK 已就绪，不会自动播放广告。

### 初始化

宿主启动时调用 `initializeAds()`：

```java
import com.smart.android.adsdk.universal.*;

private void initializeAds() {
    AdSdk.initialize(context, "后台分配的sdk_key", new InitializationListener() {
        @Override public void onInitialized() {
            // 可在这里更新界面，无需保存就绪标记。
        }
        @Override public void onError(AdError error) {
            // 处理初始化失败，之后可重新调用 initialize。
        }
    });
}
```

### 播放

初始化成功后，宿主在需要展示广告时调用 `playAds()`：

```java
private void playAds() {
    AdSdk.play(container, new AdRequest.Builder().setSoundEnabled(true).build(),
        new AdListener() {
            @Override public void onLoaded(AdSession session) {}
            @Override public void onStarted(AdSession session) {}
            @Override public void onFinished(AdSession session, AdResult result) {}
        });
}
```

就绪状态由 SDK 管理，宿主无需保存 `sdkReady`。`play()` 自动检查；未初始化或初始化尚未完成时，通过 `onFinished` 返回 `INIT_NOT_CALLED` 错误。界面需要查询时调用 `AdSdk.isInitialized()`。

当前不需要 adChannelId、Google App ID 或 TCL 宿主 metadata 占位符。同一进程只接受一份成功初始化的 key；并发初始化同一 key 合并请求，不同 key 拒绝，初始化失败后允许重新请求。初始化成功前不进入原厂初始化；缺少配置不使用旧客户的固定值兜底。

一次 play 先执行 Google，再执行 TCL；Google 播放成功后仍执行 TCL。两路授权、请求 ID 和状态/日志报告分开，宿主结束回调一次。取消会终止正在执行的平台并阻止启动下一平台。SDK 只管理添加到宿主容器中的自己的子视图。

## 后台接口合约

可单独转给后台的文档：[配置接口](docs/sdk-config-api.md)、[现有接口字段变更](docs/backend-api-changes.md)。

默认我方接口域名由 SDK 内部 `KeyConfigClient.DEFAULT_BASE_URL` 配置，当前使用 `https://api.kytira.cc/`。后台需实现 `POST /api/v2/ad/sdk/config`，并在 Google `sdk/authorize` 成功响应中通过 `data.gam_config` 返回 `ad_tag_url` 和加载超时；旧 SDK 所用 `google-gam/resolve` 仍需保留。

配置请求：

```json
{
  "sdk_key": "客户接入key",
  "ad_version": 123,
  "ad_version_name": "1.2.3",
  "sdk_version": "1.0.0"
}
```

成功响应只要求：

```json
{
  "code": 100000,
  "data": {
    "google": {
      "channel_id": "后台生成的Google渠道号"
    },
    "tcl": {
      "channel_id": "后台生成的TCL渠道号",
      "package_name": "该key登记的包名",
      "app_name": "该key登记的应用名称",
      "version_name": "2.0.10",
      "version_code": 10,
      "signature_md5": "32位十六进制登记MD5",
      "app_key": "TCL原厂AppKey",
      "project_id": 213,
      "partner_name": "TCL登记partner"
    }
  }
}
```

示例渠道号、项目号、版本和名称只说明字段类型。google.channel_id 与 tcl.channel_id 分别对应后台为该 key 创建/绑定的两路业务渠道，均为必填非空字符串，SDK 不从一个渠道拼后缀推导另一个。其余八项来自该 key 对应的 TCL 登记信息，其中应用名和版本代码也维持原有统计身份。数字字段必须是整数；项目号大于 0，版本代码不小于 0；响应不需要 msg、enabled、config_version、ttl_seconds 或其他 Google 配置；请求自动携带真实宿主版本和 SDK 版本，无需宿主填写签名。

后台创建 sdk_key 时，为其创建/绑定 Google、TCL 两个独立渠道，并通过配置接口返回。客户接入 API 只传 key，SDK 内部保存本次初始化的两个渠道号，后续我方业务接口继续使用原有 channel_id 合约，不改为 sdk_key/provider 路由。

| 接口 | channel_id 来源 | 原有业务输入 |
| --- | --- | --- |
| sdk/flow-control | 当前平台对应的配置渠道号 | MAC、androidid、宿主版本、Android SDK 版本 |
| sdk/authorize | 当前平台对应的配置渠道号 | 原有真实宿主、设备、request_id 等；两路均带 androidid，Google 另带 rdid，响应通过 data.gam_config 返回链接及加载超时 |
| consent-popup / consent-report | 当前平台对应的配置渠道号 | 原有决策、设备和版本输入 |
| report / consent-log-report | 当前平台对应的配置渠道号 | 原有状态、请求 ID、设备、日志输入 |

全部 7 个接口共同携带 ad_version、ad_version_name、sdk_version。6 个原本传 mac 的业务接口均增加 androidid，Google、TCL 两路都传真实 ANDROID_ID，读取失败为空字符串；sdk/config 不传 mac 或 androidid。只有配置请求发送 sdk_key；上述业务请求不新增 sdk_key 或 provider。两个渠道各自控制其门禁、授权、CMP 决策和状态/日志上报，不共用一个业务渠道。任一渠道缺失或无效都使完整初始化失败，不回退到固定渠道、google/tcl 字面值或另一路渠道。

已有 flow-control 继续决定 enabled、skip_cmp 和日志开关，authorize 继续决定授权及相关播放策略。状态/日志接口保持原有语义。SDK 不主动把 sdk_key 或我方业务渠道追加到广告 URL；SdkLog 及状态诊断/日志载荷对 key、渠道号脱敏。原厂 TCL AppKey 与我方 sdk_key 分开使用。

客户不需要填写渠道号，公开 API 不提供渠道 getter。渠道号随配置返回并随业务请求发送，客户端抓包仍可查看它，不能把这一接入方式描述为渠道号完全不可见。

配置按 key 持久缓存，初始化优先使用有效缓存；独立定时刷新，不随广告周期请求。成功响应的 data.refresh_interval_seconds 控制刷新频率，缺失或无效默认 86400 秒。持续运行和重启均按上次成功获取时间计时；设备恢复后 1 分钟内检查到期，播放前也做本地检查。失败保留旧配置并按 1、2、4 分钟退避，最多每小时重试。有效更新在下一轮前应用；等待旧原厂初始化或 CMP 同步结束时，本次播放通过 onFinished 返回错误，宿主稍后重试。登记不同的原厂配置与同意缓存分别保存，CMP 补报保持原渠道。

## 两条原厂执行入口

Google 使用已有实验逻辑：门禁/CMP 业务决策 → 我方 authorize 通过 data.gam_config 返回 ad_tag_url → 返回链接原样进入 TCL 2.8.02 的成功取链响应边界 → 原厂 VAST/Wrapper、UniPlayer、检测和 XML 跟踪。rdid 沿用 `google_tcl_ad/aid` 的本地 UUID 首次生成、保存、复用规则；androidid 为真实 Settings.Secure.ANDROID_ID 或读取失败时的空字符串。客户端不新加 GAM 查询参数；原厂自己的 requestId 行为保留。

TCL 使用原有融合代码的业务决策、原厂 CMP、原厂配置及取链、解析播放、上报。其登记字段从不可变 key 配置读取；原厂固定 MovieArk/appBundle/商店地址保持原含义，设备值保留当前来源及已有公共兼容修正规则，我方业务接口的宿主身份保持实际来源。原厂初始化的渠道来源保留其未指定时的登记 partner 默认行为；取链扩展字段 channelName 不再填写我方内部业务渠道号，不额外增加配置字段。

原厂代码只打包一份。Google 阶段启用已审查的 supplied-URL 隔离边界；TCL 阶段保留这些位置的原厂业务实现。两路单独保存 AdConfigFetcher、初始化状态及原厂报告开关；每次 Google 入口同时绑定 HttpRequester 单例和 Ad 的内部 HTTP 缓存，并在返回/异常时恢复，避免下一次请求复用旧 supplied URL。数据库、原厂 BI/CMP 和 UniPlayer 自有 Tracker 的业务边界在 Google 阶段关闭，TCL 阶段保留原有路径。通用 POST、Cookie/TLS、原厂 XML 跟踪正常执行；不按广告 URL 的域名拦截。

统一打包包含当前公共参数/UA、视图尺寸、生命周期补丁，排除 SXK 专属补丁。UA、Android 版本/Build ID 和通用设备名称的已有规范化算法也从当前实验宿主复制到本 module；保留原有有效值及原有异常值修正规则，不用后台的应用登记配置重写设备字段。原厂登记桥接、资源字段和日志桥接均定向到本 module 的命名空间。

当前包不含 Google IMA、AndroidX Media3 或 UMP。Google skip_cmp=true 直接沿用实验路径；false 分支中要求 UMP 的 ACCEPT_ALL/SAVE_SETTINGS/REJECT 等动作返回无法执行，SKIP_ALREADY_DECIDED 不伪造同意状态，MAYBE_LATER 按现有业务延后动作处理。未来 UMP 通过现有 SDK 自有 CMP 接口接入，公开方法不引用 UMP 类型，届时另补实际初始化字段和同意信息合约。TCL 原厂 CMP 保留。

## 构建与核验

```sh
./gradlew :ad-sdk-universal:assembleRelease :ad-sdk-universal:testDebugUnitTest :ad-sdk-universal:lintRelease
```

产物：`ad-sdk-universal/build/outputs/aar/ad-sdk-universal-release.aar`。AAR 内嵌四份 TCL JAR 和资源；外部依赖仍由 Gradle 解析。如果分发裸 AAR，接入方需声明 build.gradle 中的外部依赖，不能只复制 AAR 就忽略依赖。

原厂输入哈希、依赖版本、禁止旧 SDK/IMA/Media3/UMP 的检查在 preBuild 执行。字节码边界记录位于 `build/generated/embeddedTcl/audit/`。Lint 仅对原厂生成资源中的缺失翻译及已存在的 drawable 文本使用路径限定忽略；自有源码正常检查。

本地测试及临时宿主 D8/R8 构建记录见 `artifacts/universal-sdk-validation-20261013/`。后台需部署 key 配置接口，为 key 绑定两个独立的有效渠道；提供有效 key 后验证实际取链、播放、平台切换及请求参数。当前本地构建结果不代表上述后台或新 SDK 真机联调已完成。
