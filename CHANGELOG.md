# Changelog

All notable changes to VseMoiOnline will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- **Backend: `GET /privacy-policy`** — Russian-language privacy policy served as mobile-friendly HTML with dark-mode support (`prefers-color-scheme`). Registered in `app.js`.
- **Backend: orphan device cleanup in `POST /provision`** — when an existing account is re-provisioned with a new `device_fingerprint` (reinstall / clear data), all prior device rows for that account are deregistered from Xray via gRPC and deleted from the DB before the new device is created.

### Changed
- **Hamburger menu: source code link** → `https://github.com/dmitry-ulyanichev/vsemoionline` (was V2rayNG repo). Constant `VSM_APP_URL` added to `AppConfig.kt`.
- **Hamburger menu: Telegram channel** → `https://t.me/vsemoionline` (was `t.me/github_2dust`). Constant `VSM_TG_URL` added to `AppConfig.kt`.
- **Hamburger menu: privacy policy** → `https://vmonl.store/privacy-policy` (was Chinese CR.md from V2rayNG). Constant `VSM_PRIVACY_URL` added to `AppConfig.kt`.
- **Nav header redesigned**: height 160 dp → 120 dp; app name font `Display1` (34 sp) → 20 sp bold; background replaced from V2rayNG PNG with `@color/vsm_toolbar` (adapts light/dark); version text softened to 60 % white.
- **NavigationView styling**: `itemIconTint` → `@color/vsm_link` (theme-adaptive); `itemTextColor` → `?android:textColorPrimary`; background → `@color/vsm_surface`.

### Removed
- **3-dot overflow menu** — contained a single "Restart service" item irrelevant to end users. `onCreateOptionsMenu`, `onOptionsItemSelected`, and `import android.view.Menu` removed from `MainActivity.kt`; `menu_main.xml` emptied.

### Added
- **Animated toolbar and VPN area backgrounds**
  - Toolbar slowly cycles through navy/purple/wine shades (9 s half-cycle)
  - VPN area cycles through lavender/blush tints in light mode, deeper blues in dark (12 s half-cycle)
  - Seamless via `ValueAnimator.ofArgb` REVERSE repeat — no jump at loop ends

- **FAB redesign: power icon + halo pulse**
  - Single `ic_power` icon replaces separate play/stop icons
  - Idle (ready): green tint + faint green halo
  - Connected: dark red tint + pulsing halo (scale 1.0→1.35, alpha fade, 1200 ms loop)
  - Blocked (quota/days exhausted): grey tint + transparent halo; shows block reason instead of connecting

- **VPN status label** (`tv_vpn_status`)
  - Shows "Подключено" / "Нажмите для подключения" / "Подключение…"
  - Shows "Оплатите подписку" or "Трафик исчерпан" when connection is blocked

- **Connection blocking on quota/days exhaustion**
  - FAB tap blocked with toast when `paid_days_remaining == 0` or `traffic_remaining_gb <= 0`
  - Speed ring shows 0.0 in red when traffic exhausted
  - `effectiveDays` treated as 0 when paid + traffic exhausted (drives all urgency logic)

- **Subscription button animations**
  - "Продлить подписку" button pulses between red/bright-red (700 ms) when ≤ 3 days or < 10 % traffic
  - Wrapped in `btnPayContainer` for unified show/hide

- **Subscription header urgent pulse**
  - `tv_sub_value` text colour pulses urgently (700 ms) at ≤ 3 days remaining

- **Collapsed hint badge** (`tv_sub_traffic_hint`)
  - Appears when subscription block is collapsed and user is running low
  - Shows days remaining (urgent pulse) at ≤ 3 days; traffic % (warn colour) at 10–30 %; hidden otherwise

- **Chevron rotation animation on expand/collapse**
  - 200 ms rotation replaces text swap of "∨"/"∧"

- **Night-mode pill link background** for cabinet link (`pill_link_bg_dark` drawable)

- **App-open status refresh (rate-limited)**
  - `/status` polled on app open if ≥ 30 min since last successful poll
  - Prevents stale subscription display without hammering the server

