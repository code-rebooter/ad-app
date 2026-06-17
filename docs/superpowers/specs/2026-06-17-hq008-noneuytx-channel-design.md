# hq008 Noneu YTX Channel Design

## Goal

在现有 `hq008Noneu` 非欧美正式包基础上新增一个独立渠道 `hq008Noneuytx`，用于产出新的正式 APK。新渠道复用 `hq008 noneu` 现有运行链路和正式包能力，只调整渠道标识参数与版本起点。

## Confirmed Requirements

- 新增 flavor 名称：`hq008Noneuytx`
- 渠道参数：
  - `channel = "TCL_NONEEU_YTX"`
  - `cType = "TCL_NONEEU_YTX"`
  - `model = "TCL_NONEEU_YTX"`
- 版本起点：
  - `versionCode = 1`
  - `versionName = "1.0.1"`
- 这是“基于非欧美渠道再新增一个渠道”，因此不替换现有 `hq008Noneu`
- 需要能打正式包

## Current Project Context

当前项目已经存在多条 `hq008` 系列 flavor：

- `hq008`：欧美正式渠道
- `hq008Noneu`：非欧美正式渠道
- `hq008Noneuc2`：基于 noneu 派生的额外渠道
- `tcl_poly`：同样复用 noneu 家族行为的额外渠道

其中：

- `app/build.gradle` 负责 `sourceSets`、`productFlavors`、签名、版本、BuildConfig 字段和正式包命名
- `BuildFlavor.isHq008Noneu()` 与 `BuildFlavor.isHq008Family()` 决定运行时是否走 `hq008 noneu` / `hq008 family` 共享逻辑
- 现有测试已经用 flavor contract test 锁定 `hq008Noneuc2` 与 `tcl_poly` 的家族归属和关键配置

## Chosen Approach

新增独立 flavor `hq008Noneuytx`，并沿用 `hq008Noneu` 的正式包基础配置。

原因：

- 不影响现有 `hq008Noneu` 渠道发包
- 与 `hq008Noneuc2`、`tcl_poly` 的扩展模式一致
- 改动集中、回归面小
- 能确保新包继续走当前 `hq008 noneu` 的正式链路逻辑

## Design

### 1. Gradle flavor and source set

在 `app/build.gradle` 中新增：

- `sourceSets.hq008Noneuytx`
  - `java.srcDirs = ['src/hq008/java']`
  - `res.srcDirs = ['src/hq008/res']`
- `productFlavors.hq008Noneuytx`

该 flavor 复用 `hq008Noneu` 的以下正式配置：

- `applicationId = "com.google.android.adnoneu"`
- `signingConfig = signingConfigs.tclDemoNoneuRelease`
- `baseUrl = "https://api.bcytua.cc/"`
- `backupDomain = "https://api.bcytua.cc/"`
- `manifestPlaceholders`
  - `share_uid = ""`
  - `tcl_app_key` 复用现有 noneu 值
  - `partner_name` 继续读取 `TCL_PARTNER_NAME`
  - `project_id = "190"`
- `CMP_DEVICE_ID_OVERRIDE = ""`

该 flavor 只改动以下业务标识与版本：

- `versionCode = 1`
- `versionName = "1.0.1"`
- `channel = "TCL_NONEEU_YTX"`
- `cType = "TCL_NONEEU_YTX"`
- `model = "TCL_NONEEU_YTX"`

### 2. Runtime flavor classification

在 `BuildFlavor.isHq008Noneu()` 中加入 `hq008Noneuytx`。

目的：

- 让新渠道继续走 `hq008 noneu` 分支
- 复用现有 `hq008 family` 的浮窗广告、调度、隐藏模式和启动初始化逻辑
- 保持与 `hq008Noneuc2`、`tcl_poly` 的归类方式一致

如果不加入该判断，新 flavor 虽然复用了 `src/hq008` 代码，但运行时会错过一部分 `hq008 noneu` / `hq008 family` 专用逻辑。

### 3. Regression coverage

新增一条 flavor contract test，风格与 `Hq008Noneuc2FlavorContractTest` 保持一致，覆盖：

- `BuildFlavor.isHq008Noneu("hq008Noneuytx") == true`
- `BuildFlavor.isHq008Family("hq008Noneuytx") == true`
- `app/build.gradle` 中存在 `hq008Noneuytx {`
- flavor 配置中包含：
  - `channel : "TCL_NONEEU_YTX"`
  - `cType : "TCL_NONEEU_YTX"`
  - `model : "TCL_NONEEU_YTX"`

## Out of Scope

- 不修改现有 `hq008Noneu`、`hq008Noneuc2`、`tcl_poly` 的既有配置
- 不调整 `hq008` 主链路代码行为
- 不新增新的 SDK、接口域名、签名或资源目录
- 不在本次设计中变更 `applicationId` 命名策略，除非实现阶段发现正式打包存在明确冲突

## Verification Plan

实现完成后至少验证：

1. 目标 flavor 契约测试通过
2. `BuildFlavor` 归类测试通过
3. 能执行对应正式包 assemble 任务并产出 `hq008NoneuytxRelease` APK

建议验证命令以实现时实际可用任务名为准，至少覆盖：

- `./gradlew test... --tests com.smart.android.ad_app.Hq008NoneuytxFlavorContractTest`
- `./gradlew assembleHq008NoneuytxRelease`

## Risks and Notes

- 新 flavor 复用 `hq008Noneu` 的 `applicationId`，因此它与现有 noneu 包更像是“同包名不同渠道参数”的正式发包分支。这符合“基于非欧美渠道新增渠道”的当前要求，但若未来需要与原 noneu 包并存安装，则需要单独调整 `applicationId`
- 项目当前工作区已有其他进行中的改动，实现时应只触碰本次新增 flavor 所需文件，避免干扰现有工作
