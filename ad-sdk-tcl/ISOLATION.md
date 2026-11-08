# adurl-only 执行范围

2026-11-06 交付修正：SDK 独立桥接在原厂 `c/d.a(VastRequest, Ad, AdMaxDuration)` 素材构建边界捕获 `RuntimeException`，记录 `vast_parse_failed` 后返回 null，交由原厂调用者已有的空素材失败分支处理。正常 XML 解析、素材选择和 tracking 仍执行原厂实现；不改写 XML，不捕获 Error。此项修复真机空 InLine 引发的空指针崩溃，仅作用于 SDK，app 的观察补丁不启用该恢复行为。因此交付 runtime-media 与原厂或旧观察版并非逐字节、逐异常行为相同。当前验收见 [二合一交付验证](../docs/reports/2026-11-06-combined-sdk-customer-validation.md)。

2026-11-05 补充：adurl-only-v4 仍是原厂业务隔离参考；交付 jar 在该参考上添加与 app 相同的运行时观察点。显式 ExecutionConfig 可调用宿主后台 patch，并在 google_tcl_ad/aid 保存宿主 rdid；该存储属于宿主后台参数，不恢复 TCL 原厂 aid、BI、配置或业务取链入口。具体行为见 README 的本轮执行配置。

2026-10-12，执行 profile `adurl-only-v4`。保留原厂需要的广告链路，同时禁止与该广告链路无关的 TCL 自有功能。不能再用“原厂全部 class 相同”代替副作用隔离证明。

## 允许

- 宿主提供的 adurl / 保底地址、XML 中的 Wrapper。
- 原厂 VAST 模型、素材选择、在线播放、状态回调。
- XML 指定的曝光、进度、完成、错误、点击等广告回报。
- 上述内容引用的雷鸟域名仍可访问；不按域名一刀切。
- 播放所需的媒体读取、Exo 媒体缓存读写/索引数据库、通用文件工具和 Android 系统运行缓存。测试记录属于 demo，不放进 SDK。
- 原厂 UniPlayer 的缓存代理配置判断及清晰度切换预加载代码；当前原始输入未提供其可选插件，不引入未知实现，不强制开启。
- 原厂 HttpRequester 的通用 GET/POST；TCL 取链通过专用的本地输入边界隔离。
- 原厂媒体 HTTP 的 CookieManager，以及媒体 Host 请求头对应的 HTTPS 主机名校验和 reset/release 恢复行为。这些设置不指定 TCL 服务，不属于 TCL 业务隔离范围。它们仍使用原厂进程默认设置；不改写成自有实现。

## 禁止及措施

| 项目 | 执行限制 |
| --- | --- |
| TCL BI/UX/错误统计客户端与数据库 | 删除 adsdkbi、混淆 BI 实现及 TCL LitePal 数据库共 190 个 class；切断兼容入口 |
| 原厂 SDK 配置拉取及缓存 | preload、读缓存、拉远端和写缓存入口返回；运行配置仅来自显式输入或随 SDK 固定快照 |
| 原厂取链接请求 | 宿主已提供链接，业务请求方法仅调用新增的 resolveSuppliedAdAddress；默认返回空，宿主桥接只返回已提供的链接；通用 POST 保留 |
| 持久广告统计、生成并保存 TCL aid | 计数和偏好存取入口禁用；不写 media_ad_sp / tcl_ad，不生成持久 aid |
| UniPlayer 自有 Tracker | 注册清空实现，两种事件报告入口返回；不影响 VAST XML 广告回报 |
| TCL 配置文件及配置线程 | 配置读写及私有启动入口禁止，构造器不读取文件目录；通用 FileUtils 恢复 |
| TCL 浏览器推广安装及指定浏览器 | 保留普通广告落地页 Intent，取消绑定 TCL 浏览器和跳转其安装页面 |
| 任意 Java 对象反序列化 | XStream 仅允许原厂 VAST 模型及必要基础集合，保留原厂注解和转换过程 |
| 混入完整库或额外统计/广告插件 | 初始化核验三个隔离标记及禁止类；不满足即拒绝启动 |

VAST 自身的错误回报和第三方跟踪并未关闭。“SDK 错误统计”与“VAST Error URL”是两条不同路径。

## 修改范围与证据

输入仍按原始 AAR 的 SHA-256 固定。隔离 AAR 删除 190 个非必要类，13 个类做边界隔离改动，另外 2,202 个原厂 class 保持字节一致。构建还逐方法验证：修改类中未列为隔离点的方法保持一致，禁止出现新增的、指向已删除实现的引用。

修改清单由构建生成 `runtime-*/build/original/isolation-manifest.json`。保底、Wrapper、媒体选择、播放控制及 VAST 回报本体未被重写。XML 的 Java 类型权限是有意收紧的执行边界，不允许利用广告 XML 实例化非广告对象。

当前 28 项 SDK 单元测试通过，包括禁止类型、外部 XML 网络、TCL 取链边界、正常广告 GET/POST、通用文件写入及原厂代理配置判断。SDK、运行时 AAR 和独立 demo 已构建。

