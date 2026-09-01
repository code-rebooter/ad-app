# TCL 2.8.02 Audio Focus Compatibility Design

## Background

Customer logs show that the TCL 2.8.02 video player requests `AUDIOFOCUS_GAIN`
through its bundled ExoPlayer `AudioFocusManager`. Android then sends
`AUDIOFOCUS_LOSS` to the third-party video application, which pauses playback.
Setting the ad volume to zero does not change this focus request.

## Requirements

- Ad playback must not pause or duck a third-party application's video.
- Apply the behavior to every app flavor that packages the TCL 2.8.02 SDK.
- Keep the existing `sound_mode` volume behavior.
- Do not use runtime reflection.
- Do not modify the vendor AAR files in `../tcl_ad_demo`.

## Considered Approaches

1. Patch Android `AudioManager.requestAudioFocus` calls. This is broad and can
   affect unrelated playback code, so it is not selected.
2. Patch ExoPlayer's `AudioFocusManager`. This stops the observed request, but
   changes a generic third-party class bundled in the AAR and is wider than
   necessary.
3. Patch TCL's own audio-focus configuration getter to return `false`. This
   uses the SDK's existing `setSupportAudioFocusManager(false)` path and is the
   selected approach.

## Design

Extend the existing `Hq008AarPatchTask`/`LsapClassPatcher` build-time pipeline.
For the SHA-256-pinned TCL 2.8.02 player AAR, replace the body of
`com/tcl/uniplayer/tuniplayer/b.w()Z` with a constant `false` return. The SDK
already reads this value while configuring each player and forwards it to
`setSupportAudioFocusManager(boolean)`. ExoPlayer therefore receives
`setAudioAttributes(attributes, false)` and does not request audio focus.

The player AAR produced by this task is already flavor-scoped to `hq008`,
`hq008XHSX`, `tcl_aishang`, `ad_ytx01`, `ad_album_101_001`, `hq008Noneu`,
`hq008Noneuc2`, and `tcl_poly`. No runtime bridge or per-channel source change
is required.

## Safety And Failure Handling

- Existing AAR SHA-256 validation prevents silently applying the patch to an
  unknown SDK build.
- Exact class name, method name, and descriptor matching keeps the bytecode
  change limited to the TCL audio-focus configuration getter.
- Other classes and other methods in the same class remain unchanged.
- Patch metadata records the disabled audio-focus policy for diagnostics.

## Verification

- A buildSrc unit test generates the matching TCL configuration class shape,
  proves the original getter returns `true`, applies the patch, and verifies
  the getter returns `false` while unrelated methods remain unchanged.
- A flavor contract test verifies that every TCL 2.8.02 flavor consumes the
  patched player AAR.
- Build the patched player AAR and representative TCL variants.
- On a target device, play YouTube first and display a muted ad. Logs must no
  longer contain an ad-process `requestAudioFocus()` event, and YouTube must
  remain in the playing state.

## Out Of Scope

- Changing the vendor SDK version.
- Changing non-TCL advertising engines.
- Changing the existing `sound_mode` volume contract.
