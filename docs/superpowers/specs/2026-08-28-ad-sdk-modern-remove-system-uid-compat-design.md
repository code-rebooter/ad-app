# Remove ad-sdk-modern System UID Compatibility

## Scope

Remove customer-specific Android system UID (`uid=1000`) behavior from the shared
`ad-sdk-modern` module. Keep `ad-sdk-modern-no-ump` unchanged.

## Design

- Delete `SystemUidStorageCompat` and all standard-module call sites.
- Build IMA, Media3 data sources, and ExoPlayer with the controller's original context.
- Run the normal UMP flow and read normal consent preferences for every UID.
- Restore the standard module's lightweight `androidx.preference.PreferenceManager`
  implementation to its pre-system-UID behavior.
- Replace compatibility-positive tests with a source contract that rejects system-UID
  code in `ad-sdk-modern/src/main`.

## Verification

- The removal contract must fail before production changes and pass afterward.
- `ad-sdk-modern` must compile successfully.
- The standard module unit test suite must be run, with unrelated baseline failures
  reported separately.

