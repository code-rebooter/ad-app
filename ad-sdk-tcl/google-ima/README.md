# Google 纯 IMA SDK

二合一版本 2026.11.06.1 的复审修复、验证结果和产物哈希见 [完整验证报告](../../docs/reports/2026-11-06-sdk-review-fixes-validation.md)。最低 API 24；Google 为纯 IMA 路径。

最低 Android 版本统一为 API 24（Android 7.0）。

取消/替换/窗口销毁时，按本轮归属关闭实际原生 XML HTTP 连接和 WebView patch 连接；响应体仍未读完时也停止读取。原生工作线程在正常返回和异常返回时均恢复归属，旧轮的取消不会关闭新轮连接。Google 完成后的 1500ms 测量结算期仍保留。

结算期内等待启动的新 manager 同样支持 `release()`；被取消的排队请求不会在旧轮收尾后重新启动。多个新请求以最新请求为准，释放旧 manager 不会取消后来者。宿主在同步报告回调内取消或替换广告后，SDK 不再继续发送该轮的加载/开始回调。

从 app module 的 `googleImaRuntime` 迁入生产纯 IMA 路径。依赖和固件桥接与原 TCL 模块独立，播放执行 Media3 1.8.0 + IMA 3.35.1；输入是本轮 authorize 的基础 `gam_config.ad_tag_url`。

```groovy
implementation project(':ad-sdk-tcl:google-ima')
```

```java
GoogleImaExecutionConfig execution = GoogleImaExecutionConfig
    .builder(context, requestId, channelId)
    .callbackTimeoutSeconds(callbackTimeoutSeconds)
    .auditEnabled(popupLogEnabled)
    .hiddenMode(hiddenMode)
    .build();

GoogleImaAdManager manager = new GoogleImaAdManager(execution,
    new GoogleImaSessionListener() {
        @Override public void onAdStarted() { /* 开始宿主倒计时 */ }
        @Override public void onAdCompleted() { /* 关闭广告窗口 */ }
        @Override public void onAdFailed() { /* 结束本轮错误流程 */ }
        @Override public void onAdReport(String requestId, String eventType,
                String message, java.util.Map<String, Object> diagnostics) {
            // 接宿主现有 ad/report：保存并发送，失败时沿用宿主重试机制。
        }
        @Override public void onAdAudit(String requestId, String summaryJson) {
            // 接宿主现有 consent-log-report 的 IMA_RUNTIME_AUDIT 条目。
        }
    });
manager.showAd(container, gamConfig, physicalSoundEnabled);
// 窗口销毁时释放；每轮授权创建独立 execution/manager。
// manager.release();
```

公开类型位于 `com.smart.android.tclad.ima`。`gamConfig` 是 Gson JsonElement，可通过 `JsonParser.parseString()` 读取宿主保存的授权配置。宿主继续负责调度、CMP/UMP 和 authorize；播放器读取宿主已有的 IAB 偏好。`isPlaybackAllowed()` 和 `playbackDeniedReason()` 可接宿主信号门禁，`onPlaybackDeadline()` 可延长宿主窗口守护期限。

构建 execution 会读取宿主既有的 `google_tcl_ad/aid`，与同进程 TCL/app/shared core 在同一 canonical-path 进程锁和文件锁内初始化。存储不可用或首次 commit 失败会抛出 `IllegalStateException`，宿主应按本轮配置准备失败处理；SDK 不会降级生成未确认的 ID。该机制不改变 ID 格式、上报策略或跨应用隔离，也不承诺 Android SharedPreferences 多进程缓存一致。

## 同步内容

- 本轮授权配置：ad_tag_url、加载/起播期限、reported_sound_mode、enable_fake_ctr。未提供有效 tag 时结束本轮，不使用上一轮配置。
- 后台 patch 由 `gam_config.enable_gam_patch` 控制，默认关闭；显式下发 `1` 时 IMA Core 生成最终链接后调用。原生 HTTP 与 WebView 回退共享一次 patch，取消中断在途请求，失败阻止原广告请求。request_id/channel_id 和设备参数按本轮冻结，sdk_version 为 3.35.1。
- 音量上报由 `reported_sound_mode` 控制：非静音时上报读到的系统音量（index/max，过低或读不到时按设备种子兜底），静音时上报 0；`vpmute` 与之一致。
- 首帧和媒体推进证据、逐广告实例身份、广告串切换、素材时长预算、缓冲软预算与固定硬期限、停滞及起播超时、迟到回调隔离和终态去重。完成后保留 1500ms 测量结算窗口，宿主提前 release 不截短该窗口；结算期间请求下一轮时等待上一轮结算完成。
- VAST/Wrapper、有效填充、广告进度、原生 HTTP/WebView、ActiveView、OMID、身份和声音测量审计。审计输出沿用 app 汇总结构。
- enable_fake_ctr 按本轮授权执行纯 IMA 生产参数：30% 触发概率、20%–80% 进度窗口，收到 UI 点击路由确认后才通过 onAdReport 输出 AD_FAKE_CTR。未迁入 app 的 Debug 样本和强制放行。

