# GOOGLE_AD_TV_LOCKSCREEN 1.0.5 Release Design

## Scope

- Update only the `google_ad_tv_lockscreen` product flavor.
- Increase `versionCode` from `8` to `9`.
- Increase `versionName` from `1.0.4` to `1.0.5`.
- Add the actual release build time as an inline comment after `versionName`, using `YYYY-MM-DD HH:mm CST`.
- Preserve all existing UMP behavior and unrelated working-tree changes.

## Build And Verification

- Verify Gradle resolves the lockscreen variant as version `1.0.5` with version code `9`.
- Run the focused UMP contract tests that match the current consent-state behavior.
- Build `:app:assembleGoogle_ad_tv_lockscreenRelease`.
- Report the APK path, file size, checksum, and embedded version metadata.

## Failure Handling

- If a focused test fails because it still asserts superseded UMP behavior, update only that stale contract to match the agreed current behavior and rerun it.
- If compilation, signing, or packaging fails, keep the version change in place, report the exact failure, and do not modify unrelated channel configuration.
