# Google 纯 IMA 后台音量上报开关：实机验证

日期：2026-11-03，Asia/Shanghai。

本轮实机确认了两个真实遗漏并修复：一是仅修改 OM 设备音量时，媒体音量仍为 1；二是 ActiveView 的早期元数据采样仍将 1 计入最大音量。最终版本在原有消息和读取入口映射音量值，四种实际声音/上报声音组合均完成正常播放、独立 OM 验证和 ActiveView HTTP 报文验证。

## 环境与测试方法

- 目标设备 `192.168.0.124:5555`，Dreamlink/mstar，系统 API 29，WebView `74.0.3729.186`。系统 release 属性为 15，与 API 不一致，因此不据该属性推断实际 Android 大版本。
- 应用 `com.ambientlight.tv`，1.0.6/code 6；纯 IMA `3.35.1`、Media3 `1.8.0`、运行时 Core `3.539.0`。
- 复用 11 月 2 日的[Core 测量记录](2026-11-02-google-ima-core-measurement-device124.md)、[patch 接入记录](2026-11-02-google-ima-patch-integration-device124.md)及其 IAB OM 验证脚本；脚本仅替换日志回传地址。
- 临时包将后台指向 ADB reverse 的本地接口。本地 flow-control/authorize 放行、跳过 CMP，频控返回 3600 秒，并通过原有 Debug Provider 显式触发；未永久放开生产授权或频控。
- 自建本地 VAST、AAC/H.264 素材及像素跟踪。8 秒素材的部分验证脚本加载过晚，重试改用 20 秒素材；未修改产品播放时序或人为补事件。
- ActiveView 补充用例复用既有官方样本的 `activeview` Extension 结构/flags，只把跟踪接收地址改成本地服务，观察 Core 正常生成的测量请求。
- 系统媒体音量临时设为 4/15，使实际播放器 0/1 两种状态具有区别；结束后恢复 0/15。

实际执行的 Core 源码 SHA-256 一直为 `1c4b95dae73dc379b8fdae008d803eae21d32fdbd3d342e6f220c513e9d7c751`。没有替换或改写下载的 Core 文件。

## 最终结果与覆盖

| 位置 | reported_sound_mode=0 | reported_sound_mode=1/缺失/异常 | 实机依据 |
|---|---|---|---|
| 后台 patch 请求 | 整数 0 | 整数 1 | 本地服务记录实际请求体 |
| 最终广告请求 URL | vpmute=1 | vpmute=0 | 本地 VAST 收到的 HTTP 参数 |
| AdsRequest.adWillPlayMuted | MUTED | UNMUTED | 原请求入口审计及底层 AAR 分支 |
| ActiveView nativeVolume | 0 | 1 | 原生桥接实际调用审计 |
| ActiveView 元数据 volume | 0 | 1 | 原有 getVideoMetadata 读取审计 |
| ActiveView HTTP 的 a/mv/nv | 0/0/0 | 1/1/1 | 本地接收的实际 acvw 测量字符串 |
| OM deviceVolume/mediaPlayerVolume/videoPlayerVolume | 0/0/0 | 1/1/1 | 独立 IAB 验证脚本收到的正常 start 事件 |
| 实际 ExoPlayer 音量 | 由 sound_mode 决定 | 由 sound_mode 决定 | 四种交叉组合中的实际播放器读数 |

最终完整测量回归的四组均在同一应用进程连续执行：`上报0/实际0 → 上报1/实际0 → 上报0/实际1 → 上报1/实际1`。每组均收到最终 `AD_PHASE_COMPLETED`；独立验证均收到 sessionStart、impression、loaded、start、三个四分位、complete、sessionFinish，原生 OM startSession/finishSession 各一次。非静音组产生正常可听半程跟踪，静音组没有该跟踪。

ActiveView 的 `mut` 在该 Core 中取自 `aD.bb` 的时长累计器，不是静音布尔值；本轮始终 100% 可见，因此其值为 0。没有将该字段强行改成静音 0/1。依据留存 Core：`aD.update`、`YB.update`、`RD`，见 `core-3.539.0.pretty.js` 12768、12136、13178 行。

## 配置矩阵

12 组配置语义全部通过。四个交叉组合使用最终包完整重跑；其余八组的字段解析、兜底、嵌套优先级及 OM 验证来自同轮前一阶段包，该阶段与最终包的字段解析代码相同，最终补充只涉及 ActiveView 读取入口及其安装时机。

| 下发 reported_sound_mode | sound_mode | 期望上报音量 | 结果 | 原始目录 |
|---|---:|---:|---|---|
| 1 | 0 | 1 | 通过 | `final3-report1-physical0` |
| 0 | 0 | 0 | 通过 | `final3-report0-physical0` |
| 0 | 1 | 0 | 通过 | `final3-report0-physical1` |
| 1 | 1 | 1 | 通过 | `final3-report1-physical1` |
| 缺失 | 0 | 1 | 通过 | `verified-missing-physical0` |
| null | 0 | 1 | 通过 | `verified-null-physical0-long` |
| 2 | 0 | 1 | 通过 | `verified-invalid-number-physical0` |
| {"invalid": true} | 0 | 1 | 通过 | `verified-invalid-object-physical0` |
| gam_config=0，顶层=1 | 0 | 0 | 通过 | `verified-nested0-top1-physical0` |
| "0" | 1 | 0 | 通过 | `verified-string0-physical1` |
| "1" | 0 | 1 | 通过 | `verified-string1-physical0` |
| false | 0 | 1 | 通过 | `verified-invalid-boolean-physical0` |