播放状态和点击事件通过 `onAdReport()` 交回宿主现有上报传输；trace 和审计由本轮 auditEnabled 控制，状态报告不受该开关影响。SDK 直接调用 GAM patch；默认地址为 `https://api.kytira.cc/api/v2/ad/google-gam/patch`，可通过 `patchEndpoint()` 指定 HTTPS 接口。

## 核心和受控依赖

编译复用 `ad-sdk-google-ima/src/main/java` 的同版纯 IMA 核心源文件，将原先指向 app 的固件/URL 桥接引用改为 SDK 内部桥接。固件、UA、Client Hints 与 URL 参数修正规则来自 app 当前实现。独立 SDK 无需依赖 app 的 Hq008 类。

`controlled-maven` 保存 app 受控 AAR 的独立桥接版本，保持 IMA、Media3 IMA、TV Ads、广告 ID 和 AppSet 的版本及控制逻辑，只重定向固件桥接引用，并在 POM 中排除对应原版模块，防止普通宿主引入重复类。preBuild 会校验原受控输入以及本模块五份 AAR/POM，缺失或过期会使构建失败。更新原受控输入后执行：

```sh
python3 ad-sdk-tcl/google-ima/tools/prepare_controlled_runtime.py
```

此子模块自带同版 GoogleIma 核心公开类，宿主选择本模块时使用这里的核心，避免同时依赖另一份 `ad-sdk-google-ima`。

## 检查入口

```sh
./gradlew :ad-sdk-tcl:google-ima:assembleDebug :ad-sdk-tcl:google-ima:assembleRelease :ad-sdk-tcl:google-ima:testDebugUnitTest :ad-sdk-tcl:google-ima:lintRelease --offline
./gradlew -I ad-sdk-tcl/google-ima/tools/consumer-smoke.init.gradle :google-ima-consumer-smoke:assembleRelease --offline
```

独立宿主用例只依赖本模块，通过 Java 公开 API 构建 R8 Release APK；不会请求商业广告。纯 IMA 运行核心、后台 patch 并发/取消、音量与授权解析、逐广告预算、终态结算、完整审计和五份受控 AAR 的桥接签名均有 JVM 回归。

宿主仍负责 CMP/UMP、业务授权、调度及上报 HTTP/重试。本模块的 onAdReport/onAdAudit 已输出对应事件和完整审计，但只有宿主接入传输后才会发送这些报告。检查结果与实机联调范围见 [迁移检查记录](../../docs/reports/2026-11-06-tcl-google-ima-migration-check.md)。

## 真机验证

最低版本已统一为 API 24。两台 API 29 / WebView 74 实体设备最终受控场景 30/30 通过（472 个断言），包括声音矩阵、独立 OMID / ActiveView、210 秒长视频、取消/替换、WebView 回退及 R8 Release 点击确认。真实默认后台 patch 和 Google VAST 获取通过，但 Google 官方测试素材跳转到 asset.gvt1-cn.com 后返回 HTTP 404，真实远端视频完整播放仍未通过。

测试宿主、复测命令、逐项证据和设备恢复见 [minSdk 24 真机验证报告](../../docs/reports/2026-11-06-pure-ima-min24-device-validation.md)。

### 音量闭环验收（2026-11-06）

> 本节是历史试验记录，不代表当前源码或本次 RDID 修复的验收结论。当前审计 reporter 不包含下述 `volume_chain_audit`，当前 Media3 补丁也不强制关闭预加载；不能据下面的历史描述认定这些能力已在当前交付启用。音量策略撤回和恢复记录见 `outputs/ima-volume-scope-recovery-20261106/STATUS.md`，历史实测报告顶部也有范围纠正。本次修复没有修改音量策略，亦未重新执行音量真机矩阵。

- 实际播放器音量决定初始请求的 `vpmute` / `adWillPlayMuted`；系统音量独立测量，不跟随播放器静音伪装为 0。
- 固定版本 Media3 适配器关闭媒体预加载，连续广告串行加载，避免下一段尚未初始化的播放器状态进入当前段的 ActiveView 测量。代价是切段需要等待真实素材加载；不改写测量消息或 URL。
- 每段广告和实际音量变化都通过真实 `onVolumeChanged` 回调传递；OM start/volumeChange 只读观察。
- `volume_chain_audit` 分别比较最后实际快照与 IMA 输入、回调值、OM 媒体值；任一已观测矛盾输出 `observed_mismatch`，缺值不当成功。历史变化查各自时间线，不把末值一致当整段时间线一致。
- 客户端 `complete_to_omid_service` 不是独立验证端确认。验收脚本单独核对本地验证脚本的实际收件。
- 真机矩阵入口为 `ad-sdk-google-ima/src/test/device/run-volume-matrix.py`；最终证据与命令见 `docs/reports/2026-11-06-google-ima-real-volume-closure.md`。

本轮验收仍保留一项实际失败：有声连续三广告的 ActiveView 首条音量与系统比例不一致，Debug 与 R8 均复现；详见上述实测报告，不能据此宣称全部通过。
