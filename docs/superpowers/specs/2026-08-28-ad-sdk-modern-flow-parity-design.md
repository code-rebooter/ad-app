# ad-sdk-modern Flow Parity Design

## Scope

Synchronize two shared-flow behaviors from `google_ad_tv_desktop` into
`ad-sdk-modern`. Do not change `ad-sdk-modern-no-ump` or copy app presentation
logic into the SDK.

## Pending CMP Action

When local UMP consent is still required, the SDK checks for a previously saved
terminal remote action before calling `consent-popup`. Only `ACCEPT_ALL`, `REJECT`,
and `SAVE_SETTINGS` are persisted. The SDK saves the action before starting the
local UMP operation and clears it only after that operation succeeds without an
error. A failed local operation therefore retries the same action on the next ad
session without requesting a new remote decision.

Invalid persisted values are removed and ignored. Preference read/write failures
must not crash or permanently block the ad flow; the SDK logs the failure and
continues with the normal remote-decision path.

## Per-Authorization Callback Timeout

The SDK parses `ad_callback_timeout_seconds`, with `callback_timeout_seconds` as
a compatibility alias, from a successful authorize response. Values in `30..600`
seconds become a per-session timeout override; missing, malformed, or out-of-range
values leave the `SdkConfig` timeout unchanged.

The timeout remains a deadline for the complete SDK session. It starts when the
session starts. When authorize returns an override, the SDK cancels the previous
timer and schedules only the remaining duration (`override - elapsed`). It does
not restart a full timeout from the authorize callback.

## Testing

- A source contract proves pending actions are read before `consent-popup`, saved
  before UMP execution, and cleared after local success.
- Parser tests cover both authorize field names and invalid ranges.
- Session tests use a controllable scheduler clock to verify remaining-deadline
  rescheduling and default-timeout preservation.
- Existing compile and module test commands remain the final regression gate.

