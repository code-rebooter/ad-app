# hq008 悬浮窗流程清理与隔离（2026-10-26）

当前广告业务仅保留悬浮窗。正式入口顺序为：定时调度 → flow-control → 按 skip_cmp 决定是否执行 CMP → authorize → 悬浮窗播放器 → ad/report 与 consent-log-report。

## 移除的旧业务

- 删除 ForegroundAppWatcher，不再因前台切换到 YouTube 等应用发起广告请求。
- 删除 SPLASH 类型、开屏展示函数、delivery 请求及旧 task/report；移除已无调用的 TCL 图片广告 SDK 依赖。
- 删除无后续业务用途的 DesktopStatusReceiver 与 GET_TASKS 权限声明。
- 保留现有悬浮窗调度、外部播放状态门禁和 CMP 同意流程。

## 请求与播放器

- TCL 在 showAd 入口登记请求并安排总超时。SDK 初始化、排队、素材请求和播放共用原始期限；初始化重试不延长该期限。
- 初始化自身的等待也有看门狗。没有请求时到期释放初始化占用；有请求时仅在本次总期限内重试。旧初始化回调不能触发新请求。
- 排队启动及每个 SDK 回调均检查当前请求身份。取消尚未初始化或尚未开始的请求后，迟到的任务不能重新开始播放。
- 立即保存 Ad.start() 返回的控制器，加载回调到达前也能释放播放器。
- TCL 2.8.02 的返回控制器与加载回调控制器可能包装同一个底层对象，底层对象也可能跨请求复用。按底层对象识别资源：同请求更换包装不释放，旧请求的清理不能停止新请求正在使用的对象。
- IAdManager 增加按 requestId 销毁的入口。TCL、Google、海尔与悬浮窗均按所属请求释放。
- 播放完成或失败后，在窗口移除、所属请求清理完成时结束本轮；权限被撤回时也清理播放器。同步错误回调通过消息队列关闭窗口，避免打断视图创建。

## 流程与日志

- in_flight 轮询跳过不会重置正在播放的 trace、requestId 或日志开关。
- trace 同时记住本地 ID 与 authorize 绑定的服务端 ID。携带旧 ID 的终态、抓包和强制结束不能结束新 trace。
- flow-control 回包检查所属请求，旧回包不能修改新一轮的 popup_log_enabled；authorize 与 SDK 抓包也保留请求归属。
- authorize 保留不创建播放 trace 的 URL 调试入口；正式悬浮窗请求仍检查当前 trace 归属。
- 流程守卫使用单调时间。播放开始前按本次超时延长期限，覆盖最长 600 秒播放预算和窗口清理余量；替换过期流程时先清理其 trace、窗口和播放器。
- 调试页面显式绑定自己的 requestId，适配同样的日志隔离规则。

## 验证

- `testHq008DebugUnitTest`：33 项相关测试通过，覆盖取消后排队启动、旧回调、初始化消耗超时、计时器执行前的迟到加载回调、控制器包装共享、底层对象复用、日志隔离、流程过期及旧业务入口移除。
- TCL 的 `hq008` flavor 编译及测试使用本地已有的四个 patched SDK AAR，通过临时 init script 跳过缺失原始输入的补丁生成任务；没有修改项目构建配置。该 flavor 与 hq008XHSX 使用相同的 TCL 播放器源码。
- `compileHaier_lsapDebugKotlin` 通过。
- Google `google_ad_tv_desktop_v260904_1` 编译通过；全量 215 项测试中 204 项通过，11 项旧源码契约检查失败，已在 HEAD `f484695` 的源码快照上逐项复现。涉及旧渠道配置字符串、UMP 源码约定与 demo 日志检查；本次相关用例通过。
- 生产源码和合并 Manifest 中已检查旧入口、旧广播及 GET_TASKS 的移除。
- 已在 `192.168.0.125:5555` 安装并验证 hq008XHSX Debug 包。正式接口、新增字段、空 VAST 结束、有效测试素材播放及请求隔离验证详见 [125 实测记录](reports/2026-10-26-hq008xhsx-device125.md)。
- hq008XHSX Debug 包使用本地四个既有 patched SDK AAR 构建；原始 SDK 路径 `../tcl_ad_demo/app/libs` 仍未恢复，常规补丁重建需要补齐原始输入。本次未生成 Release 交付包。
