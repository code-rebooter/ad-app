# ADDY JAMS LSAP 1.1.12 R2 AAR Upgrade Design

## Goal

Use the customer-reissued `addy_jams` LSAP 1.1.12 AAR for a single-channel release test while preserving the current AAR as an immediate rollback artifact and retaining the existing UA, device-model, and network-audit bytecode patch.

## Scope

- Upgrade only `addy_jams`.
- Keep `haier_lsap` and `addy_hq1002` unchanged.
- Keep the current `addy_jams` 1.1.12 AAR unchanged in `app/libs/addy_jams/`.
- Add the reissued binary with the local revision suffix `1.1.12-r2` because the supplier reused version `1.1.12` for different bytecode.
- Increment the `addy_jams` application version to versionCode 8 and versionName 1.0.8 so the test build can upgrade versionCode 7 / versionName 1.0.7 in place.

## Input Identity

| Artifact | SHA-256 |
| --- | --- |
| Existing 1.1.12 AAR | `24e3651af8b1eb8e6f8313ad9225d6725cd64f24915430de07a89686bdeaee88` |
| Reissued 1.1.12 R2 AAR | `b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30` |

Static comparison shows that the AAR resources, manifest, ProGuard rules, and class list are unchanged. Only `classes.jar` differs. The reissued `UnifiedAdSdk` adds main-thread dispatch for callbacks and detach cleanup, session/listener terminal guards, and stale callback suppression. These changes are consistent with the reported player-release race fix, but device testing remains required.

## Artifact Layout

The existing file remains:

```text
app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12.aar
```

The reissued input is stored as:

```text
app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2.aar
```

The build-generated patched output is:

```text
app/build/generated/lsap-patched/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2-patched.aar
```

`app/build.gradle` continues to bind `addy_jamsImplementation` to `lsapPatchedAars.addy_jams`; only the `addy_jams` patch specification input, output, and expected SHA change.

## Patch Compatibility

The existing `LsapClassPatcher` remains the first choice. The patch task must reject the new AAR if any SHA, network-surface, residual-call, or minimum-class gate fails. Validation must not weaken those gates.

Successful patch metadata must contain:

- `patchVersion=lsap-full-network-audit-2`
- `originalAarSha256=b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30`
- `targetFlavor=addy_jams`
- `lsapSdkVersion=1.1.12`
- all 11 required `networkSurface` categories
- a modified-class count consistent with the current 39-class baseline

If the task fails because the vendor bytecode changed at a patched call site, the patcher and its focused tests must be updated from a bytecode comparison. The check itself must remain enabled.

## Automated Verification

A contract test will first establish the new expected state and fail against the current repository. It will assert that:

- both the old and R2 AAR files exist;
- the `addy_jams` patch input points to R2;
- the configured SHA is the R2 SHA;
- the patched output uses the R2 filename;
- `addy_jamsImplementation` still consumes the generated patched artifact.

After integration:

1. Run the focused contract test.
2. Run `:app:patchAddyJamsLsapAar`.
3. Inspect `META-INF/lsap-ua-audit.properties` in the generated AAR.
4. Run the `addy_jams` debug unit-test task.
5. Build `:app:assembleAddy_jamsRelease`.
6. Inspect the release APK to confirm the R2 patched classes and runtime bridge are packaged and the unpatched R2 AAR is not used directly.

## Device Acceptance

The release APK is a test candidate, not proof of the player fix. The test team must exercise at least:

- normal ad completion;
- window removal during playback;
- request failure and timeout cleanup;
- repeated show/destroy cycles;
- app foreground/background transitions;
- process restart after playback.

Acceptance requires no freeze or ANR, no player/codec thread leak visible in captured diagnostics, and no regression in ad callbacks or existing UA/network audit reporting.

## Rollback

Rollback changes only the `addy_jams` patch specification back to the existing AAR filename and SHA, then regenerates the patched AAR and release APK. The old input stays present throughout the trial, so rollback does not depend on recovering the supplier file from chat history.
