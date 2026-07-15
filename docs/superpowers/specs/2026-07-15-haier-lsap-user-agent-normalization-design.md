# 海尔 LSAP User-Agent 规范化设计

日期：2026-07-15

## 目标

在 `haier_lsap` 渠道中修正异常的 Android Dalvik User-Agent，同时保证正常设备的原始 UA 不发生任何变化。

一旦判定原始 UA 异常，就不能继续信任该 ROM 提供的 `Build.VERSION.RELEASE`、`Build.MODEL` 或 `Build.ID`。因此，异常修复分支必须使用应用侧维护的可信 SDK 配置，而不是继续使用 ROM 字段重新拼接 UA。

## 范围

- 仅对准确的 `haier_lsap` flavor 启用。
- 覆盖应用支持的 API 23 至 API 36。
- 在任何广告 SDK 或网络组件缓存 UA 之前，规范化 `System.getProperty("http.agent")`。
- 正常原始 UA 必须逐字符原样保留。
- 异常原始 UA 根据 `Build.VERSION.SDK_INT` 替换为确定的海尔电视标准 UA。
- 当前连接的异常测试设备不能作为固定模板来源。
- 不影响其他产品渠道。

`make`、`model`、`osv` 等独立请求字段不属于本次实现范围。如果服务端会同时校验这些字段和 UA 的一致性，需要另行评估和修正。

## 最终行为

异常 UA 修正后的固定格式为：

```text
Dalvik/2.1.0 (Linux; U; Android <官方版本>; Haier TV Build/<标准Build ID>)
```

Android 版本和 Build ID 必须从应用侧 API Level 映射表中成对选择，避免再次出现 Android 11 搭配 Android 10 `QP1A` Build ID 的矛盾组合。

处理流程必须满足幂等性：

1. 读取当前 Java 系统属性 `http.agent`。
2. 解析 UA，并根据当前 API Level 校验其合法性和一致性。
3. 校验通过时，原样返回并保留原 UA。
4. 校验失败时，查找当前 API Level 对应的标准配置。
5. 生成并安装修正后的 UA。
6. 如果不存在当前 SDK 的配置，则保留原值并记录“不支持的 SDK”诊断，不允许猜测或临时拼接。

## API Level 标准配置

配置表覆盖应用支持的全部运行版本。

| SDK | Android 对外版本 | 标准 Build ID |
|---:|---|---|
| 23 | 6.0 | `MRA58K` |
| 24 | 7.0 | `NRD90M` |
| 25 | 7.1 | `NDE63H` |
| 26 | 8.0 | `OPR6.170623.010` |
| 27 | 8.1 | `OPM1.171019.011` |
| 28 | 9 | `PPR1.180610.009` |
| 29 | 10 | `QP1A.190711.019` |
| 30 | 11 | `RP1A.200720.009` |
| 31 | 12 | `SP1A.210812.015` |
| 32 | 12L | `SP2A.220305.012` |
| 33 | 13 | `TP1A.220624.014` |
| 34 | 14 | `UP1A.231005.007` |
| 35 | 15 | `AP3A.240905.015.A2` |
| 36 | 16 | `BP2A.250605.031.A2` |

这些值是应用侧用于异常数据修正的标准配置，并不代表异常 ROM 原本的真实厂商固件身份。

## UA 校验规则

### 基础格式

满足以下任意条件时，UA 判定为异常：

- UA 为空。
- UA 包含控制字符、回车或换行。
- 无法解析为包含 Android 版本、型号和 Build ID 的 Dalvik Android UA。
- 任意必需字段解析后为空。

### Android 版本

UA 中解析出的 Android 版本必须与当前 `SDK_INT` 兼容。

| SDK | 允许视为正常的版本值 |
|---:|---|
| 23 | `6.0`、`6.0.1` |
| 24 | `7.0` |
| 25 | `7.1`、`7.1.1`、`7.1.2` |
| 26 | `8.0`、`8.0.0` |
| 27 | `8.1`、`8.1.0` |
| 28 | `9` |
| 29 | `10` |
| 30 | `11` |
| 31 | `12` |
| 32 | `12`、`12L` |
| 33 | `13` |
| 34 | `14` |
| 35 | `15` |
| 36 | `16` |

不能使用“版本中只要有小数点就判异常”的全局规则。Android 6.0、7.1 和 8.1 本身就是官方版本。但对于 API 30，只允许 Android 11，因此 `11.1` 必须判定为异常。

### 设备型号

当 UA 中的型号属于通用占位符、芯片平台名或伪造设备身份，而不是电视品牌或正式型号时，判定为异常。比较时忽略大小写并去除首尾空格。

初始异常值列表：

```text
TV BOX
Android TV
Android TV Box
AOSP
generic
unknown
mstar
walley
walleye
```

该列表必须保持明确且可测试。后续客户确认新的异常值时，可以直接扩展列表，不需要修改 UA 解析器。

### Build ID

