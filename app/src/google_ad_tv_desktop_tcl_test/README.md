# Google 渠道 TCL 实验版

开发分支为 `experiment/google-tcl`，基线提交 `53e4ad4`；合入 `main` 后继续通过独立 flavor 选择实验方案。构建 flavor 为 `google_ad_tv_desktop_tcl_test`，当前按 V260904_1 渠道对齐：包名 `com.localvideoplayer.tv`，后台 channel_id、C_TYPE 和 MODEL 均为 `GOOGLE_AD_TV_DESKTOP_V260904_1`，版本 `1.0.10` / versionCode 11，签名使用该渠道原有的 `localVideoPlayerRelease`。默认业务接口地址与原 V260904_1 渠道一致。安装会覆盖相同包名且签名一致的旧应用；原有 `google_ad_tv_desktop_v260904_1` 和九猫构建配置、实现保持原样。

## 业务和广告边界

`业务触发 → flow-control → 按 skip_cmp 处理业务决策 → sdk/authorize → 宿主窗口 → google-gam/resolve → 原样传入 adurl → TCL VAST/Wrapper、UniPlayer、检测和 XML 回报（沿用旧 TCL 公共补丁）`。

复用现有业务总开关、授权、轮询、窗口/信号状态控制、状态上报、日志上报和服务端日志开关。正式 Google flavor 的源码目录及 IMA/Media3/UMP 依赖声明保留。共享 BuildFlavor 新增实验版的家族识别，实验版不归入 UMP 专属渠道分支。HaierUserAgentInstaller 的生效名单新增此实验 flavor，使旧公共 UA 策略在该构建中执行。

实验阶段服务端设置 `skip_cmp=true`。为保留 false 分支，实验版继续调用 consent-popup；ACCEPT_ALL/SAVE_SETTINGS/REJECT 需要 UMP，记录无法执行并阻断，不调用 consent-report 伪报成功；SKIP_ALREADY_DECIDED 因本地 SDK 状态不可用而阻断。MAYBE_LATER 沿用原有业务延期动作和 consent-report，再进入授权。共享 AdConfigManager 原有的请求失败/未知动作转 MAYBE_LATER 规则保留。

GoogleTclResolveClient 保留原请求体 channel_id/mac 与 ad_tag_url 返回字段，并新增 rdid/androidid：rdid 按 TCL 的本地空值检查、UUID.randomUUID().toString() 生成及保存复用规则实现，使用自有 google_tcl_ad/aid 存储；androidid 读取系统 ANDROID_ID，读取失败上传空字符串。这里只向自有 resolve 接口上传，后台控制是否拼接广告参数；上传的自建 UUID 不等于已验证的 Google 系统广告标识。客户端不会给返回链接补 Google、雷鸟或同意参数；后续 requestId 等处理仍由 TCL 原厂引擎完成。后端原有 IMA 加载/启动超时字段仅记录，广告内部超时使用 TCL 原厂配置；业务授权返回的宿主回调超时继续生效。

广告进入方式与已验证 Demo 相同：lazyLoad、WATERFALL、playerType=0、playerViewType=1，在原厂 onAdLoaded 时立即 controller.start。原始回调逐次记录，包括重复完成和两种开始回调。宿主窗口完成与倒计时各只收尾一次，不干预原厂 XML 跟踪。窗口关闭及独立广告机会交接执行原厂 release；请求中 release 的原厂异常只记录，不加入其他播放器回退。

## 旧 TCL 公共补丁对齐

仅在实验 app 打包时通过 TclExperimentVisitorFactory 应用 LsapClassPatcher 的公共补丁；共享 ad-sdk-tcl 三个隔离运行时的 AAR 不作此改动，其他 app flavor 不走该 visitor。

- 沿用旧参数补丁的设备信息读取、System HTTP UA、OkHttp 和 URLConnection 请求 UA，以及 WebView 相关桥接规则。
- 沿用旧 ViewSizeChecker 的尺寸/挂载检查规则和旧 LifecycleOwnerAgent / 控制器的生命周期、视图脱离处理。
- 明确排除 SXK 的渲染视图替换、音频焦点禁用、音轨禁用及 12fps 限帧四项。九猫包内的 TCL/ExoPlayer 类不允许出现这些 SXK 策略调用。
- 保留现有 adurl-only 隔离：不恢复已删除的 BI/数据库、TCL 配置联网或持久广告统计；通用 HTTP POST 保留原厂实现，业务取广告链接入口单独隔离；XML 类型许可继续收紧。

对真实固定输入作比较：2,215 个隔离原厂类中，除四个 SXK 目标类外，其余 2,211 个类的公共补丁结果与旧 TCL 补丁逐字节一致。SXK 目标类只应用其中公共参数规则，不插入 SXK 策略。当前共 31 个类有公共改动。

