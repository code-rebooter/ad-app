# Google IMA Parameter Coverage Design

## Goal

Make the existing Google IMA 3.35.1 patch effective for every `google_ad_tv_*`
variant, close the deterministic WebView user-agent gap, and make the remaining
Google Play services networking boundary explicit and mechanically verifiable.

## Scope

This change covers the eight variants that already consume the patched IMA AAR:

- `google_ad_tv_desktop`
- `google_ad_tv_desktop_jm`
- `google_ad_tv_desktop_ytx`
- `google_ad_tv_desktop_007`
- `google_ad_tv_desktop_v260904_1`
- `google_ad_tv_desktop_tpmaotai1935`
- `google_ad_tv_lockscreen`
- `google_ad_tv_lockscreen_hq002`

`hq006` and `hq003` remain outside this patch because they are separate product
flows and are not configured to consume the generated Google IMA AAR today.

The implementation will not modify the remote IMA Core JavaScript, rewrite the
host-provided ad tag, log full advertising or tracking URLs, or force-disable
the Google Play services GKS network backend.

## Design

### Flavor activation

`HaierUserAgentInstaller` will recognize all eight patched Google variants.
They will therefore use the same canonical UA normalization already used by the
existing LSAP channels. Tests will enumerate every supported Google variant and
will retain an unrelated flavor as the negative case.

### WebView UA enforcement

`Hq008XhsxAarRuntimeBridge` will apply the effective UA to
`webView.settings.userAgentString` immediately before every bridged WebView
navigation operation:

- one-argument `loadUrl`
- `loadUrl` with headers
- `postUrl`

The headers overload will continue to replace any caller-provided `User-Agent`
header. Applying the WebSettings value as well ensures that the initial IMA
loader request, child resources, `navigator.userAgent`, XHR, fetch, and later
navigations share the same UA.

The bridge will use one private helper for this operation so future navigation
entry points cannot diverge silently.

### Patched AAR contract

The patch pipeline will validate the pinned, real IMA AAR after rewriting it.
The validation will fail the build when required postconditions are absent:

- the Core WebView `loadUrl` call is redirected to the runtime bridge;
- the local native network backend redirects URL opening and UA header writes;
- the request-building classes use the normalized Android identity accessors;
- patch metadata records the preserved GKS selection as an explicit boundary;
- the source AAR hash and expected IMA version still match.

Focused ASM unit tests will cover the relevant transformations. The generated
AAR verification remains the integration-level guard against obfuscation or
upstream bytecode changes.

### GKS boundary

The existing `enableGks` selection remains unchanged. When IMA selects GKS, it
passes URL, method, and body to Google Play services and does not expose request
headers to the app bridge. The patched AAR metadata will state this policy, and
the verifier will assert that the selection was deliberately preserved.

Forcing all requests through `HttpURLConnection` requires a separate decision
because it changes Google networking, privacy, and anti-abuse behavior. It is
not necessary to fix the current flavor and WebView defects.

## Data Flow After The Change

1. The host and Media3 continue to pass `adTagUrl` unchanged.
2. IMA receives normalized Android identity values through patched accessors.
3. Before the Google IMA Core page loads, the bridge installs the normalized
   WebView UA.
4. Core JavaScript reads the same value through `navigator.userAgent` and may
   place it in `ua`, `useragent`, or supported tracking macro values.
5. Local native networking enforces the same UA header. GKS networking remains
   explicitly outside header enforcement.

## Error Handling And Privacy

Unsupported or blank original UA values continue through the existing
normalizer behavior; no ad URL is changed as a fallback. Patch verification
fails at build time rather than producing a partially patched AAR. Diagnostics
must contain only policy/version/count information and must never include full
ad tags, tracking URLs, advertising IDs, consent strings, or secure signals.

## Verification

Verification will use a red-green sequence and then run:

- focused UA flavor tests;
- focused runtime bridge contract tests;
- all `buildSrc` patcher tests;
- generation and validation of the pinned patched IMA AAR;
- `google_ad_tv_desktopDebug` Kotlin compilation;
- the Google desktop unit-test task, with any unrelated existing failures
  reported separately rather than hidden.

The completion report will distinguish guaranteed local behavior from the
remote IMA Core and GKS boundaries. It will not claim universal request-header
coverage without device traffic evidence.