Build ID 校验需要保持保守，避免误判正常的 OEM 自定义 Build ID。

- Build ID 为空或包含不安全字符时判定为异常。
- 如果 Build ID 符合可识别的 Android/AOSP 版本族，其版本族必须与当前 API Level 兼容。
- 可识别的冲突组合必须判定为异常，例如 API 30 或更高版本搭配 `QP1A.191105.004`。
- 对于无法识别、但格式安全的 OEM Build ID，不能仅因为前缀未知就判定异常。

当前识别的版本族：

| Build ID 版本族 | 兼容 SDK |
|---|---:|
| `M...` | 23 |
| `N...` | 24-25 |
| `O...` | 26-27 |
| `P...` | 28 |
| `Q...` | 29 |
| `R...` | 30 |
| `S...` | 31-32 |
| `T...` | 33 |
| `U...` | 34 |
| `AP3A...` | 35 |
| `BP2A...` | 36 |

## 代码组件设计

### `HaierUserAgentNormalizer`

纯 Kotlin 组件，职责包括：

- 解析 UA；
- 根据传入的 SDK 整数判断 UA 是否正常；
- 选择对应的标准配置；
- 返回规范化结果，包括原始 UA、生效 UA、是否发生修改以及原因码。

该组件不能直接依赖 Android Framework，以便在普通 JVM 单元测试中完整验证。

### `HaierUserAgentInstaller`

Android 环境组件，职责包括：

- 读取 `Build.VERSION.SDK_INT`；
- 读取 `System.getProperty("http.agent")`；
- 调用规范化组件；
- 仅在 UA 实际变化时调用 `System.setProperty("http.agent", effectiveUa)`；
- 输出简洁诊断日志。

### Application 接入

在 `APP.attachBaseContext()` 中、`super.attachBaseContext(base)` 之前完成安装，确保早于所有 SDK 初始化逻辑。

启用条件必须判断准确 flavor，不能直接使用较宽泛的 `BuildFlavor.isHaierLsap()`，因为该方法还包含其他 LSAP 系列产品渠道。

接入形式：

```kotlin
override fun attachBaseContext(base: Context) {
    if (BuildConfig.FLAVOR == "haier_lsap") {
        HaierUserAgentInstaller.install()
    }
    super.attachBaseContext(base)
}
```

Java 系统属性只在当前进程中生效。如果未来 `haier_lsap` 在其他进程中加载广告 SDK，则对应进程也必须执行相同安装逻辑。

## 诊断日志

规范化结果原因码：

- `UNCHANGED_VALID`：原 UA 正常，未修改。
- `REPLACED_BLANK`：原 UA 为空，已替换。
- `REPLACED_UNSAFE_CHARACTERS`：包含不安全字符，已替换。
- `REPLACED_UNPARSEABLE`：无法解析，已替换。
- `REPLACED_VERSION_MISMATCH`：Android 版本与 SDK 不一致，已替换。
- `REPLACED_GENERIC_MODEL`：型号属于通用或平台值，已替换。
- `REPLACED_BUILD_MISMATCH`：Build ID 与 SDK 不一致，已替换。
- `UNCHANGED_UNSUPPORTED_SDK`：不存在 SDK 配置，保留原值。

正式版本日志只记录 SDK、是否修改和原因码。完整的原始 UA 与修正 UA 只允许在 Debug 日志中输出。

## 测试设计

JVM 单元测试必须覆盖：

- API 23 至 API 36 的正常 UA 均逐字符原样保留。
- Android 6.0、7.1、8.1 等官方小数版本不会被误判。
- API 30 搭配 Android 11 时保持不变。
- API 30 搭配 Android 11.1 时执行替换。
- API 30 搭配可识别的 `QP1A` Build ID 时执行替换。
- `TV BOX`、`mstar` 等通用或平台型号执行替换。
- 无法解析或包含控制字符的 UA 执行替换。
- 其他字段正常时，安全但未知的 OEM Build ID 可以保留。
- API 23 至 API 36 的所有异常输入都能生成对应的标准 UA。
- 对规范化结果再次执行规范化时不会产生二次变化。
- 不支持的 SDK 保留原值，不进行猜测。
- 安装逻辑只对准确的 `haier_lsap` flavor 生效。

## 验收标准

- 正常 UA 永远不被修改。
- 已知异常 ROM UA 在广告 SDK 读取 `http.agent` 之前完成替换。
- 修正后的 Android 版本与 Build ID 必须相互兼容。
- 修正后的型号固定使用 `Haier TV`，不能继续使用通用平台值。
- API 23 至 API 36 的行为确定且有完整测试覆盖。
- 不修改或提交用户现有的 `app/build.gradle` 变更。

## 参考资料

- Android 官方 Build 编号：<https://source.android.com/docs/setup/reference/build-numbers>
- AOSP 默认 Dalvik UA 生成逻辑：<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/com/android/internal/os/RuntimeInit.java#282>