## 本轮具体修复与协议边界

1. **OM 媒体音量**：复现修复前 `mediaPlayerVolume/deviceVolume/videoPlayerVolume=1/0/1`。在 OM 服务接收原有 `SessionService.start` 时映射第二个已有参数，在原有 `volumeChange` 时映射第一个已有参数。只处理已经存在且在 0–1 范围内的有限数值；缺失或非法参数原样通过，消息 GUID、版本、时长、会话和发送者保留。
2. **ActiveView 早期最大音量**：补充 HTTP 取证发现仅映射 nativeVolume 时，当前/最小音量为 0，最大音量 mv 仍为 1。先单独包装 common 入口未实际命中；加入实际使用的 bridge 入口后有调用，但在 setDeviceVolume 时安装仍晚于第一份采样。最终在既有 WebView document-start 或页面开始/结束回退入口提前安装，只映射 `ima.bridge.getVideoMetadata` / `ima.common.getVideoMetadata` 返回中已有有效 volume，其余元数据原样保留；HTTP a/mv/nv 实测全部为 0。
3. 没有修改实际播放器 setVolume：它继续只读取 soundEnabled，即授权 sound_mode。请求级 reportedSoundEnabled 独立保存，不复用上一轮配置。
4. 没有额外触发 getVolume、onVolumeChanged、OM start/volumeChange，也没有向 native 客户端 start 载荷补字段。受控 IMA 字节码中 `zzg.onAdProgress` 仍为 `start → adMediaInfo → aconst_null → zzd`。
5. Core 虚拟播放器内部状态没有被本轮强行更新。被控制的是上述实际声明、读取返回和测量消息；不能将输出值一致解释为内部状态也被改写。VAST mute/unmute 跟踪仍按 IMA 正常触发条件发送。

保留旧入口的覆盖也已逐项核对：Media3 `VideoAdPlayerImpl.getVolume` 一个百分比桥接调用、客户端 `zzg.onVolumeChanged` 一个、流播放器 `zzcb` 两个（起播和变化）。它们原来就存在，现在读取本次字段返回 0/100；**本轮客户端 VAST 没有实际触发前三类百分比入口，zzcb 属于 DAI/流播放，因此不记作实机命中通过，也未强行触发。** 字节码证据保留在输出目录。

independent 生成器的 adWillPlayMuted 属于自拼接方案，仍按新字段处理，编译验证通过；它没有参与本轮纯 IMA 实机矩阵。本轮没有把两种运行时混为一个流程。

生产测量脚本的 60 条离线断言通过，验证：安装不发事件、每个输入只转发一次、正常 start/volumeChange 仅替换已有音量、保留其他协议参数、缺失/非法数据不补写、其他消息直通、元数据读数不改写 Core 原对象、不调用实际播放器音量设置。

## 失败与重试保留

- 早期 8 秒矩阵保留 5 组验证不完整记录（4 组没有验证脚本日志，1 组缺 complete），均未计为通过；20 秒素材重试取得完整验证日志。
- 一次触发在前一广告流程尚未退出时到达，被原有流程保护跳过，单独记录为 excluded_overlap，随后独立重跑。
- ActiveView 的 mv 遗漏、common 入口未命中、安装时机过晚的失败样本均保留。没有删除失败记录或放宽最终断言；最终四组严格要求元数据调用命中及 HTTP a/mv/nv 全部正确。
- 构建曾误用 Android Studio 的 JDK 25 导致 Gradle Groovy 不兼容，改用已有 Corretto 17 后通过；未为此修改项目构建规则。

## 构建、恢复与证据

最终纯 IMA 正常后台 Debug 完整组装成功；independent Kotlin 编译成功。最终测试包仅有本地接口地址这一项测试配置差异，所有临时源码已恢复。设备原 APK 已按 SHA-256 核对恢复，9 个 shared_prefs 文件逐字节恢复，媒体音量、代理和样本属性恢复；本地服务以及本轮 ADB reverse/forward 已撤销。

- [最终结构化验证](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/final-verification.json)
- [全部尝试（包含失败）](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/verification.json)
- [本地接口、跟踪与独立 OM 日志](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/server-events.jsonl)
- [生产脚本协议断言](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/omid-policy-tests.json)
- [原生起播字节码](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/zzg-bytecode.txt)
- [正常后台构建日志](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/build-normal-restored-final3.log)
- [independent 编译日志](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/build-independent-compile-jdk17.log)
- [设备恢复记录](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/restoration.json)
- [偏好文件恢复核对](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/prefs-restoration.json)
- [正常后台 Debug APK](/Users/zy/Documents/Codex/ad-app/outputs/ima-volume-device-20261103/normal-backend-validated.apk)

正常后台 APK SHA-256：`0bfc58bf09cfd4fe74c1975f7de9a5ee45946068ef2ef41ee01900b0bbfcc2aa`。
测试包 SHA-256：`506c87c53c701c2a441887c28a6e48abcc1f3b63c46e411c22507c144da6c678`。

结论范围：上述设备、SDK/Core 版本和本地受控 VAST 已验证；未把未运行的 DAI、independent 实机流程或其他 Core 版本宣称为已验证。
