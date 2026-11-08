# Google 纯 IMA + TCL 二合一 SDK

二合一版本 2026.11.06.1 的复审修复、验证结果和产物哈希见 [完整验证报告](../../docs/reports/2026-11-06-sdk-review-fixes-validation.md)。最低 API 24；Google 为纯 IMA 路径。

最低 Android API 24，编译使用 Java 17 / compileSdk 36。项目内使用：

```groovy
implementation project(':ad-sdk-tcl:combined')
```

客户交付仓库使用一个坐标：

```groovy
implementation 'com.smart.android:ad-sdk-tcl-combined:2026.11.06.1'
```

完整复制客户包的 `repository` 目录，并按包内 README 配置 Maven 仓库。聚合 AAR 通过 POM/Gradle metadata 传递引入两套执行层、三个原厂运行时和五份受控 Google 依赖；不能只复制一个 AAR。

## Google 纯 IMA

```java
GoogleImaExecutionConfig config = GoogleImaExecutionConfig.builder(context, requestId, channelId)
    .callbackTimeoutSeconds(180L).auditEnabled(popupLogEnabled).build();
GoogleImaAdManager google = new GoogleImaAdManager(config, listener);
google.showAd(container, gamConfig, physicalSoundEnabled);
// 窗口销毁时 google.release();
```

类型来自 `com.smart.android.tclad.ima`。`gamConfig` 是本轮 authorize 返回的 Gson JsonElement，含 `ad_tag_url`、`reported_sound_mode`、加载/起播期限和 `enable_fake_ctr`。Google 入口执行 Media3 1.8.0 + IMA 3.35.1；Core 生成完整 GAM 参数后调用后台 patch，不使用自拼接方案。

`onAdReport` 提供 AD_REQUESTED / AD_FILLED / AD_LOADED / AD_STARTED / AD_COMPLETED / AD_ERROR 以及授权启用后的点击事件；宿主保存并发送这些事件。Google 的播放与声音声明统一采用 `physicalSoundEnabled`；`reported_sound_mode` 控制四条上报通道（vpmute / ActiveView / OM 设备音量 / IMA 百分比）的覆盖值。

`onAdCompleted` 用于结束宿主窗口流程：广告正常结束会调用；缺失配置或外部门禁拒绝时，也会在 AD_ERROR 后调用以结束该轮调度。应依据 `onAdReport` 的 AD_COMPLETED 判断广告成功播放，不要只依据窗口流程回调。`onAdFailed` 处理请求/播放错误，`onAdCancelled` 处理取消和被新轮替换。

完成后的测量结算期为 1500ms，提前 release 不截短结算期；期间的新请求等待旧轮结算。实际 verification WebView 的销毁也保留 OMID 结束事件传递所需时间。SDK 不伪造测量事件。

等待结算的新请求也可取消，释放后不会延迟复活；新轮替换以最新请求为准。Google/TCL 同步宿主回调内 release 或替换后，不再补发旧轮 FILLED/LOADED/STARTED 等阶段回调。

## TCL 原厂

```java
ExecutionConfig config = ExecutionConfig.builder(context, requestId, channelId)
    .callbackTimeoutSeconds(180L).auditEnabled(popupLogEnabled).build();
Controller tcl = Ad.get().begin(context).lazyLoad().setPlayerType(0).setPlayerViewType(1)
    .setExecutionConfig(config).setVolume(physicalSoundEnabled ? 1f : 0f)
    .setResolvedAd(new ResolvedAd.Builder(adurl).build())
    .listen(new AdStatusListener() {
        @Override public void onAdLoaded(Controller loaded) { loaded.start(container); }
        @Override public void onAdStartPlay(double seconds) { /* 一次宿主开始事件 */ }
        @Override public void onAdFinished() { /* 结束本轮 */ }
        @Override public void onAdError(int code) { /* 处理本轮错误 */ }
    }).start();
// 窗口销毁时 tcl.release();
```

