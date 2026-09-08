# Google TV Desktop V260904_1 Channel Design

## Goal

Add an independent Google TV desktop product flavor for the GAM customer ad unit
`/23316180870/ctv_01/v260904_1`. The flavor must reuse the existing Google TV
desktop implementation while keeping its package, backend channel, signing
identity, and GAM configuration routing separate from existing customers.

## Channel Configuration

| Field | Value |
| --- | --- |
| Gradle flavor | `google_ad_tv_desktop_v260904_1` |
| Backend channel ID | `GOOGLE_AD_TV_DESKTOP_V260904_1` |
| `BuildConfig.C_TYPE` | `GOOGLE_AD_TV_DESKTOP_V260904_1` |
| `BuildConfig.MODEL` | `GOOGLE_AD_TV_DESKTOP_V260904_1` |
| Application ID | `com.localvideoplayer.tv` |
| Version code | `1` |
| Version name | `1.0.1` |
| Primary/backup API domain | `https://api.bcytua.cc/` |
| Shared UID | empty |
| UMP App ID | `ca-app-pub-3199037222330432~8403613512` |
| Signing config | `localVideoPlayerRelease` from the sibling `GooglePlayApps` project |

The flavor uses the same source directories, manifest, Google IMA/Media3/UMP
dependencies, ProGuard behavior, and playback implementation as
`google_ad_tv_desktop`.

## Signing

Load signing values from the ignored file
`../GooglePlayApps/keystore-local-video-player.properties`. Resolve its
`storeFile` relative to the `GooglePlayApps` project directory. Do not copy the
keystore or its passwords into `AD_APP`.

Both debug and release variants of this flavor use that signing config, matching
the existing debug-signing override pattern for customer channels.

## GAM Backend Configuration

The APK does not hard-code the customer's VAST tag. It sends
`GOOGLE_AD_TV_DESKTOP_V260904_1` to
`POST /api/v2/ad/google-gam/resolve`; the backend returns the configured
`ad_tag_url`.

The initial backend tag is the customer-provided URL with the agreed package
parameter appended:

```text
https://pubads.g.doubleclick.net/gampad/ads?iu=/23316180870/ctv_01/v260904_1&description_url=[placeholder]&tfcd=0&npa=0&sz=400x300%7C640x480&gdfp_req=1&unviewed_position_start=1&output=vast&env=vp&impl=s&app_package=com.localvideoplayer.tv&correlator=
```

`correlator` remains empty in backend configuration so the IMA request path can
handle it at runtime. The initial test targets non-EEA traffic; this change does
not add new regional consent behavior. The existing desktop UMP App ID remains
in place until ad delivery is proven and consent requirements are revisited.

## Code Changes

1. Add the external `localVideoPlayerRelease` signing property loader and
   signing config to `app/build.gradle`.
2. Add a `google_ad_tv_desktop_v260904_1` source set that reuses
   `src/hq008` and `src/google_ad_tv_desktop`.
3. Add the product flavor with the configuration listed above.
4. Add its debug signing override and all Google desktop dependency scopes.
5. Classify the new flavor as a Google TV desktop/HQ008-family flavor in
   `BuildFlavor`.
6. Add a focused contract test covering flavor identity, signing, source reuse,
   dependencies, and family classification.

## Verification

Run the focused flavor contract test, build the debug variant, and inspect the
resulting APK to confirm:

- package name is `com.localvideoplayer.tv`;
- channel, cType, and model use `GOOGLE_AD_TV_DESKTOP_V260904_1`;
- the APK is signed with the Local Video Player upload certificate;
- the Google desktop manifest and IMA/Media3/UMP dependencies are included;
- existing user changes remain untouched.

Backend creation of the channel row and GAM URL is outside this repository and
must use the exact backend channel ID above.
