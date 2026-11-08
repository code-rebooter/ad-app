#!/usr/bin/env python3
"""Package the locally published dual-engine SDK, without keys or test fixtures."""
import argparse,hashlib,json,re,shutil,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--version',default='2026.11.06');p.add_argument('--skip-build',action='store_true')
p.add_argument('--output',default='build/customer-sdk')
p.add_argument('--validation-report',default='docs/reports/2026-11-06-combined-sdk-customer-validation.md')
p.add_argument('--frozen-repository',action='store_true',help='Package the already validated repository without refreshing controlled dependencies')
a=p.parse_args();root=Path(__file__).resolve().parents[2];dest=root/a.output;repo=dest/'repository'
if a.frozen_repository and not a.skip_build:p.error('--frozen-repository requires --skip-build')
modules=[':ad-sdk-tcl',':ad-sdk-tcl:runtime-base',':ad-sdk-tcl:runtime-player',':ad-sdk-tcl:runtime-media',':ad-sdk-tcl:google-ima',':ad-sdk-tcl:combined']
if not a.skip_build:
 subprocess.run(['./gradlew','-I','ad-sdk-tcl/tools/delivery.init.gradle','-PsdkDeliveryVersion='+a.version,'-PcustomerSdkOutputRoot='+str(dest)]+[m+':publishReleasePublicationToCustomerRepository' for m in modules]+['--offline','--console=plain'],cwd=root,check=True)
if not a.frozen_repository:
 shutil.copytree(root/'ad-sdk-tcl/google-ima/controlled-maven',repo,dirs_exist_ok=True)
else:
 controlled=list((repo/'com/smart/android/tclad/ima/controlled').rglob('*.aar'))
 if len(controlled)!=5:raise SystemExit('Frozen repository requires all five validated controlled AARs')
for name in ['ad-sdk-tcl','ad-sdk-tcl-runtime-base','ad-sdk-tcl-runtime-player','ad-sdk-tcl-runtime-media','ad-sdk-tcl-google-ima','ad-sdk-tcl-combined']:
 for suffix in ['aar','pom','module']:
  path=repo/'com/smart/android'/name/a.version/(name+'-'+a.version+'.'+suffix)
  if not path.is_file():raise SystemExit('Missing delivery artifact: '+str(path))
docs=dest/'docs';docs.mkdir(parents=True,exist_ok=True)
example=dest/'example';example.mkdir(parents=True,exist_ok=True)
example_files={
 'gradle.properties': 'android.useAndroidX=true\norg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8\n',
 'settings.gradle': '''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { maven { url uri('../repository') }; google(); mavenCentral(); maven { url 'https://jitpack.io' } }
}
rootProject.name = 'DualAdSdkCustomerExample'
''',
 'build.gradle': '''plugins { id 'com.android.application' version '8.10.1' }
android {
    namespace 'com.smart.sdkexample'
    compileSdk 36
    defaultConfig { applicationId 'com.smart.sdkexample.dualads'; minSdk 24; targetSdk 36; versionCode 1; versionName '1' }
    buildTypes { release { minifyEnabled true; proguardFiles getDefaultProguardFile('proguard-android-optimize.txt') } }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}
dependencies { implementation 'com.smart.android:ad-sdk-tcl-combined:SDK_VERSION' }
'''.replace('SDK_VERSION',a.version),
 'src/main/AndroidManifest.xml': '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
  <application android:label="Dual Ad SDK Example" android:theme="@android:style/Theme.Material.NoActionBar">
    <activity android:name="com.smart.sdkexample.MainActivity" android:exported="true">
      <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
    </activity>
  </application>
</manifest>
''',
 'src/main/java/com/smart/sdkexample/MainActivity.java': '''package com.smart.sdkexample;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.gson.JsonParser;
import com.smart.android.tclad.*;
import com.smart.android.tclad.ima.*;
import java.util.Map;
import java.util.UUID;

/** Independent customer project: one Maven dependency, both public engine APIs. */
public final class MainActivity extends Activity {
    private GoogleImaAdManager google;
    private Controller tcl;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout container = new FrameLayout(this); setContentView(container);
        String id = UUID.randomUUID().toString();
        boolean sound = getIntent().getBooleanExtra("sound", false);
        String engine = getIntent().getStringExtra("engine");
        String config = getIntent().getStringExtra("gam_config");
        String adurl = getIntent().getStringExtra("adurl");
        if ("google".equals(engine) && config != null) {
            google = new GoogleImaAdManager(GoogleImaExecutionConfig.builder(this, id, "SDK_EXAMPLE").build(),
                new GoogleImaSessionListener() {
                    @Override public void onAdReport(String requestId, String event, String message, Map<String,Object> diagnostics) {
                        Log.i("SdkExample", event); // Persist/send with the customer's reporting transport.
                    }
                    @Override public void onAdCompleted() { finish(); }
                    @Override public void onAdFailed() { finish(); }
                });
            google.showAd(container, JsonParser.parseString(config), sound);
        } else if ("tcl".equals(engine) && adurl != null) {
            tcl = Ad.get().begin(this).lazyLoad().setPlayerType(0).setPlayerViewType(1)
                .setVolume(sound ? 1f : 0f)
                .setExecutionConfig(ExecutionConfig.builder(this, id, "SDK_EXAMPLE").build())
                .setResolvedAd(new ResolvedAd.Builder(adurl).build())
                .listen(new AdStatusListener() {
                    @Override public void onAdLoaded(Controller loaded) { loaded.start(container); }
                    @Override public void onAdFinished() { finish(); }
                    @Override public void onAdError(int code) { finish(); }
                }).start();
        } else {
            TextView help = new TextView(this);
            help.setText("SDK ready. Supply this round's authorized gam_config (Google) or adurl (TCL). See Combined-integration.md.");
            container.addView(help);
        }
    }
    @Override public void onDestroy() {
        if (google != null) google.release();
        if (tcl != null) tcl.release();
        super.onDestroy();
    }
}
'''
}
for name,text in example_files.items():
 file=example/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)
for name in ['gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties']:
 file=example/name;file.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(root/name,file)
doc_sources={
 'ad-sdk-tcl/README.md':'TCL.md', 'ad-sdk-tcl/google-ima/README.md':'Google-IMA.md',
 'ad-sdk-tcl/ISOLATION.md':'TCL-isolation.md', 'ad-sdk-tcl/REQUEST_PARAMETERS.md':'TCL-request-parameters.md',
 'ad-sdk-tcl/PARITY_REQUIREMENTS.md':'TCL-parity.md', 'ad-sdk-tcl/GOOGLE_INTEGRATION.md':'TCL-google-integration.md',
 'ad-sdk-tcl/RESOLVE_TCL_CONTRACT.md':'TCL-resolve-contract.md', 'ad-sdk-tcl/vendor/README.md':'TCL-vendor.md',
 'ad-sdk-tcl/combined/README.md':'Combined-integration.md',
 a.validation_report:'device-validation.md'}
doc_targets={(root/s).resolve():t for s,t in doc_sources.items()}
for source,target in doc_sources.items():
 file=root/source
 if not file.exists():continue
 def relocate_link(match):
  label,link=match.groups()
  if link.startswith(('https://','http://','#')):return match.group(0)
  path,sep,anchor=link.partition('#');resolved=(file.parent/path).resolve()
  if resolved in doc_targets:return '['+label+']('+doc_targets[resolved]+(sep+anchor if sep else '')+')'
  return label+'（源码工作区历史记录：`'+link+'`）'
 (docs/target).write_text(re.sub(r'\[([^\]]+)\]\(([^)]+)\)',relocate_link,file.read_text()))
readme='''# Google 纯 IMA + TCL 二合一 SDK