类型来自 `com.smart.android.tclad`。执行原厂 TCL 2.8.02 的 VAST/Wrapper、选材、播放和 tracking。原厂业务配置/统计/取链等无关入口按隔离清单禁用；如需 SDK 取链，可使用 `WaterfallAdTagProvider` 显式指定宿主后台地址、58 字段请求模板和 HTTP client。原厂默认配置快照随包提供，可在初始化前设置明确的新配置。

`onAdRequested` 只在真实取链 HTTP 收到响应后确认；直接提供已取得的 adurl 不会制造取链请求事件。网络超时会及时回调错误，主动取消不回调错误。`onAdFilled` 需要实际有效 VAST。`onAdDiagnostics`、`onAdExecutionEvent`、`onAdAudit` 分别提供终态诊断、运行事件和审计；宿主负责转为已有上报接口的 payload。

后台 patch 默认关闭。需要 SDK 调用 `google-gam/patch` 时显式设置 `backendPatchEnabled(true)`；默认行为是直接使用已下发的链接。原厂保底会单独 patch 后请求。TCL 不提供 Google 专有的音量上报、OMID/ActiveView 或模拟点击接口。

无效 TCL XML 导致原厂素材构建抛出 RuntimeException 时，SDK 记录 `vast_parse_failed` 并进入原厂已有的空素材失败分支，避免空 InLine 使进程崩溃；正常解析与 tracking 继续执行原厂逻辑。原厂 `getVolume()` 反映初始配置，动态 `setVolume()` 转发播放器，不能用初始配置字段判断动态音量是否变化。

## 会话与宿主职责

- 每次业务授权创建独立 execution/config 和 manager/controller，冻结 request_id / channel_id / 设备参数。
- 同一引擎的新轮会结束上一轮；跨引擎切换先由宿主 release 上一引擎，再启动另一引擎。
- start/load/终态按本轮去重，旧轮网络和迟到回调不能结束新轮。窗口销毁必须调用对应 release；TCL 的 pause/resume/setVolume 转发原厂。
- `onPlaybackDeadline` 用于同步宿主窗口守护期限，防止 180 秒请求期限误杀长广告。
- 宿主继续负责 CMP/UMP、authorize、业务调度以及报告 HTTP 的持久化/发送/重试。SDK 回调不等于生产上报接口已经收报。
- customer consumer 已开启 R8，consumer rules 随 AAR 传递；无需关闭混淆。

可复现打包入口为 `python3 ad-sdk-tcl/tools/package_customer_sdk.py`。真机用例和原始验证器位于 `ad-sdk-tcl/google-ima/tools`；验证报告与实际产物哈希随最终客户包附上。

### 音量闭环验收（2026-11-06）

- 实际播放器音量决定初始请求的 `vpmute` / `adWillPlayMuted`；系统音量独立测量，不跟随播放器静音伪装为 0。
- 固定版本 Media3 适配器关闭媒体预加载，连续广告串行加载，避免下一段尚未初始化的播放器状态进入当前段的 ActiveView 测量。代价是切段需要等待真实素材加载；不改写测量消息或 URL。
- 每段广告和实际音量变化都通过真实 `onVolumeChanged` 回调传递；OM start/volumeChange 只读观察。
- `volume_chain_audit` 分别比较最后实际快照与 IMA 输入、回调值、OM 媒体值；任一已观测矛盾输出 `observed_mismatch`，缺值不当成功。历史变化查各自时间线，不把末值一致当整段时间线一致。
- 客户端 `complete_to_omid_service` 不是独立验证端确认。验收脚本单独核对本地验证脚本的实际收件。
- 真机矩阵入口为 `ad-sdk-google-ima/src/test/device/run-volume-matrix.py`；最终证据与命令见 `docs/reports/2026-11-06-google-ima-real-volume-closure.md`。

本轮验收仍保留一项实际失败：有声连续三广告的 ActiveView 首条音量与系统比例不一致，Debug 与 R8 均复现；详见上述实测报告，不能据此宣称全部通过。
