# Dynamic Channel Resolution Design

## Goal

将广告链路中对渠道号的读取统一收口，优先读取系统属性 `persist.vendor.ad.channel`，当系统属性为空时回退到 `BuildConfig.CHANNEL`，避免为每个客户持续新增独立 flavor 才能区分渠道。

## Confirmed Requirements

- 宿主会在安装 APK 前写入系统属性 `persist.vendor.ad.channel`
- App 内所有传渠道号的地方都应优先使用系统属性值
- 如果系统属性没有值，则继续使用 gradle 中配置的 `BuildConfig.CHANNEL`
- 需要把“获取渠道”的逻辑抽成公共方法，避免业务代码分散读取
- 暂不引入 token，也不修改 CMP 是否跳过的后端控制方案

## Current Project Context

- 当前多个广告链路直接读取 `BuildConfig.CHANNEL`
- `hq008` 家族的 flow-control、CMP、authorize、consent-log、ad-report、调度和防重入逻辑都把渠道号作为关键参数
- `APP` 初始化时也会把 `BuildConfig.CHANNEL` 传给 `AppManager`
- 当前工作区存在其他未提交改动，因此本次实现应聚焦在动态渠道解析本身

## Chosen Approach

新增一个公共渠道解析器 `AdChannelResolver`：

- 统一提供 `currentChannel()` 给业务侧使用
- 内部按 `system property -> BuildConfig fallback` 的顺序解析
- 对系统属性做 `trim` 和空值处理
- 同时暴露一个轻量的来源描述，便于日志中观察当前渠道来自系统属性还是默认构建值

## Design

### 1. Channel Resolver

新增 `app/src/main/java/com/smart/android/ad_app/AdChannelResolver.kt`：

- 常量属性名：`persist.vendor.ad.channel`
- 对外方法：
  - `currentChannel(): String`
  - `currentChannelSource(): String`
  - `resolve(): ResolvedChannel`
- `ResolvedChannel` 包含：
  - `value`
  - `source`（`system_property` / `build_config`）

读取策略：

1. 先尝试通过反射读取 `android.os.SystemProperties`
2. 反射失败时回退到执行 `/system/bin/getprop`
3. 读取结果做 `trim`
4. 非空则作为最终渠道
5. 否则回退到 `BuildConfig.CHANNEL`

### 2. Replace Direct Channel Reads

以下核心链路改为统一使用 `AdChannelResolver`：

- `APP` 初始化 `AppManager.channel`
- `AdConfigManager`
- `Hq008SdkAuthorizeClient` 的调用入口参数
- `Hq008CmpDecisionClient`
- `Hq008AdReporter`
- `Hq008ConsentLogReporter`
- `Hq008LocalSchedulePolicy`
- `Hq008FloatingFlowGuard`
- `hq006` RTB 竞价请求
- 调试展示页面中的渠道摘要

### 3. Logging

在关键日志中补充实际渠道与来源，方便确认宿主是否已写入系统属性：

- `APP` 初始化日志
- `AdConfigManager` 广告配置请求日志

日志描述保持中文，字段值可以是英文。

### 4. Regression Coverage

增加/更新契约测试，确保：

- 存在统一的渠道解析器
- 解析器使用 `persist.vendor.ad.channel`
- 解析器会在空值时回退到 `BuildConfig.CHANNEL`
- 关键链路不再直接把 `BuildConfig.CHANNEL` 作为请求参数

## Out of Scope

- 本次不新增新的通用 flavor、包名、签名配置
- 本次不修改后端接口协议
- 本次不处理 `tcl_poly` 自己的独立广告 SDK 接入逻辑

## Verification Plan

- 运行动态渠道解析相关契约测试
- 搜索生产代码中剩余的 `BuildConfig.CHANNEL` 直接使用点
- 如测试通过且关键链路已收口，则认为本次实现完成

## Risks and Notes

- 如果设备 ROM 对反射读取系统属性有限制，将走 `getprop` 回退路径
- 如果系统属性在进程运行期间被外部改写，业务会按下一次读取结果生效
- 由于当前工作区存在其他改动，本次不做无关清理和重构
