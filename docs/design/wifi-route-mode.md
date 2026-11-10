# Wi-Fi route modes

## Experience

Settings > Network > Wi-Fi modes, disabled by default. Each entry contains an
exact, case-sensitive Wi-Fi name and Rule, Global or Direct mode. Add, edit and
remove entries; reject duplicates and empty names. Preserve significant spaces.
Do not interpret SSIDs as regex or shell patterns.

While connected to a matching physical Wi-Fi network, use its configured mode.
Otherwise use the normal Home/profile mode, including cellular, unknown SSID,
permission denial/revocation and system location disabled. Automatic choices
must not overwrite that saved default. Keep the VPN service connected.

Home must distinguish the effective automatic mode from the normal default.
While matched, Home mode buttons are disabled and the management row stays available.
Disable automation to edit the normal mode on Home. When no rule matches,
manual mode changes update the saved default and are serialized through the service.

## Runtime and privacy constraints

Reuse DefaultNetwork's selected physical network. Do not match any available
Wi-Fi while cellular actually carries engine traffic. Capabilities updates on
the same Network can supply or redact the SSID and must be considered without
repeating unrelated traffic-accounting/reset work.

On Android 12+, location-sensitive capabilities require
NetworkCallback.FLAG_INCLUDE_LOCATION_INFO. A synchronous
getNetworkCapabilities call returns redacted location data. Older-platform
fallbacks must validate the physical Wi-Fi association and invalidate stale
names immediately. Do not use network names as authentication or log them.

Explain access to the connected Wi-Fi name before requesting location access.
Request only when enabling this optional feature. Background operation needs
an explicit platform-appropriate permission flow; unknown identity must restore
the default. Do not scan surrounding networks or collect coordinates. Update
the public privacy notice with the actual implementation's behavior.

Configuration must use atomic cross-process persistence, following the existing
RouteModeStore contract. Runtime transitions must be serialized and tied to an
engine session; old asynchronous work must never change a later engine. Handle
startup, shutdown, rapid handover, settings edits and failed mode application.
Reuse existing mode-change connection cleanup policy.

## Validation bar

- Pure policy/store tests: matching, case, whitespace, duplicate rejection,
  Unicode, invalid files, disabled/unknown/cellular fallback, default restoration.
- Runtime tests: same-network SSID update, stale callback after handover/stop,
  manual versus automatic ordering, failed application, no old-session writes.
- UI tests: add/edit/delete, validation, permissions denied and permanently
  denied, location disabled, visible automatic/default mode attribution.
- Android unit suites, lint and build on the pinned SDK/toolchain.
- Emulator/physical Wi-Fi and background permission checks are distinct gates;
  report NOT_RUN rather than claiming they follow from unit tests.

## References and scope decision

- Surge Subnet Group: network condition -> policy with default fallback:
  https://manual.nssurge.com/policy-groups/subnet.html
- Surfboard: SSID/BSSID conditions and location permission explanation:
  https://getsurfboard.com/docs/location_permision/
- Clash Party 2.0.3: SSID-based subscription switching, a different action:
  https://github.com/mihomo-party-org/clash-party/releases
- Android callback location information and redaction:
  https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
- SFA Wi-Fi matching and Android location access:
  https://sing-box.sagernet.org/clients/android/features/

Scope: Rule/Global/Direct switching, not VPN start/stop or profile switching. The Home mode card contains the Auto badge, matching Wi-Fi name, management row and dynamic default restoration text. Healthy automation uses normal brand colors; unavailable and failed states are explicit. The normal mode controls are disabled while matched, with management available in the same card.

## Validation evidence

- Core and mobile unit suites: 222 and 195 tests, zero failures.
- Android lint and ARM64 debug APK/native library build: passed.
- API 36 ARM64 emulator Compose tests: Home provenance/management and settings
  add/edit/remove/duplicate-name validation. Home uses a synthetic connected
  state; these screenshots do not establish a real VPN connection.
- Physical Wi-Fi handover, OEM redaction, background location grant/revocation,
  and pre-31 platform behavior: NOT_RUN.
- New English and Simplified Chinese strings are authored; other existing
  catalogs carry English text pending native-language translation.