- **Admin token issuance endpoint** (`POST /admin/token`)
  - Operator generates single-use activation tokens via HTTP without DB access
  - Body: `{ xray_uuid, days, expires_hours? }`; default expiry 48 h
  - Returns formatted token, raw token, and ready-to-send `activation_url`

- **Admin throttle endpoint** (`POST /admin/throttle`)
  - Operator updates free/paid tier throttle rate without SSH
  - Body: `{ tier, rate_kbps }`; proxies to tc-agent; protected by `ADMIN_SECRET`

- **nginx reverse proxy + TLS** (`infra/nginx/vmonl.store.conf`)
  - `vmonl.store` proxied to client-backend with HTTPS via Let's Encrypt / certbot
  - Deployed via `.github/workflows/nginx.yml`

- **`cabinet_url` wired to payment buttons**
  - Backend returns `cabinet_url` from both `/provision` and `/status`
  - Android saves it on every `/status` poll; "Оплатить подписку", "Продлить подписку", "Личный кабинет →" open it in the browser

### Fixed
- **Comparison table free-traffic cell now dynamic**
  - Was hardcoded string "25 ГБ"; now reads `PREF_TRAFFIC_TOTAL_GB` populated from `traffic_cap_mb` in `/status`

- **APK download returning 404**
  - `/opt/vsemoi` host directory was not mounted into the client-backend container; added volume mount in `docker-compose.yml`

### Added
- **Activation code UI polish**
  - "Ввести код активации" label above the 8-field code input row
  - Half-transparent grey × hint placeholders in each code field
  - "✓ Код успешно активирован" success flash (3 s auto-hide) shown in place of the input after a successful activation

- **tc-agent: live throttle read endpoint**
  - `GET /throttle/:port` returns `{ port, rate_kbps }` by parsing the live `tc class show` output for that port's classid
  - Enables the backend to read the actual applied rate rather than a static config value

- **tc-agent: bootstrap throttle on startup**
  - Throttle rules are now applied automatically at agent start via `FREE_TIER_PORT` / `FREE_TIER_RATE_KBPS` env vars (and optionally `PAID_TIER_PORT` / `PAID_TIER_RATE_KBPS`)
  - Survives reboots without manual curl intervention; values stored in `/etc/tc-agent.env`

- **Backend: live throttle_mbps in /status**
  - `/status` now queries tc-agent for the currently applied rate on the device's tier port (free: 8444, paid: 8445)
  - Falls back to `FREE_TIER_THROTTLE_MBPS` / `PAID_TIER_THROTTLE_MBPS` env defaults if tc-agent is unreachable
  - `TC_AGENT_URL` and `TC_AGENT_SECRET` env vars added to `client-backend`

### Changed
- **Activation section visibility**
  - Hidden for paid users with > 3 days remaining (subscription comfortable — no action needed)
  - Shown for free users and paid users with ≤ 3 days (renewal via code still relevant)
  - Managed by `updateSubBlock()` alongside the existing button visibility logic

### Fixed
- **Kotlin 2.x compile error in code field filters**
  - `String.toUpperCase()` was removed in Kotlin 2.0; replaced with `.toString().uppercase()` in all three InputFilter lambdas in `setupCodeFields()` and `handleCodePaste()`

- **Android UI Phase 1 — full subscription management screen**
  - Brand colour palette (20 vsm_ colours, dark-mode overrides) and Material Components theme
  - Russian UI strings throughout (subscription labels, server row, sub-block, comparison table, buttons)
  - Custom `DonutChartView` — canvas-drawn donut ring with ghost arc, centre text, configurable colours
  - Restructured `activity_main.xml`: subscription row, server row, CoordinatorLayout VPN area (FAB centred), collapsible "УПРАВЛЕНИЕ ПОДПИСКОЙ" block with donut charts, comparison table, pay/renew/family buttons, and 8-field activation code input
  - 8-field activation code input (`et_code_1`–`et_code_8`) with auto-advance on type, backspace-to-previous, clipboard paste validation (strips hyphens, validates 8 alphanumeric chars), submit button enabled only when all fields filled
  - `setupCodeFields()`, `handleCodePaste()`, `checkSubmitEnabled()` in `MainActivity.kt`
  - Status polling now populates traffic/speed donut charts and subscription header from SharedPreferences
  - `code_field_bg.xml` selector drawable: mint border when focused, muted border at rest

