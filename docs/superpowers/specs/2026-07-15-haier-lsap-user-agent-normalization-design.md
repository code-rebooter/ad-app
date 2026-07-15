# Haier LSAP User-Agent Normalization Design

Date: 2026-07-15

## Objective

Correct malformed Android Dalvik User-Agent values on the `haier_lsap` channel without changing valid device User-Agent values.

The affected ROM cannot be trusted as a source for `Build.VERSION.RELEASE`, `Build.MODEL`, or `Build.ID` after an invalid UA is detected. Therefore, the correction branch uses an application-owned SDK profile instead of rebuilding from ROM-provided identity fields.

## Scope

- Enable only for the exact `haier_lsap` flavor.
- Support the app runtime range API 23 through API 36.
- Normalize `System.getProperty("http.agent")` before any ad SDK or network component can cache it.
- Leave a valid original UA byte-for-byte unchanged.
- Replace an invalid original UA with a deterministic Haier TV UA selected by `Build.VERSION.SDK_INT`.
- Do not use the currently connected abnormal test device as a template.
- Do not change unrelated product flavors.

Separate request fields such as `make`, `model`, and `osv` are outside this implementation scope. They must be reviewed separately if the server validates them against the UA.

## Chosen Behavior

The fixed portion of a corrected UA is:

```text
Dalvik/2.1.0 (Linux; U; Android <official-version>; Haier TV Build/<canonical-build-id>)
```

The Android version and canonical build ID are selected together from an application-owned API-level table. This prevents combinations such as Android 11 with an Android 10 `QP1A` build.

Processing is idempotent:

1. Read the current Java system property `http.agent`.
2. Parse and validate it against the current API level.
3. If valid, return and install the original string unchanged.
4. If invalid, select the canonical profile for the current API level.
5. Generate and install the corrected UA.
6. If no profile exists, preserve the original value and log an unsupported-SDK diagnostic rather than guessing.

## Canonical Profiles

The profile table covers every supported runtime API level.

| SDK | Public Android label | Canonical build ID |
|---:|---|---|
| 23 | 6.0 | `MRA58K` |
| 24 | 7.0 | `NRD90M` |
| 25 | 7.1 | `NDE63H` |
| 26 | 8.0 | `OPR6.170623.010` |
| 27 | 8.1 | `OPM1.171019.011` |
| 28 | 9 | `PPR1.180610.009` |
| 29 | 10 | `QP1A.190711.019` |
| 30 | 11 | `RP1A.200720.009` |
| 31 | 12 | `SP1A.210812.015` |
| 32 | 12L | `SP2A.220305.012` |
| 33 | 13 | `TP1A.220624.014` |
| 34 | 14 | `UP1A.231005.007` |
| 35 | 15 | `AP3A.240905.015.A2` |
| 36 | 16 | `BP2A.250605.031.A2` |

These are application normalization profiles based on Android release build families. They are not claims about the faulty ROM's original vendor firmware identity.

## Validation Rules

### Syntax

A UA is invalid when any of the following is true:

- It is blank.
- It contains control characters, carriage returns, or line feeds.
- It cannot be parsed as a Dalvik Android UA with Android version, model, and Build ID fields.
- Any required parsed field is blank.

### Android version

The parsed Android version must be compatible with the current `SDK_INT`.

| SDK | Accepted normal version labels |
|---:|---|
| 23 | `6.0`, `6.0.1` |
| 24 | `7.0` |
| 25 | `7.1`, `7.1.1`, `7.1.2` |
| 26 | `8.0`, `8.0.0` |
| 27 | `8.1`, `8.1.0` |
| 28 | `9` |
| 29 | `10` |
| 30 | `11` |
| 31 | `12` |
| 32 | `12`, `12L` |
| 33 | `13` |
| 34 | `14` |
| 35 | `15` |
| 36 | `16` |

The validator must not apply a global "decimal means invalid" rule. Android 6.0, 7.1, and 8.1 are official releases. For API 30, however, `11.1` is invalid because the accepted label is only `11`.

### Model

The parsed model is invalid when it is a known generic or platform identity rather than a TV brand/model. Matching is case-insensitive after trimming.

Initial invalid values:

```text
TV BOX
Android TV
Android TV Box
AOSP
generic
unknown
mstar
walley
walleye
```

This set is deliberately explicit and testable. New customer-confirmed bad values can be added without changing the parser.

### Build ID

Build validation is conservative to avoid rejecting valid OEM-specific build identifiers.

- An empty or unsafe Build ID is invalid.
- If the Build ID matches a recognized Android/AOSP family, that family must be compatible with the current API level.
- A recognized conflicting combination is invalid, such as `QP1A.191105.004` on API 30 or later.
- An unknown but syntactically safe OEM Build ID is not rejected solely because its prefix is unfamiliar.

Recognized families include:

| Family | Compatible SDK |
|---|---:|
| `M...` | 23 |
| `N...` | 24-25 |
| `O...` | 26-27 |
| `P...` | 28 |
| `Q...` | 29 |
| `R...` | 30 |
| `S...` | 31-32 |
| `T...` | 33 |
| `U...` | 34 |
| `AP3A...` | 35 |
| `BP2A...` | 36 |

## Components

### `HaierUserAgentNormalizer`

A pure Kotlin component responsible for:

- parsing a UA;
- determining whether it is valid for an injected SDK integer;
- selecting a canonical profile;
- returning a normalization result containing the original UA, effective UA, whether it changed, and a reason code.

The component must not access Android framework state directly so it can be unit tested on the JVM.

### `HaierUserAgentInstaller`

An Android-facing component responsible for:

- reading `Build.VERSION.SDK_INT`;
- reading `System.getProperty("http.agent")`;
- invoking the normalizer;
- calling `System.setProperty("http.agent", effectiveUa)` only when the value changed;
- emitting a concise diagnostic.

### Application integration

Install from `APP.attachBaseContext()` before `super.attachBaseContext(base)` and before all SDK initialization. Activation must check the exact flavor rather than the broader LSAP-family helper, because `BuildFlavor.isHaierLsap()` also includes other product flavors.

Conceptual integration:

```kotlin
override fun attachBaseContext(base: Context) {
    if (BuildConfig.FLAVOR == "haier_lsap") {
        HaierUserAgentInstaller.install()
    }
    super.attachBaseContext(base)
}
```

The Java system property is process-local. If a future `haier_lsap` component loads the ad SDK in another process, that process must execute the same installation path.

## Diagnostics

Result reason codes:

- `UNCHANGED_VALID`
- `REPLACED_BLANK`
- `REPLACED_UNSAFE_CHARACTERS`
- `REPLACED_UNPARSEABLE`
- `REPLACED_VERSION_MISMATCH`
- `REPLACED_GENERIC_MODEL`
- `REPLACED_BUILD_MISMATCH`
- `UNCHANGED_UNSUPPORTED_SDK`

Release logging should include SDK, changed state, and reason. Full original and corrected UA values should be limited to debug logging.

## Tests

JVM unit tests must cover:

- valid UA values for each API level 23-36 remain byte-for-byte unchanged;
- official legacy decimal versions such as Android 6.0, 7.1, and 8.1 remain valid;
- API 30 with Android 11 remains unchanged;
- API 30 with Android 11.1 is replaced;
- API 30 with a recognized `QP1A` build is replaced;
- generic models including `TV BOX` and `mstar` are replaced;
- malformed and control-character input is replaced;
- an unknown but safe OEM Build ID is accepted when the other fields are valid;
- every abnormal input on SDK 23-36 resolves to the expected canonical profile;
- normalization is idempotent;
- unsupported SDK input is preserved rather than guessed;
- installation is scoped to the exact `haier_lsap` flavor.

## Success Criteria

- Normal UAs are never modified.
- Known malformed ROM UAs are replaced before the ad SDK reads `http.agent`.
- Corrected UAs contain mutually compatible Android version and Build ID fields.
- Corrected UAs use `Haier TV` instead of a generic platform/model value.
- Behavior is deterministic and covered for every supported API level 23-36.
- No existing user change in `app/build.gradle` is modified or included in the design commit.

## References

- Android platform build-number reference: <https://source.android.com/docs/setup/reference/build-numbers>
- AOSP default Dalvik UA construction: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/com/android/internal/os/RuntimeInit.java#282>
