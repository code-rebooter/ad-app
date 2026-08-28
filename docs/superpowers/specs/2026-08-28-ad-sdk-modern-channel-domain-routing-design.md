# ad-sdk-modern 渠道域名映射设计

## 目标

`ad-sdk-modern` 根据现有 `adChannelId` 在 SDK 内部选择自有后台 Base URL，客户不新增任何配置：

- `GOOGLE_AD_TV_CVTE` 使用 `https://api.xartek.cc/`。
- 其他渠道继续使用 `https://api.kytira.cc/`。
- Google UMP 和 IMA 自身管理的官方请求不受影响。

同时删除 SDK 内重复打包的 `androidx.preference.PreferenceManager`，统一使用 IMA 传递依赖提供的官方实现。

## 设计

域名只在 `SdkRuntime` 创建默认组件时解析一次。解析后的 Base URL 统一传给 flow-control、consent-popup、consent-report、authorize、Google GAM resolve 和广告状态 report，避免不同接口使用不同域名。

渠道匹配忽略首尾空白和大小写。未知、空白或其他渠道全部回退到现有公共域名，保持已有客户行为不变。

## 错误处理

两个 Base URL 都是 SDK 内部固定的 HTTPS 地址，不接受客户输入。现有网络错误、HTTP 错误和广告 Session 终止逻辑保持不变。

## 验证

不新增或运行回归测试。发布前仅执行生产源码检查、`git diff --check`、release 编译和本地 Maven 发布，确认 AAR 不再包含自定义 `PreferenceManager`，然后发布 JitPack `v1.0.12`。
