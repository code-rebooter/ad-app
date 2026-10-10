# TCL 原厂输入及隔离运行时

本模块使用三个原厂 TCL 2.8.02 AAR：

| 输入 | SHA-256 | class 数 |
| --- | --- | --- |
| tcl-base-2.8.02.aar | `1e9660a0e335e8e87e98057f5a0df520791a9754a0eb47dff789f00f7ecb56de` | 278 |
| tcl-media-2.8.02.aar | `22426d990e7e27e1fea05b2c014ea4960d3fa5da5efe2b92f945704ab3abc9ca` | 169 |
| tcl-player-2.8.02.aar | `74d2c79e0af1a62697e36bc84929d28504ca264d69856505fdd3606e9a743907` | 1,958 |

`TclOriginalRuntimeTask` 先核对输入哈希，再原样解包 classes.jar、资源与 consumer rules。三个内部模块保留各自原始 R 命名空间。Manifest 的 package 声明按 AGP 要求移动到 namespace，隔离任务删除 BI/数据库实现并修改明确的副作用边界；输入原件不修改。

原厂 media 的 R 类引用了 base 的颜色与尺寸资源。runtime-media 同时读取 base 的原始资源目录，使现代非传递 R 构建仍生成原厂所需字段；原始的 580 个资源值及限定条件逐项核对。

`verify_isolated_runtime.py` 按隔离清单比较 Release AAR：190 个类删除，13 个类修改边界，其余 2,202 个类不变。`verify_original_resources.py` 检查原厂 R 字段引用及资源值。APK 编译为 DEX 后不作“DEX 与 class 文件字节相同”的断言。

广告业务由原厂 media_ad 执行。网络、VAST 模型与转换器、Wrapper、控制器、事件回报、播放器均来自这些原件；SDK 自有代码只提供公开 API 和链接来源适配。配置快照属于输入，由原厂配置模型读取。

隔离规则和边界逐项记录在 [ISOLATION.md](../ISOLATION.md)。前一版整包复用不满足副作用隔离要求。
