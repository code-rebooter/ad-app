# TCL 2.8.02 Concurrent Video Compatibility Design

## Background

Customer logs show that the TCL 2.8.02 video player requests `AUDIOFOCUS_GAIN`
through its bundled ExoPlayer `AudioFocusManager`. Android then sends
`AUDIOFOCUS_LOSS` to the third-party video application, which pauses playback.
Setting the ad volume to zero does not change this focus request.

After disabling TCL audio-focus management, a second customer log confirmed
that YouTube remained playing and the ad player's timeline advanced normally.
However, the ad picture stayed at the first frame. The ad decoder
`c2.allwinner.avc.decoder_1` repeatedly failed to dequeue output buffers while
the ad used a second `SurfaceView`; YouTube's first decoder continued to
render. This isolates the remaining failure to the Allwinner concurrent
hardware-video output path rather than playback state or audio focus.

## Requirements

- Ad playback must not pause or duck a third-party application's video.
- Apply the behavior to every app flavor that packages the TCL 2.8.02 SDK.
- Keep the existing `sound_mode` volume behavior.
- Do not use runtime reflection.
- Do not modify the vendor AAR files in `../tcl_ad_demo`.
- Keep YouTube and the TCL ad video rendering concurrently on affected
  Allwinner devices.

## Considered Approaches

For audio focus:

1. Patch Android `AudioManager.requestAudioFocus` calls. This is broad and can
   affect unrelated playback code, so it is not selected.
2. Patch ExoPlayer's `AudioFocusManager`. This stops the observed request, but
   changes a generic third-party class bundled in the AAR and is wider than
   necessary.
3. Patch TCL's own audio-focus configuration getter to return `false`. This
   uses the SDK's existing `setSupportAudioFocusManager(false)` path and is the
   selected approach.

For concurrent rendering:

1. Force software decoding together with a view change. This changes two
   variables and prevents the device test from identifying which change fixed
   the issue, so it is deferred.
2. Map TCL's requested `SurfaceView` type (`0`) to `TextureView` (`1`) at the
   SDK configuration boundary. This keeps the existing hardware decoder while
   avoiding the failing second Surface/BufferQueue output path and is selected.

## Design

Extend the existing `Hq008AarPatchTask`/`LsapClassPatcher` build-time pipeline.
For the SHA-256-pinned TCL 2.8.02 player AAR, replace the body of
`com/tcl/uniplayer/tuniplayer/b.w()Z` with a constant `false` return. The SDK
already reads this value while configuring each player and forwards it to
`setSupportAudioFocusManager(boolean)`. ExoPlayer therefore receives
`setAudioAttributes(attributes, false)` and does not request audio focus.

In the same player AAR, patch the actual obfuscated runtime class
`com/tcl/ff/component/uniplayer/f/k.setSurfaceType(IZ)V`. At method entry,
rewrite only parameter value `0` to `1`. Values `1` (TextureView) and `-1`
(no render view) remain unchanged. TCL's current Exo playback path uses
`playType=3`, so its later `playType==1` compatibility branch does not map the
TextureView request back to SurfaceView.

The player AAR produced by this task is already flavor-scoped to `hq008`,
`hq008XHSX`, `tcl_aishang`, `ad_ytx01`, `ad_album_101_001`, `hq008Noneu`,
`hq008Noneuc2`, and `tcl_poly`. No runtime bridge or per-channel source change
is required.

## Safety And Failure Handling

- Existing AAR SHA-256 validation prevents silently applying the patch to an
  unknown SDK build.
- Exact class names, method names, and descriptors keep both bytecode changes
  limited to TCL's player configuration boundary.
- The render-policy patch changes only the `0` input; other surface types
  remain unchanged.
- Other classes and other methods in the same classes remain unchanged.
- Patch metadata records both policies for diagnostics.

## Verification

- A buildSrc unit test generates the matching TCL configuration class shape,
  proves the original getter returns `true`, applies the patch, and verifies
  the getter returns `false` while unrelated methods remain unchanged.
- A second buildSrc unit test executes a generated matching player class before
  and after patching and verifies `0 -> 1`, `1 -> 1`, and `-1 -> -1`, while an
  unrelated class remains byte-for-byte unchanged.
- A flavor contract test verifies that every TCL 2.8.02 flavor consumes the
  patched player AAR.
- Build and inspect the patched player AAR, then build the customer Debug APK.
- On a target device, play YouTube first and display a muted ad. Logs must show
  no ad-process audio-focus request, YouTube must remain playing, and both
  videos must continue rendering.

## Out Of Scope

- Changing the vendor SDK version.
- Changing non-TCL advertising engines.
- Changing the existing `sound_mode` volume contract.
- Forcing the TCL player to use software decoding. This remains a fallback only
  if the TextureView device test still shows decoder-output failures.