**以下手机验收对应早前 v1 隔离版，未覆盖本轮新增限制。**使用 `M2012K11AC`（Android 13）的独立包 `com.smart.android.tclad.demo.isolated`。初始化/主动触发禁用入口的网络尝试为 0，广告后无 BI 线程、数据库文件、TCL 偏好/配置文件；XML 类型及外部 DTD/实体负向测试通过。Google 与雷鸟链接分别完整播放 10 秒和 60 秒，8 组行为与未修改原厂的历史基线一致。原厂重复完成回调与请求中 release 崩溃保留。网络证据是进程代理选择与 HttpRequester 记录，不是原始套接字抓包。
[当前隔离记录](../artifacts/tcl-isolation-20261009/report.md)。旧整包记录单独保存，不能替代本次隔离证据。

最终包又复跑了 8 组，并完成两种同进程交接。正常两轮之间由宿主显式调用原厂 release；未 release 直接复用的间歇停播记录仍保留，确切归因尚未核实。两个真实链接的实播 APK 与最终交付 APK 不同，三个运行时 AAR 的哈希相同；版本对应关系详见当前报告和 acceptance.json。


## 2026-10-10 v2 补漏复核

完整扫描三份原厂 AAR 及隔离 JAR，新增禁止配置线程/业务文件工具、无数据库缓存、代理及预加载、Tracker、全局 Cookie/TLS 设置和 TCL 浏览器安装推广。SDK 强制核验三个 v2 marker。Debug/R8 Release APK 检查新增 23 个隔离类、52 个边界方法及全局 setter，均通过；公共补丁继续保留，SXK 排除。

用户已授权的 `mstar` 设备（Android 10）负向探针通过：初始化联网/BI 线程/禁止文件为 0；6 个本地用例完成可见播放、HTTP 在线素材、Wrapper、媒体错误、空/无效 XML 回归，全局 Cookie/TLS 不变。29 项 SDK 单测和 5 项实验业务测试通过，SDK lint 无错误。应用既有 12 项全量测试失败及 4 项 lint 错误保持记录。

本轮电脑代理端口未监听，未重跑 Google/雷鸟真实 HTTPS 实播。先前交测包未覆盖，不包含 v2 补漏。保留的 Exo 媒体格式、系统监听及闲置工具类、隔离判断范围和完整证据见 [v2 审计记录](../artifacts/tcl-isolation-audit-20261010/report.md)。


## 2026-10-12 v3 隔离范围修正

用户明确隔离目标是 TCL 自有业务及与 TCL 的交互。v2 将通用媒体网络的进程默认 Cookie/TLS 设置也禁止，超出了该范围；v3 撤销这两处规则。`com.tcl.tuniplayer_exo.f.a` 和 `TUniBasePlayer` 整类恢复为输入 AAR 的原厂字节。

AAR 核验明确要求这两类与输入完全相同。AAR 保留原厂 1 个 CookieHandler setter 及 5 个默认 HostnameVerifier setter；D8 合并 setDataSource 两条等价分支，DEX 对应为 1 个 Cookie 和 4 个 HostnameVerifier 调用。最终 APK 核验要求所在类、方法和编译后调用数全部匹配；其他全局网络 setter 仍不得新增。demo 对进程设置改为被动记录，同时主动验证原厂 CookieManager 的同源 Cookie 会话。业务隔离规则继续执行。

本次没有修改此前交测 APK；v2 历史报告保留原结果。新版本及覆盖范围见 [v3 修正核验记录](../artifacts/tcl-network-scope-correction-20261012/report.md)。


## 2026-10-12 v4 全部隔离规则复核

按原始三份 AAR 重扫全部 class 及调用点，逐项审查 v3 的全部 21 个修改类、190 个删除类、适配入口和实验包限制。新增确认 8 个类被过度禁用：FileUtils、UniPlayer 配置/清晰度预加载、Exo 缓存工厂、SimpleCache、CacheDataSink 及两个媒体数据库提供器，现已完整恢复。连同 v3 恢复的 Cookie/TLS 两类，10 个类都明确要求与原件逐字节一致。

原厂通用 POST 完整保留；原厂取链请求改调用新增的本地 resolveSuppliedAdAddress 边界，默认不发网络。广告落地页恢复正常浏览器选择，同时继续取消 TCL 浏览器安装推广。剩余 13 个修改类的用途、保留理由及调用点逐项记录，非隔离方法继续核验不变。

原厂缓存索引数据库与 TCL BI 数据库分开处理。前者恢复，后者依然物理删除。当前 Proxy 可选插件未在原厂输入中提供；恢复现有代码和默认配置，不增加未知依赖，不能把插件未提供表述为已完整验证该插件功能。

详见 [全部规则复核](../artifacts/tcl-isolation-scope-review-20261012/report.md)；v2/v3 报告保留其历史结果，不替代 v4 验证。此前交测包未覆盖。
