# System property logging implementation plan

**Goal:** All application and SDK diagnostic output is disabled unless Android's `persist.sys.ad.log` is `true`, for both Debug and Release.

**Design:** Generate one identical logging implementation under each Android module's namespace, so standalone AARs remain self-contained without duplicate classes. All source wrappers delegate to that implementation. Android Gradle Plugin bytecode instrumentation gates Java/Kotlin logging from APK dependencies as well as project code. Read the property at each output; missing, false, invalid, or unreadable values fail closed. Preserve network reporting and functional debug/test behavior unrelated to logging.

**Scope:** All nine Android modules and all app flavors; Android Log, System.out/System.err, uncaught diagnostic printStackTrace calls, configured vendor logging and WebView console forwarding. Native/platform logs outside project bytecode need separate accounting in the verification report. Previously delivered APKs must be replaced; source changes cannot affect them.

## Tasks

- [x] Add failing runtime tests for property values, dynamic toggles, reflection failures, all Log overloads and standard streams. Add bytecode rewriting tests proving dependency calls are gated without changing file streams.
- [x] Implement the shared Java template, per-module generation, and AGP instrumentation for application ALL / library PROJECT classes, including embedded local JAR verification. Gate core-library desugaring input before L8 as well.
- [x] Replace source-level Debug/config/always-on gates, direct logging, vendor switches and WebView console forwarding with the module's property logger. Audit stress-file diagnostics separately from business reporting.
- [x] Run buildSrc tests, targeted existing tests and Debug/Release builds of JM plus affected SDKs and demos; inspect built APK/AAR bytecode for remaining direct sinks. All ten selected final artifacts passed the Java/Kotlin sink audit; HQ002/HQ004 additional builds remain blocked by existing shared-source dependency errors, recorded in the report.
- [x] Obtain independent code review, fix findings, and record the exact validation results and limitations. No new critical/important issues found within the documented Java/Kotlin scope; native libraries, downloaded plugins and external AAR consumers remain explicitly outside the verified coverage.

**Result:** Repository Java/Kotlin logging and ten selected artifacts are verified. The broader request for every runtime diagnostic is not fully met: the bundled native plugin loader and externally loaded code still require changes in their own build/source pipelines. Details: `docs/reports/2026-09-23-system-property-logging-audit.md`.

**Validation commands:** `./gradlew :buildSrc:test`, `./gradlew :app:assembleGoogle_ad_tv_desktop_jmDebug :app:assembleGoogle_ad_tv_desktop_jmRelease`, and affected library `assembleDebug assembleRelease` / targeted unit-test tasks. Runtime fixtures use a fake Android property reader and Log sink to verify emitted output without changing a customer's device. Artifact audit must permit Android output calls only inside generated property loggers.
