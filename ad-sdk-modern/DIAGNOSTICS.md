# SDK 诊断日志与原始错误

本文适用于当前源码的待发布修改，远程 `v1.0.15` 尚不包含这些功能。

## 开启日志

在设备上开启系统属性后，SDK 自有日志立即生效，无需修改客户回调：

```sh
adb shell setprop persist.sys.ad.log true
adb shell getprop persist.sys.ad.log
adb logcat -v threadtime AdSdk:V AdSdkHttp:V AdSdkPlayer:V AdSdkReport:V AdConsent:V AdConsentResolver:V AdConsentRunner:V IMASDK:V '*:S' > ad-sdk.log
```

确认 `getprop` 输出 `true`。关闭日志：

```sh
adb shell setprop persist.sys.ad.log false
```

`persist.sys.ad.log` 显式设置为 `true/false` 时优先于 `SdkConfig.Builder.setDebugLogging(...)`；属性未设置时使用该配置，默认关闭。设置属性需要设备系统允许当前 shell 写入该属性。IMA、Media3、OkHttp 自己输出的日志由各自组件控制，本开关控制本 SDK 添加的日志。

日志覆盖初始化、每次 `play`、流控和授权、UMP/CMP、广告配置、IMA 事件、加载、播放、释放、最终回调和上报。`AdSdkHttp` 记录 SDK 后台接口的请求方法、完整 URL、请求正文、HTTP 状态码、响应正文及网络异常；响应超过 1 MiB 时明确标记截断。长日志分段打印，HTTP 日志用 `httpId` 对应请求和响应，会话日志用 `session`、`requestId` 对应同一轮广告。

响应正文随业务读取一起记录，日志不会为了读取正文额外等待网络。对于业务只检查 HTTP 状态就关闭的上报响应，会标明 `body not fully consumed by caller`；授权和广告配置等实际读取的正文正常记录。

Google 的 VAST 请求由 IMA 管理；这里会记录传给 IMA 的广告地址、IMA 事件和原始错误，不承诺输出 IMA 内部每一次跳转的 HTTP 正文。

## onFinished 自动输出

开启属性后，即使客户回调只打印 `onFinished`，SDK 也会自行打印：

- `status`：`COMPLETED`、`SKIPPED`、`ERROR` 或 `CANCELLED`。
- `message`：有上游错误时使用上游原始 message；否则使用跳过原因或本地结束状态。
- `reason`：跳过原因；后台提供 `reason` 时保留原文。
- `error.source`、`error.originalCode`、`error.message`、`error.cause`、`error.responseBody`：错误来源、原始码、原文、异常及响应正文。

回调数据不受日志开关影响。`SKIPPED` 也可能带 `AdError`，例如后台拒绝授权；读取错误时应检查 `result.getError() != null`。

```java
@Override
public void onFinished(AdSession session, AdResult result) {
    String message = result.getMessage();
    AdError error = result.getError();
    if (error != null) {
        String source = error.getSource();
        String originalCode = error.getOriginalCode();
        String originalMessage = error.getMessage();
        String responseBody = error.getResponseBody();
        Throwable originalCause = error.getCause();
        // 例如 IMA 返回 303：source="IMA"，originalCode="303"，message 保持 IMA 原文。
    }
    // 按自己的业务间隔安排下一轮 AdSdk.play()。
}
```

`AdError.getCode()` 保留原有枚举返回类型以兼容现有接入，它表示 SDK 错误分类。上游原始错误码使用 `getOriginalCode()`，不会用 `AD_LOAD_ERROR` 等分类替代 303，也不会将所有错误伪装成 IMA 错误。

| 来源 | 原始码与原文 |
| --- | --- |
| `IMA` | `getErrorCodeNumber()` 和 `getMessage()`；`getCause()` 保留 IMA 原始异常 |
| `MEDIA3` | `PlaybackException.errorCode` 和原始 message、异常 |
| `UMP` | `FormError.getErrorCode()` 和 `getMessage()` |
| `HTTP` | HTTP 状态码、服务端 message 和响应正文 |
| `API` | 服务端业务码、reason/message 和响应正文 |
| `NETWORK` | 原始网络异常；没有上游错误码时 `originalCode=null` |
| `SDK` / `JSON` | 本地校验、超时或解析错误；不会编造上游数字码 |

后台未提供原因时保留本地诊断原因，如 `FLOW_CONTROL_DISABLED`、`AUTHORIZE_DENIED` 或 `NO_AD_TAG`，同时记录完整响应；网络/HTTP/解析异常不再只返回 `AUTHORIZE_FAIL` 或 `FLOW_CONTROL_FAIL`。
