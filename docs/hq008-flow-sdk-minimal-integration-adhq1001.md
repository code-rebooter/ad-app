# HQ008 Flow SDK 最简接入说明

## 1. Application 初始化

在 `MainApplication.onCreate()` 里只初始化流程 SDK，一次即可：

```java
import com.smart.android.hq008flow.Hq008FlowSdk;

public class MainApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Hq008FlowSdk.init(this, "ADHQ1001");

        // 客户原有广告 SDK 初始化逻辑保持不变
    }
}
```

说明：

- `init()` 会自动启动全局定时流程。
- 客户不需要手动请求 `flow-control` 或 `authorize`，这些都由 SDK 内部完成。
- 渠道号按后台实际分配填写；客户当前 APK 使用的是 `ADHQ1001`。

## 2. 屏保服务注册广告回调

在 `SystemScreensaverService` 里注册一个广告授权回调。这个回调只有一个作用：SDK 后台流程通过后，通知客户“本轮可以播广告了”。

```java
import com.smart.android.hq008flow.Hq008AdCallback;
import com.smart.android.hq008flow.Hq008AdSession;
import com.smart.android.hq008flow.Hq008FlowSdk;

public class SystemScreensaverService extends DreamService {
    private Hq008AdSession currentAdSession;

    private final Hq008AdCallback hq008AdCallback = new Hq008AdCallback() {
        @Override
        public void onAdAuthorized(Hq008AdSession session) {
            currentAdSession = session;

            // flow-control 和 authorize 都通过了
            // 这里走客户原有的广告请求/播放逻辑
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

    private void requestScreensaverAd() {
        // 客户原有逻辑：
        // 可以继续按当前策略调用 loadWhaleAd() 或 loadTad()
        loadWhaleAd();
    }
}
```

说明：

- 广告展示在哪个组件，就在哪个组件调用 `setAdCallback()`。
- 组件退出或不能展示广告时，调用 `clearAdCallback()`。

## 3. 广告事件回传

客户原有广告 SDK 的回调里，只需要把播放状态转给 `currentAdSession`。

### 广告加载成功

```java
private void onAdLoaded() {
    if (currentAdSession != null) {
        currentAdSession.loaded();
    }
}
```

### 广告开始播放

```java
private void onAdStartPlay() {
    if (currentAdSession != null) {
        currentAdSession.started();
    }
}
```

### 广告播放完成

```java
private void onAdFinished() {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;

    if (session != null) {
        session.completed();
    }

    // 客户原有的完成处理继续执行
}
```

### 广告播放失败

```java
private void onAdError(int errorCode, String errorMessage) {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;

    if (session != null) {
        session.failed(errorCode, errorMessage);
    }

    // 客户原有的失败处理继续执行
}
```

如果广告 SDK 的错误码是字符串，可以这样传：

```java
private void onAdError(String errorCode, String errorMessage) {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;

    if (session != null) {
        session.failed("AD_ERROR_" + errorCode + ": " + errorMessage);
    }
}
```

## 4. 不播放广告时也要结束 Session

只要进入了 `onAdAuthorized(session)`，客户本轮就必须最终调用一次：

```java
session.completed();
```

或者：

```java
session.failed(code, message);
```

如果业务判断本轮不播，例如广告开关关闭、正在播放中、没有广告容器、广告 SDK 未初始化，也要按失败结束：

```java
private void finishCurrentSessionAsFailed(String reason) {
    Hq008AdSession session = currentAdSession;
    currentAdSession = null;

    if (session != null) {
        session.failed(reason);
    }
}
```

否则 SDK 收不到完成或失败状态，只能等 180 秒超时，后台看到的就是超时状态。
