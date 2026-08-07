# HQ008 Flow SDK 最简接入

## 1. 引入 AAR

将 AAR 放到客户项目 `app/libs/`，并在应用模块添加依赖。文件名按实际 AAR 文件名保持一致：

```gradle
dependencies {
    implementation files('libs/hq008-flow-sdk-v2.1.2-20260806_1731-release.aar')
    implementation 'com.squareup.okhttp3:okhttp:4.9.3'
    implementation 'com.google.code.gson:gson:2.8.9'
}
```

## 2. Application 初始化

在 `Application.onCreate()` 里先初始化，不立即启动：

```java
Hq008FlowSdk.initialize(this, "ADHQ1001");
```

如果是屏保、页面可见性这类场景，再在可展示时调用 `Hq008FlowSdk.start()`，退出时调用 `Hq008FlowSdk.stop()` 和 `Hq008FlowSdk.clearAdCallback()`。

开启系统属性 `persist.sys.ad.log=true` 后，SDK 会按自然日打印本地触发和上报统计，方便调试对账。

每轮广告流程结束时，SDK 会额外调用现有 `/api/v2/ad/report` 上报一次当天汇总；汇总放在 `message` 字段中，不使用 `diagnostic_info`。自然日固定按北京时间（`Asia/Shanghai`）计算，数据使用 `SharedPreferences` 持久化。示例：

```json
{
  "day": "20260806",
  "timezone": "Asia/Shanghai",
  "screensaver_start_total": 12,
  "screensaver_stop_total": 10,
  "authorized_callback_total": 3864,
  "current_final_status": "FAILED",
  "current_final_message": "code=1001,message=TCL_AD_ERROR_1001",
  "final_status_totals": [
    {
      "status": "COMPLETED",
      "message": "COMPLETED",
      "total": 3012
    },
    {
      "status": "FAILED",
      "message": "code=1001,message=TCL_AD_ERROR_1001",
      "total": 32
    },
    {
      "status": "TIMEOUT",
      "message": "TIMEOUT",
      "total": 128
    }
  ]
}
```

其中 `screensaver_start_total`、`screensaver_stop_total`、`authorized_callback_total` 是当天累计次数；`final_status_totals` 按每个终态的 `status + message` 分开累计，最后一次终态由 `current_final_status` 和 `current_final_message` 表示。一次流程只产生一次终态统计上报，重复回调不会重复计数。

## 3. 注册广告回调

在真正能展示广告的组件中注册回调：

```java
private Hq008AdSession currentAdSession;

private final Hq008AdCallback hq008AdCallback = new Hq008AdCallback() {
    @Override
    public void onAdAuthorized(Hq008AdSession session) {
        currentAdSession = session;
        requestScreensaverAd();
    }
};

@Override
public void onDreamingStarted() {
    super.onDreamingStarted();
    Hq008FlowSdk.setAdCallback(hq008AdCallback);
    Hq008FlowSdk.start();
}

@Override
public void onDreamingStopped() {
    finishCurrentSessionAsFailed("SCREENSAVER_STOPPED");
    Hq008FlowSdk.stop();
    Hq008FlowSdk.clearAdCallback();
    super.onDreamingStopped();
}
```

## 4. 回传广告状态

客户原有广告 SDK 回调中转发状态：

```java
private void onAdLoaded() {
    if (currentAdSession != null) {
        currentAdSession.loaded();
    }
}

private void onAdStartPlay() {
    if (currentAdSession != null) {
        currentAdSession.started();
    }
}

private void onAdFinished() {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;
    if (session != null) {
        session.completed();
    }
}

private void onAdError(int errorCode, String errorMessage) {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;
    if (session != null) {
        session.failed(errorCode, errorMessage);
    }
}

private void finishCurrentSessionAsFailed(String reason) {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;
    if (session != null) {
        session.failed(reason);
    }
}
```