版本：SDK_VERSION。最低 Android API 24，Java 17，建议 compileSdk 36。

把本目录的 repository 完整复制到宿主项目，settings.gradle 添加仓库：

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url uri("$rootDir/repository") }
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

app/build.gradle 只需声明一个 SDK 坐标：

```groovy
implementation 'com.smart.android:ad-sdk-tcl-combined:SDK_VERSION'
```

Google 入口为 com.smart.android.tclad.ima.GoogleImaAdManager，TCL 入口为
com.smart.android.tclad.Ad / Controller。两套播放器、配置、取链与回调独立，按
本轮授权选择入口。Google 使用纯 IMA，不含自拼接链接引擎。先阅读
docs/Combined-integration.md 的会话和回调语义。完整参考分别见
docs/Google-IMA.md、docs/TCL.md；源码工作区的 project(...) 依赖请替换为上面的合并坐标。

请保留整个 Maven 仓库及 POM/Gradle metadata。合并坐标会传递引入两套 SDK、
三个 TCL 运行时及五份受控 Google AAR；不能只复制 combined.aar。AndroidX、
OkHttp、Gson 等公共库由 Google/Maven Central 解析，原厂 lib_dimen 来自 JitPack，
这些公共库未打入单个 AAR，首次构建需要访问对应仓库。

example 是独立的最小 Java 宿主项目，只通过上述一个 Maven 坐标接入，Release 已开启 R8。
在 Android Studio 中打开该目录，配置本机 Android SDK 后执行 ./gradlew assembleRelease。
缺省启动不会请求广告；只有显式提供本轮授权配置后才执行指定引擎。

宿主负责 CMP/UMP、业务授权、调度以及报告 HTTP 发送/持久化/重试。
SDK 已提供状态和审计回调；测试采集器不属于交付 SDK。窗口销毁时释放当前引擎；
跨引擎切换先释放上一轮。不要同时依赖原 ad-sdk-google-ima 或 Google 原版的五份
受控替代包，以免出现重复类。

R8 consumer rules 随 AAR 传递；宿主无需关闭混淆。SHA256SUMS 可核对全部文件。
真实验证结果见 docs/device-validation.md（生成后随包附上）。
'''.replace('SDK_VERSION',a.version)
(dest/'README.md').write_text(readme)
report=root/'docs/reports/2026-11-06-combined-sdk-customer-validation.md'
files=[dest/'README.md']+sorted(x for x in repo.rglob('*') if x.is_file())+sorted(x for x in docs.rglob('*') if x.is_file())+[example/name for name in example_files]+[example/name for name in ['gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties']]
manifest={str(x.relative_to(dest)):hashlib.sha256(x.read_bytes()).hexdigest() for x in files}
(dest/'SHA256SUMS').write_text(''.join(h+'  '+name+'\n' for name,h in manifest.items()))
(dest/'artifact-manifest.json').write_text(json.dumps({'version':a.version,'minimum_sdk':24,'engines':['tcl-2.8.02','google-ima-3.35.1'],'files':manifest},indent=2)+'\n')
archive=dest.parent/('ad-sdk-tcl-combined-'+a.version+'.zip')
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for file in files+[dest/'SHA256SUMS',dest/'artifact-manifest.json']:z.write(file,'ad-sdk-tcl-combined-'+a.version+'/'+str(file.relative_to(dest)))
print(json.dumps({'archive':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'size':archive.stat().st_size},indent=2))
