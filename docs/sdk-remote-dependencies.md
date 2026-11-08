# TCL / Google IMA / Flow 远程依赖

仓库：`https://jitpack.io`。本次发布标签：`v1.0.23`。

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

双引擎与流程 SDK：

```groovy
dependencies {
    implementation 'com.github.code-rebooter.ad-app:ad-sdk-tcl-combined:v1.0.23'
    implementation 'com.github.code-rebooter.ad-app:hq008-flow-sdk:v1.0.23'
}
```

仅需单引擎时，将 combined 替换为 `ad-sdk-tcl` 或 `ad-sdk-tcl-google-ima`，不要重复添加 combined 及其子模块。

六个 TCL/IMA 模块、Flow 和五份受控 Google 运行时均随同一标签发布。
POM 中的内部依赖全部改为同标签的远程坐标，不要求客户复制本地 Maven 目录。
受控运行时沿用固定 IMA 3.35.1 / Media3 1.8.0 等原有二进制；Flow 内部版本为 2.1.5。
发布仅增加构建/依赖元数据，不修改已验证的运行时逻辑。

本地预检：

```sh
./gradlew -I gradle/sdk-remote-publish.init.gradle \
  :publishToMavenLocal \
  :ad-sdk-tcl:publishReleasePublicationToMavenLocal \
  :ad-sdk-tcl:runtime-base:publishReleasePublicationToMavenLocal \
  :ad-sdk-tcl:runtime-player:publishReleasePublicationToMavenLocal \
  :ad-sdk-tcl:runtime-media:publishReleasePublicationToMavenLocal \
  :ad-sdk-tcl:google-ima:publishReleasePublicationToMavenLocal \
  :ad-sdk-tcl:combined:publishReleasePublicationToMavenLocal \
  :hq008-flow-sdk:publishReleasePublicationToMavenLocal \
  -PPUBLISH_VERSION=v1.0.23
```
