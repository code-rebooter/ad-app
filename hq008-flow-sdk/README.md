# HQ008 Flow SDK 最简接入

## 1. 引入 AAR

将 AAR 放到客户项目 `app/libs/`，并在应用模块添加依赖。文件名按实际 AAR 文件名保持一致：

```gradle
dependencies {
    implementation files('libs/hq008-flow-sdk-v2.0.8-20260804_1546-release.aar')
    implementation 'com.squareup.okhttp3:okhttp:4.9.3'
    implementation 'com.google.code.gson:gson:2.8.9'
}
```

## 2. Application 初始化

在 `Application.onCreate()` 初始化一次：

```java
Hq008FlowSdk.init(this, "ADHQ1001");
```

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
}

@Override
public void onDreamingStopped() {
    Hq008FlowSdk.clearAdCallback();
    finishCurrentSessionAsFailed("SCREENSAVER_STOPPED");
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