assemble 自动检查最终 DEX 中全部 31 个类的桥接调用和 10 个关键策略方法，验证实际打包结果。Release 核验按 R8 mapping 处理外部 AndroidX 类型名称变化，不放宽方法体检查。2,215 个原厂类全部保留指类定义和名称齐全，不表示这 31 个类的方法体仍未改动。

## 打包门禁

实验编译及运行依赖图不得含 Google IMA、Google UMP、AndroidX Media3 或旧广告 SDK module。逐个检查运行依赖类归档，包括本地 AAR 中的内嵌类。最终 APK 再检查所有 DEX 类型、Manifest 和资源，逐个核对三个隔离运行时中的全部原厂 class 在 APK 中仍有原名的类定义（不能只检查类型引用），必须保留 TCL VAST/UniPlayer，且不得含旧 Google 广告执行类、UMP 页面/主题/application ID 元数据。

实验 sourceSets 不包含正式 Google 播放器/UMP 源码或其专属资源；共享 hq008 业务源码在构建时同步到独立生成目录，物理排除旧 SDK/CMP 文件，Kotlin 与 lint 使用相同输入。TCL 的 com.tcl.ff.component.media.ima VAST 模型和定制 ExoPlayer 保留，不按 ima/ExoPlayer 字样误删。

原厂播放器包未提供可选 preload 插件；原厂 OPEN_PROXY_DEFAULT 默认 false，当前初始化只启用 WHETHER_PARSE_TCL_ADS。实验 flavor 的 R8 规则仅忽略该未启用插件的四个缺失类型，不添加缓存插件、不修改SDK 运行时原件、不影响正式 flavor。额外保留原厂 TUniPlayer 及底层播放器类的名称、成员和方法体，避免原厂旧 consumer rules 中未覆盖的类被再次混淆、合并或内联。

## 构建与验证

环境需要已配置的 JDK、Android SDK 和 Python 3。若 python3 不在 PATH，用 googleTclPython 指定解释器。

```bash
./gradlew :app:verifyGoogleTclDebugApk \
  :app:testGoogle_ad_tv_desktop_tcl_testDebugUnitTest --tests '*GoogleTclBusinessDecisionTest' \
  -PgoogleTclPython=/path/to/python3
./gradlew :app:verifyGoogleTclReleaseApk :app:lintGoogle_ad_tv_desktop_tcl_testDebug \
  -PgoogleTclPython=/path/to/python3
```

assemble 实验包时自动执行 APK 核验；检查失败则构建失败。核验报告输出到 app/build/reports/google-tcl，业务分支测试见实验 flavor 的测试结果目录。全量旧源码约定测试存在基线失败，须单独记录，不能把新增测试通过说成全量测试通过。

初版采用 `.tcltest` 独立包名。2026-10-10 九猫交测版使用九猫原应用身份；正式库存收益对照仍需要真实后台配置及独立测试设备，不能以测试素材播放成功推断收益结果。

## 真机网络核验

实验 Debug 包在 `persist.sys.ad.log=true` 时，用 OkHttp 观察拦截器保存原厂 HTTP 请求/响应记录和 VAST 副本，位于应用外部私有目录 `google-tcl-capture`。请求保持原样；VAST 副本通过 peekBody 读取，原响应继续交给 TCL。Release 使用空实现，不包含该拦截器或文件记录器。真实总开关、授权与 resolve 均正常执行，广告链接使用后台原始返回值。

## 2026-10-10 X88 实测

后台 resolve 返回 Google 官方 10 秒测试广告后，Debug 和 R8 Release 的真实门禁、授权、解析播放及 REQUESTED/LOADED/STARTED/COMPLETED 状态上报通过。Debug 网络记录确认 XML 的 impression、start、25%、50%、75%、complete 六个跟踪请求均 HTTP 200；原输入参数保持，requestId 由原厂流程增加。日志上传实现保留，后台 popup_log_enabled=false 时不发送。

两种最终 APK 逐个保留隔离运行时的全部 2,215 个原厂类，IMA/Media3/UMP 等禁止内容违规 0。新增 5 项业务测试通过；lint 仍有 4 项原共享 Manifest 错误，正式 Google 亦有同项，未修改正式代码来消除这些错误。详细证据：artifacts/google-tcl-channel-experiment-20261010/report.md。

## 九猫历史交测状态（2026-10-10）

九猫配置及公共补丁已完成交测构建。2026-10-10 11:45（Asia/Shanghai），后台缓存更新后，九猫签名 Release 的 flow-control enabled=true、skip_cmp=true，authorize authorized=true，后台真实 Google 官方 10 秒测试标签完整播放，四种业务上报被后台确认。11:47 的 Debug 网络复核确认 XML 六个跟踪请求均 HTTP 200。最终设备安装恢复为交付 Release，并核对 SHA-256 一致；实际业务日志上传及正式库存收益仍待交测。证据和交付文件见 artifacts/google-tcl-channel-experiment-20261010/jiu-mao。