### Fixed
- **Stale free-tier config removed after upgrade**
  - After a free→paid upgrade, the old free VLESS config is now cleaned up on next app launch
  - `checkAndAutoProvision()` detects more than one server in MMKV and restores from the stored (paid) URI
  - Eliminates the two-toggle bug where toggling the wrong entry produced an EOF error

- **VPN tunnel auto-restarts after activation**
  - If the VPN was running when an activation deep link was processed, the tunnel now restarts automatically with the new paid config
  - Previously required the user to manually toggle off and back on to get working traffic

### Changed
- **Two-cycle provisioning for improved UX**
  - Cycle 1: Quick scan with 3-second timeout tries all URLs rapidly
  - Cycle 2: Patient retry with 10-second timeout for timed-out URLs only
  - User-friendly toast "Still trying to connect you, please wait..." shown between cycles
  - Improved perceived responsiveness: working endpoints found in 3s instead of 8s
  - Only timeout errors trigger Cycle 2 retry (HTTP errors are skipped)

- **Localized provisioning messages**
  - Added `provisioning_retry_message` string resource (English + Russian)
  - Added `provisioning_failed_message` string resource (English + Russian)
  - Messages automatically display in user's device language

### Changed (2026-04-05)
- **Provisioning fallback chain now applies to all devices, not just fresh ones**
  - Known devices (stored UUID) previously used a single URL with no fallback — a Cloudflare outage made reconnection impossible
  - Full two-cycle chain (last working URL → primary → Gist → other platforms) now runs for all provision paths
  - Core loop extracted into `runProvisionFallbackChain()` shared by all callers

- **VPN button no longer starts VPN when provisioning fails**
  - Previously the button turned green even on provision failure (UUID not registered → traffic silently dropped)
  - `startV2Ray()` now only called on successful provision; failure shows a dismissible dialog
  - Fresh-device VPN button tap now runs the full fallback chain (previously jumped straight to `startV2Ray()` → "Select a configuration" legacy toast)

- **Provisioning failure UX: toast → AlertDialog, message improved**
  - Error stays on screen until user dismisses (was a short-lived toast)
  - Background provisioning failure (app open) now silent — dialog only shown on explicit VPN button tap
  - Error message no longer blames the user's connection; now says service is temporarily unavailable and to contact support
  - New `provisioning_failed_title` string added in English and Russian

## [1.2.0] - 2026-01-05

### Added
- **Censorship-resistant provisioning fallback system**
  - Multi-tier provisioning URL fallback mechanism
  - Persistent storage of last successful provisioning URL
  - Automatic retry through multiple platform-hosted endpoints
  - Support for GitHub Gists, Cloudflare Workers, Vercel, Netlify, and more
  - 8-second timeout per provisioning attempt for faster failover

- **Duplicate provisioning prevention**
  - Added flag to prevent simultaneous provisioning attempts
  - Fixes issue where multiple server configs were created on first launch
  - Proper cleanup with `finally` block ensures flag is always reset

### Changed
- **Provisioning retry logic improved**
  - Sequential fallback through all configured URLs until first success
  - Better error logging for each failed attempt
  - Continues to next URL on failure instead of giving up
  - User-friendly error message when all provisioning URLs fail

### Security
- **Architecture decision: No VPN-based push updates**
  - Initially implemented VPN status API for pushing provisioning URL updates
  - Removed for security reasons after analysis of high-adversary threat model
  - Exposing additional ports creates discoverable infrastructure
  - Status API enables information leakage and traffic analysis
  - Final architecture relies on distributed platform URLs and local persistence

