# Ad SDK JitPack 发版说明

当前仓库远程地址为 GitHub 时，JitPack 依赖坐标格式为：

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk:v1.0.0'
```

或：

```groovy
implementation 'com.github.code-rebooter.ad-app:ad-sdk-modern:v1.0.0'
```

客户侧需要添加 JitPack 仓库：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

发版前先提交所有代码，然后执行：

```bash
./scripts/release_jitpack.sh v1.0.0
```

脚本会执行：

```text
构建两个 release AAR
发布两个模块到本机 Maven 仓库做校验
推送当前分支到 origin
创建并推送 Git tag
请求 JitPack build.log 触发预构建
```

两个依赖不要同时接入，客户只能二选一；两套 SDK 的公开 Java API 包名一致，同时接入会出现重复类冲突。
