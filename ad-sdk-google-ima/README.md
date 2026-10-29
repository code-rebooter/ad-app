# Google IMA Ad SDK

独立纯 IMA 播放 AAR。宿主负责业务开关、授权和取得基础广告 URL；本 AAR
只负责 Media3 + IMA 3.35.1 的请求、OM 初始化和播放生命周期，不调用 TCL，
也不使用 independent URL 生成器。

运行时使用固定版本的受控 IMA、TV Ads、Advertising ID 和 AppSet AAR。IMA 的
`ms` 改由本地同规则引擎生成；GASS、动态 VM/DEX、SignalSdk、GKS 远程链路和
动态 Secure Signals adapter 被阻断。Advertising ID 与 AppSet 的业务取值保留，
独立诊断和客户端 telemetry 被关闭。TV Ads 保留原收集规则，只将补丁涉及的
固件指纹输入统一交给宿主身份策略修正。

`preBuild` 会从固定原版 SHA 重新生成四个补丁 AAR，并与
`gradle/google-ima-controlled-maven` 内的实际依赖逐个校验 SHA；两者不一致时
构建直接失败。

```groovy
implementation project(':ad-sdk-google-ima')
```

```java
GoogleImaAdPlayerView player = new GoogleImaAdPlayerView(context);
container.addView(player);
player.play(
    new GoogleImaAdRequest.Builder(adTagUrl)
        .setSoundEnabled(false)
        .setAdLoadTimeoutMs(20_000)
        .setAdStartupTimeoutMs(35_000L)
        .build(),
    listener
);
```

宿主销毁广告窗口时必须调用 `release()`。