### Technical Details
- **Client-side** (MainActivity.kt):
  - Added `isProvisioningInProgress` flag to prevent race conditions
  - Added `tryProvisioningWithFallback()` with multi-tier URL priority
  - Added `fetchProvisioningEndpoint()` to fetch current endpoint from platform URLs
  - Changed `fetchAndImportConfig()` to return boolean for success/failure tracking

### Infrastructure
- **Fallback URL priority order**:
  1. Last successful provisioning URL (saved in SharedPreferences)
  2. Primary hardcoded URL
  3. Platform-hosted URLs (GitHub Gist, Cloudflare Workers, etc.)

- **Censorship resistance strategy**:
  - Distributed fallback system makes it harder to block all provisioning sources
  - Magic links (`vsemoionline://import?url=...`) as ultimate fallback
  - No exposed ports on VPN server beyond necessary VPN traffic
  - Minimal attack surface for infrastructure discovery

## [1.1.0] - 2026-01-04

### Added
- **Custom branding with launcher icons**
  - Custom app icon with foreground and background layers (`ic_launcher_foreground.png`, `ic_launcher_background.png`)
  - Icons deployed to all density folders (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
  - Adaptive icons properly configured for Android 8.0+

- **WireGuard-style inline VPN toggle**
  - Material Design toggle switch added directly in connection item layout
  - One-tap VPN control without floating action button
  - Switch state automatically syncs with VPN status
  - Fixed toggle state update issue after VPN permission grant

- **Localized connection display name**
  - English: "Turn VPN On/Off"
  - Russian: "Вкл./Выкл. VPN"
  - User-friendly instruction instead of technical name

- **Per-app proxy settings**
  - Restored to navigation drawer for banking app exclusions
  - Allows users to bypass VPN for specific apps (e.g., SberbankOnline)

- **Navigation drawer enhancements**
  - App version info displayed in header below app name
  - Direct access to Source code, Open source licenses, Telegram channel, Privacy policy
  - Removed redundant "About" screen - all items moved to main menu

### Changed
- **App name for F-Droid variant**
  - Changed from "v2rayNG (F-Droid)" to "Все Мои Онлайн"
  - Displays as "VseMoiOnline" on device home screen

- **Simplified connection list UI**
  - Removed technical details (IP address, port, protocol type)
  - Removed subscription indicator and statistics
  - Removed ping test results display
  - Removed action buttons (share, edit, delete, more options)
  - Clean single-line display with just connection name and toggle

- **Streamlined main toolbar**
  - Removed search/filter icon
  - Removed add config icon and submenu
  - Removed three-dot overflow menu
  - **Only** refresh icon (service restart) remains for troubleshooting

- **Minimized navigation drawer**
  - Removed: Subscription settings, Routing settings, User asset settings, General settings, Promotion, Logcat, Check for updates
  - Kept: Per-app proxy settings, Source code, Licenses, Telegram, Privacy policy

### Removed
- **From About screen**
  - Backup configuration feature
  - Share configuration feature
  - Restore configuration feature
  - Feedback feature
  - Entire About activity now bypassed - items moved to navigation drawer

- **From connection test results**
  - Country code display (security: prevents IP disclosure)
  - Server IP address display (security: prevents accidental sharing)
  - Only shows: "Success: Connection took Xms"

- **Floating action button (FAB)**
  - Hidden completely (`android:visibility="gone"`)
  - Replaced by inline toggle switches

### Fixed
- **Toggle switch state synchronization**
  - Switch now immediately updates to ON after granting VPN permission
  - Added `adapter.notifyDataSetChanged()` call in `isRunning` observer
  - Eliminates need to leave/return to app to see correct state

### Technical Details
- **Modified files**: 36 files changed
- **Code reduction**: 773 deletions, 162 additions (net -611 lines)
- **Layouts simplified**: `item_recycler_main.xml` reduced from 224 to 96 lines
- **Navigation cleaned**: `menu_drawer.xml` and `menu_main.xml` streamlined
- **Adapter enhanced**: WireGuard-style toggle logic in `MainRecyclerAdapter.kt`
- **String resources**: Localization support for English and Russian

### User Experience
- **Zero-configuration VPN** for non-technical users
- **One-tap connect/disconnect** with visual toggle
- **No confusing technical information** displayed
- **Banking app compatibility** via per-app proxy settings
- **Clean, minimal interface** focused on essential functions

## [1.0.0] - 2026-01-02

### Added
- **Magic link provisioning** via `vsemoionline://` URI scheme
  - Custom intent filter in AndroidManifest.xml to handle `vsemoionline://` links
  - `handleMagicLink()` method to process incoming magic links with `?url=` parameter
  - Automatic VLESS configuration download from backend server
  - Integration with v2rayNG's native `importBatchConfig()` for config parsing

- **Device identity management**
  - `getOrCreateDeviceId()` method generating persistent UUIDs
  - SharedPreferences-based storage for device IDs (`vsemoionline_prefs`)
  - Device ID appended to provision URLs for server-side device recognition
  - Privacy-friendly: no hardware identifiers used

- **Automatic provisioning with retry**
  - `checkAndAutoProvision()` triggered on every app resume
  - Checks for empty server list via `MmkvManager.decodeServerList()`
  - Retries on every resume until successful
  - No persistent "attempted" flag - resilient to network failures

- **Network capabilities**
  - Coroutine-based asynchronous HTTP downloads (`Dispatchers.IO`)
  - `HttpURLConnection` with 10-second connect/read timeouts
  - Progress spinner during config download
  - Error handling with user-facing toast messages

- **User experience improvements**
  - Zero-config VPN setup via single link click
  - Toast notifications for import success/failure
  - Integration with both cold start (`onCreate`) and hot resume (`onNewIntent`) flows
  - Seamless import using v2rayNG's existing UI

### Changed
- **Application identity**
  - Changed `applicationId` from `com.v2ray.ang` to `online.vsemoi`
  - Preserved `namespace` as `com.v2ray.ang` for BuildConfig compatibility
  - Updated app name from "v2rayNG" to "VseMoiOnline"

- **MainActivity lifecycle**
  - Modified `onCreate()` to call `handleMagicLink(intent)`
  - Modified `onResume()` to call `checkAndAutoProvision()`
  - Added `onNewIntent()` for handling magic links when app is running

### Technical Details
- **Backend integration**: FastAPI server with VLESS URI generation
- **Protocol**: VLESS + Reality (xtls-rprx-vision flow)
- **Config format**: Standard VLESS URI with Reality parameters
- **State persistence**: SharedPreferences for device UUID and provisioning flag
- **Logging**: All operations prefixed with "VseMoiOnline:" in logcat

### Build Information
- Based on v2rayNG 1.10.33 (version code 684)
- Target SDK: 36
- Min SDK: 24
- Kotlin JVM target: 17
- Build successful on 2026-01-02

## Upstream Base

Based on [v2rayNG](https://github.com/2dust/v2rayNG) version 1.10.33 (commit: master branch as of 2024-12-31).

### Preserved Upstream Features
- ✅ VLESS/VMess/Trojan protocol support
- ✅ Reality protocol support
- ✅ Manual configuration import (QR code, clipboard, file)
- ✅ Multiple server profiles
- ✅ Auto-reconnect
- ✅ Traffic statistics
- ✅ Routing rules
- ✅ Custom DNS
- ✅ All original v2rayNG functionality

### Compatibility
- Can be installed alongside original v2rayNG (different package ID)
- Fully compatible with v2rayNG server configurations
- No breaking changes to upstream features

---

## Future Roadmap

### Planned Features
- [ ] HTTPS backend support for secure provisioning
- [ ] Configurable backend URL in app settings
- [ ] Multi-server provisioning (receive multiple configs)
- [ ] Subscription update mechanism
- [ ] Custom launcher icons and branding
- [ ] In-app manual retry for failed provisioning

### Under Consideration
- [ ] QR code generation for invite links
- [ ] Backup/restore device UUID
- [ ] Server health monitoring
- [ ] Fallback server list
- [ ] Connection quality metrics

---
