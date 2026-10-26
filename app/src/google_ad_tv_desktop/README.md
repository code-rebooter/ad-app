# Google 渠道共享实现

2026-10-26 起，当前应用的全部 Google flavor 共用一套独立 URL 生成、后台 patch 和 TCL 执行流程：

`业务开关 / CMP → sdk/authorize.data.gam_config → independent 本地生成完整 URL → 公共参数修正及空 rdid 兜底 → google-gam/patch → 后台 URL 原样交 TCL → 请求、解析、播放和监测`

## 目录与构建

- 本目录 `java/`：共享 Google 业务入口、统一 URL 生成接口、公共修正、后台 patch、TCL 状态/监测桥接及原有 UMP/CMP 实现。
- `src/googleUrlIndependent/java`：当前唯一参与编译的 URL 生成器和 `GoogleUrlEngine` 实现。
- IMA 反编译源码、动态 VM/Core 对照代码和分析输出不存放在本仓库内，统一保存在仓库外研究工作区。
- 本目录 Manifest、res、proguard-rules.pro：所有 Google flavor 共用。
- `app/gradle/google-url-tcl.gradle`：生成排除旧广告入口的 HQ008 业务源码，并为所有 Google flavor 注册相同 TCL 公共、监测和填充处理器。
- `app/build.gradle` 的 `googleAdFlavors`：统一声明 Google 渠道集合，全部继承 `googleAdCommonImplementation`，不再通过客户渠道相互继承依赖。

旧 IMA/Media3 广告执行器、纯 TCL 实验执行器和旧 resolve 客户端已删除；Google 宿主不再依赖 AndroidX Media3。TCL 自身原厂播放器与底层依赖仍保留。

## 渠道

以下 flavor 的包名、签名配置、版本号和渠道身份按原配置保留。`google_ad_tv_desktop_tcl_test` 仅保留历史构建名称，已改用本目录共享链路，不再代表纯 TCL 模式。

| flavor | 后台渠道 | 包名 |
| --- | --- | --- |
| `google_ad_tv_desktop` | `GOOGLE_AD_TV_DESKTOP` | `io.android.launcher.tv.desktop` |
| `google_ad_tv_desktop_jm` | `GOOGLE_AD_TV_DESKTOP_JM` | `io.android.launcher.tv.desktop` |
| `google_ad_tv_desktop_ytx` | `GOOGLE_AD_TV_DESKTOP_YTX` | `io.android.launcher.tv.desktop` |
| `google_ad_tv_desktop_007` | `GOOGLE_AD_TV_DESKTOP_007` | `io.android.launcher.tv.desktop` |
| `google_ad_tv_desktop_v260904_1` | `GOOGLE_AD_TV_DESKTOP_V260904_1` | `com.localvideoplayer.tv` |
| `google_ad_tv_desktop_v260904_x98k` | `GOOGLE_AD_TV_DESKTOP_V260904_X98K` | `com.localvideoplayer.tv` |
| `google_ad_tv_desktop_localcalendar` | `GOOGLE_AD_TV_DESKTOP_LOCALCALENDAR` | `com.localcalendar.tv` |
| `google_ad_tv_desktop_kaneshort` | `GOOGLE_AD_TV_DESKTOP_KANESHORT` | `com.adsflare.kaneshort` |
| `google_ad_tv_desktop_tpmaotai1935` | `GOOGLE_AD_TV_DESKTOP_TPMAOTAI1935` | `io.android.launcher.tv.desktop` |
| `google_ad_tv_lockscreen` | `GOOGLE_AD_TV_LOCKSCREEN` | `com.chihim.lockscreentv.play` |
| `google_ad_tv_lockscreen_hq002` | `GOOGLE_AD_TV_LOCKSCREEN_HQ002` | `com.chihim.lockscreentv.play` |
| `google_ad_tv_desktop_tcl_test` | `GOOGLE_AD_TV_DESKTOP_V260904_1` | `com.localvideoplayer.tv` |

客户别名：`flymobi` 对应 `google_ad_tv_desktop_localcalendar`（包名 `com.localcalendar.tv`）。

## 接口与日志

所有 Google 渠道的后台均须在本轮 authorize 返回可用 `gam_config`，并支持 `google-gam/patch`；无可用基础链接时跳过本轮，不回退 resolve。patch 失败或无有效 URL 时结束本轮，不绕过后台。授权绑定的 request_id/channel_id 随本轮传递。

patch 请求保留本地持久 UID `rdid`（`google_tcl_ad/aid`），并携带 mac、androidid、宿主版本及实际 TCL SDK 版本；这里的 UID 与 URL 的 GMS 广告 ID 是两个用途。公共修正及空 ID 兜底在 patch 之前，后台返回结果不再作本地 URL 修正。

真正交 TCL 的最终 URL 写入 `AD_SDK_REQUEST.adLog.final_url`，流程终态一次汇总上报；识别到 InLine 视频填充后、TCL 正式解析前仍报告原状态接口 `AD_PROGRESS / FILLED`。加载、播放和监测沿用共享 TCL 路径。

接口文档见 `docs/google_ima_tcl_authorize_api.md`、`docs/google_gam_patch_api.md`、`docs/google-ad-backend-fields-20261023.md`。

## URL 引擎

- 所有 Google flavor 固定编译 `src/googleUrlIndependent/java`，使用 `app/libs/google-url-independent/independent-google-tv-url-local-identity-20261025.aar`。
- `googleUrlEngine` Gradle 参数已经移除；传入该参数不会改变构建结果，也不存在运行时 IMA 回退。
- 活动依赖不包含 IMA AAR、PAL、tv-ads、GMS Advertising ID 或 AppSet。UMP/CMP 只保留自身需要的 Basement。
- IMA 3.35.1、PAL、tv-ads 依赖坐标和补丁任务以注释保留在 `app/build.gradle`；后续分析新版远程 Core 或动态 VM 时只能在仓库外研究工作区临时恢复，不得写入正式 source set 或渠道构建。
- Release APK 文件名固定包含 `independent`。

独立适配器在后台线程共享进程级 owner，为每轮创建新会话和本地空白 WebView。WebView 不加载远程 Core，并阻止网络资源请求；在生成前应用现有固件输入规则。广告 ID/LAT 与 AppSet 使用独立 AAR 的本地存储和固定规则生成。取消/超时只交付一次结果，后台线程关闭会话后释放 WebView，不在主线程等待生成器结束。原始生成结果仍通过同一个公共 URL 修正及空 ID 兜底，再发后台 patch；patch 返回结果不再本地修改。

构建主渠道 independent Release：

```sh
./gradlew :app:assembleGoogle_ad_tv_desktop_v260904_1Release
```

当前主渠道版本为 `1.0.12 / versionCode 13`。完整 AAR 的算法覆盖范围沿用原候选记录；构建与包内容检查不代表新增设备已通过取链、填充或播放验证。

