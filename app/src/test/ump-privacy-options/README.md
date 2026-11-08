# UMP privacy options 就绪等待回归

直接执行当前生产 `GoogleUmpSilentConsentFormRunner.kt`，Android Handler/窗口与 UMP show 入口使用确定性夹具；不复制或改写生产算法。

```sh
python3 app/src/test/ump-privacy-options/run.py
# 或指定输出目录
python3 app/src/test/ump-privacy-options/run.py build/ump-privacy-options-tests
```

使用本机 Gradle 缓存中的 Kotlin 2.2.21 和 JDK；可通过 `JAVA_HOME` 指定 JDK。结果写入指定目录的 `summary.json`，编译/执行失败退出非零。

24 项覆盖：原 250ms 初次点击、8 秒预加载、29 秒预加载后的独立动作预算、30 秒等待上界、无视图、非 loading 错误、SDK 同步异常/回调、迟到和重复回调、宿主销毁、原 15 秒动作超时、ACCEPT/REJECT 脚本替换；异步挂载首帧隐藏、历史临界 ACK 时序、无关根视图隔离、终态后排队 JS、原生 deadline、旧 token、初始表单及每帧重复窗口更新。

## 真实 JavaScript 选择规则回归

```sh
python3 app/src/test/ump-privacy-options/run.py
npm install --prefix app/src/test/ump-privacy-options --ignore-scripts
node app/src/test/ump-privacy-options/decision-script.cjs
```

亦可将 `UMP_JS_MODULES` 指向已安装 `jsdom@26.1.0` 的独立目录，后接两个参数指定上一步生成的 `scripts` 目录与输出目录。测试依赖仅供该夹具使用，不进入应用。

14 项 JavaScript 回归将当前生产 runner 实际输出的脚本与 `decision-baseline.js` 的原选择规则比较：ACCEPT、REJECT、Manage、78 项偏好设置与 Confirm、shadow root、fallback、滚动，以及已结束请求、布局中取消、批处理取消、prompt 延迟、动作 deadline。该基线是加终态隔离前的原模板，不是另一份改写算法。

该夹具验证生产调度与收口，不将 SDK/JS 夹具结果冒充真实 Google 服务结果；真实服务实测记录另存于本轮设备验证目录。
