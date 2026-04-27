# Changelog

All notable changes to VseMoiOnline will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- **Backend + Android: member emancipation (self-payment detachment)**
  - Regular family members who pay for themselves are automatically detached from the original family and given a new family of their own
  - Payment page detects a regular member by device fingerprint and shows a warning: "До сих пор за вас платил другой пользователь" / "Произведя оплату, вы станете самостоятельным пользователем…"
  - Member's email is pre-filled in the payment form; a hidden `device_fingerprint` field identifies them correctly regardless of email typos
  - On payment confirmation, the member is detached atomically: remaining allocated days carry over to the new family; new paid days are credited on top; `member_emancipated` event is recorded in the original family's audit log
  - `emancipateMemberInTx()` in `families.js` zeros the account's `paid_until` before creating the canonical family to prevent stale allocation date seeding
  - `resolveAccount` in payment service uses `device_fingerprint` as highest-priority identity signal

- **Backend: emancipation event in family cabinet history**
  - `member_emancipated` events now appear in the original family's transaction history
  - Rendered as "Пользователь вышел из семьи: {name}" with carried-over days as a negative days label

- **Backend: `GET /get` — disposable-domain download landing page**
  - Download landing page served from the disposable backend domain (e.g. `vmonl.store/get`)
  - Matches the card-grid design of `website/index.html`: navy gradient block, three download-card articles with "Рекомендуем" badge, title, description, size pill, and download button
  - First card pre-selected (green button, navy text); clicking another card selects it; JS refreshes file sizes from `/api/public/downloads` on load
  - Responsive: single-column on screens narrower than 680 px
  - Logo links back to `vsemoi.online`; no redundant nav links

- **Backend: `family_role` field in `/status` response**
  - `/status` now returns `family_role: "owner" | "member" | null`
  - Android client saves it from each poll and uses it to select the correct UI branch

- **Android: share card for regular family members**
  - Regular members (paid for by another user) see a share card instead of "Подключите родных и близких"
  - Card: label row with share icon + "Рекомендуйте наш VPN друзьям"; below it a pill with the URL preview and a compact green "Копировать ссылку" button inline
  - Tapping the button copies `{backend_base_url}/get` to the clipboard and toasts "Ссылка скопирована"
  - `owner_cabinet` drawer item is hidden for regular members
  - New resources: `layout_share_card` (in `activity_main.xml`), `vsm_share_card_bg.xml`, `vsm_share_row_bg.xml`, `ic_vsm_share.xml`, string keys `vsm_share_label / vsm_share_copy / vsm_share_copied`

### Changed
- **Android: activation code section hidden for all paid users**
  - Previously shown for paid users with ≤ 3 days remaining; now always hidden when plan is paid
  - Paid users renew via the payment page; grant_days tokens are an operator-only action via the cabinet

- **Android: main-screen system bars, recovery flow copy, and drawer navigation polished**
  - Main screen now uses explicit system-bar handling that stays readable in both light and dark themes, including Android 15+ transparent status-bar behavior
  - Drawer menu reordered to: `Личный кабинет`, `Восстановить подписку`, `Telegram канал`, `Настройки приложений`, `Политика конфиденциальности`, `Исходный код`, `Лицензии открытого ПО`
  - Base string resources updated so the drawer and restore-subscription flow show Russian copy on the current build path

### Fixed
- **Android: FAB tap target, connecting-label flicker, and stale VPN service control hardened**
  - The visible FAB halo container now shares the power-button click listener, increasing the effective tap target beyond the inner icon button
  - Connect attempts now show `Подключение…` immediately and ignore repeated connect taps until success, failure, or VPN-permission cancellation
  - Idle FAB refreshes no longer overwrite `Подключение…` during provisioning/status polling
  - VPN service startup now reports explicit failure when core startup returns false, and start requests resync the UI if the core is already running
  - Service control now keeps a managed live service reference while the VPN/proxy service exists, making disconnect taps more reliable after screen-off/wake or memory pressure

- **Android: redundant legacy toasts removed from the main VPN flow**
  - Removed leftover V2rayNG start/stop/start-success/start-failure toasts and the provisioning retry toast that conflicted with the in-screen VseMoiOnline UI
  - Provisioning-specific config imports now use a dedicated silent path so the app no longer shows the stock `Success` / `Import 1 configurations` popups during connect
- **Android: first connect after reinstall/app reload no longer fails with `Select a configuration`**
  - Provisioning imports are now awaited before `startV2Ray()` runs, eliminating the race where the first tap could happen before the imported config became selected
- **Android: connection progress bar now stays in sync with the FAB state transition**
  - The top progress indicator remains visible through the short `CONNECTING` phase and is dismissed only when the VPN service reports its actual start result, removing the dead gap before the button turns red

### Added
- **Backend: owner cabinet UI — full server-rendered implementation**
  - Static HTML mockup (`cabinet-mockup.html`) built matching the dark navy website theme with glass-morphism nav, CSS design tokens from `website/style.css`, and all family management controls with JS stubs
  - `renderCabinetEntryPage`, `renderResultPage`, and `renderCabinetHome` replaced with the fully styled implementation
  - `GET /cabinet/logout` route added; clears the session cookie and redirects to `/cabinet`
  - `safeJson()` helper added to safely embed server-side JSON in `<script>` blocks (escapes `</script>`)
  - Event delegation via `data-action` attributes used throughout to avoid `${}` conflicts inside template literals
  - Login: two-step OTP flow (email → code), step 1 hidden once code is sent, resend affordance on step 2
  - Cabinet home: dark card layout with deposit pill, allocation-policy controls, distribute-evenly dialog (auto-policy checkbox), member cards with `+`/`−` day allocation, amber unallocated-days pill, transaction history (expandable)
  - Activation card shown for single-member view and per-member in multi-member view; monospace token display with ⧉ copy button; full activation URL with ⧉ copy button
  - Logo served from `/assets/logo.svg`; `<span>` site-name with tight letter-spacing matches static website exactly

- **Backend: device-existence-based token auto-minting**
  - `getAccountsWithDevices(accountIds)` helper added — queries `devices` table for any account that has a bound device
  - `buildFamilyContext` auto-minting loop now skips token creation when a device already exists for the account (regardless of owner/member role); previously the check was role-based and incorrectly re-minted tokens after a device was activated
  - Activation endpoint no longer rejects owner-role accounts

### Fixed
- **Backend: single-member cabinet showed no activation card**
  - `renderCabinetHome` single-member branch now renders an activation card section
  - `buildFamilyContext` previously excluded owners from token lookup and auto-creation; owners are now included in both

### Added
- **Backend + Android: single active device enforcement**
  - Backend `POST /provision` now returns 409 `paid_session_active` when another device on the same account was seen within `PAID_SESSION_LOOKBACK_MIN` minutes (default 5)
  - Android handles 409 with a dedicated `PaidSessionActiveException` — aborts the retry loop immediately and shows "VPN уже активно на другом устройстве" toast instead of the generic provisioning-failed dialog
  - Added `POST /provision/release` backend endpoint — sets `last_seen_at` to an hour in the past so another device can provision immediately without waiting for the lookback window to expire
  - Android calls `releaseProvisionSession()` fire-and-forget when the user taps the disconnect button, clearing the lock on the backend instantly

- **Backend: family deposit allocation policy and rebalance operations**
  - Added persisted `allocation_policy` on families (`manual` / `auto_distribute_evenly`) via migration `20260415133000-family-allocation-policy.js`
  - `GET /cabinet/api/family` now includes `allocation_policy` in the family summary
  - Payment/admin deposit credits honour the policy: single-member → auto-allocate; multi-member + manual → leave in deposit; multi-member + auto_distribute_evenly → rebalance immediately in the same transaction
  - Added `POST /cabinet/api/family/policy` — update allocation policy
  - Added `POST /cabinet/api/family/rebalance` — move N days between two members
  - Added `POST /cabinet/api/family/distribute-evenly` — rebalance entire pool evenly across all active members

- **Backend: member removal**
  - Added `POST /cabinet/api/family/members/:id/remove` — detaches the member, returns all their remaining allocated days to the family deposit, expires any pending activation tokens for their account, and downgrades their devices to free via gRPC

- **Backend: email-less family member slots**
  - `POST /cabinet/api/family/members` now accepts members without an email address — useful for adding a second personal device as a separate allocation slot
  - Email-less members are created with an anonymous account; `display_name` becomes required when email is omitted
  - The restore flow does not apply to email-less members; the owner re-issues an activation token from the cabinet if the device is replaced

- **Backend: deposit-first `+` allocation with richest-member fallback**
  - `POST /cabinet/api/family/members/:id/allocate` now attempts to take days from the family deposit first; if the deposit is insufficient, it automatically steals the shortfall from the active member with the most days (excluding the target), routing through the deposit as the accounting intermediary

### Fixed
- **Backend: `FOR UPDATE` on nullable outer join in `listActiveFamilyMembersForUpdate`**
  - PostgreSQL rejects `FOR UPDATE` applied to the nullable side of a `LEFT JOIN`; fixed by scoping the lock to `FOR UPDATE OF fm`
- **Android: misleading "VPN service is temporarily unavailable" dialog shown on concurrent-device block**
  - The dialog was shown because `PaidSessionActiveException` previously returned `false` from the provisioning cycle, which the caller interpreted as a generic failure; now the exception propagates and is caught specifically in `provisionThenConnect`, showing only the toast

### Changed
- **Backend: owner days now allocated via the same deposit mechanism as other members**
  - The prior restriction blocking `POST /cabinet/api/family/members/:id/allocate` for owner-role members was removed; owner days are taken from the deposit just like any member

### Added
- **Backend + Android: family-member activation claim flow**
  - Cabinet family API now exposes member activation tokens for claiming a device onto a family member account
  - Backend now distinguishes activation token purposes explicitly: `grant_days` and `claim_account`
  - Added backend `POST /activate/claim` so member activation can bind a real device without minting extra paid days
  - Family-backed member/device runtime was manually validated end to end through `/provision`, `/status`, `/servers`, and `/restore`
- **Backend: owner cabinet email-OTC entry flow**
  - Added backend-hosted `/cabinet` entry page for owner access
  - Added one-time code request/verify flow for cabinet login
  - Added short-lived cabinet sessions stored in DB
  - Added nginx proxying for `/cabinet` on `vsemoi.online`
- **Backend: public plans metadata endpoint and reusable branding asset**
  - Added `GET /api/public/plans` so static website pages can render plan pricing from the backend catalog
  - Added reusable backend branding files: `client-backend/src/assets/logo.svg`, `client-backend/src/lib/branding.js`, `client-backend/src/routes/assets.js`
  - Backend-rendered payment pages now load the logo from `/assets/logo.svg` instead of embedding one-off SVG markup
- **Android: native subscription recovery flow**
  - New `RestoreSubscriptionActivity` with backend-driven `send-code` and `verify` steps
  - Device fingerprint is sent automatically during verification; no manual device ID entry shown to the user
  - New recovery menu entry wired from the main app navigation
  - New recovery resources: dedicated layout, status chips, hero background, recovery cards, and localized copy in English and Russian
- **Backend: `GET /privacy-policy`** — Russian-language privacy policy served as mobile-friendly HTML with dark-mode support (`prefers-color-scheme`). Registered in `app.js`.
- **Backend: orphan device cleanup in `POST /provision`** — when an existing account is re-provisioned with a new `device_fingerprint` (reinstall / clear data), all prior device rows for that account are deregistered from Xray via gRPC and deleted from the DB before the new device is created.

### Changed
- **Android: activation deep link now claims the current device explicitly**
  - `MainActivity.handleActivateDeepLink()` now calls backend `POST /activate/claim`
  - The app sends the current `device_fingerprint` during activation instead of posting only the token
  - Successful activation now switches the app to paid immediately after the follow-up re-provision step, without needing an extra Connect press
- **Backend: cabinet-generated member activation URLs now prefer `CABINET_BASE_URL`**
  - Prevents cabinet pages opened on `vsemoi.online` from generating activation links that point at the wrong host when device-facing backend endpoints are served from `vmonl.store`
- **Android: cabinet access moved from main screen to drawer**
  - Removed the old `Личный кабинет` pill from the main subscription row
  - Added a new drawer item that opens the backend `/cabinet` page in the browser
  - `Оплатить подписку` / `Продлить подписку` now open `/payment`; drawer cabinet entry opens `/cabinet`
  - Cabinet URL fallback now resolves from `backend_base_url + /cabinet` if no cached `cabinet_url` is present
- **Backend: `cabinet_url` now points to the owner cabinet entry flow**
  - `/provision` and `/status` now return `.../cabinet` instead of reusing the payment page URL
  - Device identity is no longer treated as sufficient auth for full owner cabinet access
- **Payments / website: plan catalog is now the single source of truth**
  - `client-backend/src/lib/subscriptions.js` now carries both billing values and website-facing pricing metadata
  - `website/pay.html`, `website/index.html`, and `website/terms.html` now fetch `/api/public/plans` instead of hardcoding plan prices in multiple places
  - Fixed pricing drift across pages (for example, inconsistent savings copy on the 6-month plan)
- **Backend-hosted payment pages: theme-aware branded UI**
  - `client-backend/src/routes/payment.js` now supports `theme=dark|light`
  - `vmonl.store` defaults to dark theme, while other hosts default to light
  - Theme is preserved through `/payment`, `/payment/create`, `/payment/success`, `/payment/cancel`, and error screens
  - Payment page branding no longer exposes the disposable host name inside the page UI
  - Payment title changed from `Продление подписки` to neutral `Оплата подписки`
  - Logo tile now uses the same dark treatment in both themes; icon foreground size was increased for better readability
- **Android: payment cabinet URL now carries explicit theme hint**
  - `MainActivity.openCabinetUrl()` appends `theme=dark` or `theme=light` based on the current app theme
  - Existing query parameters are preserved; pre-existing `theme` is replaced instead of duplicated
- **Public website: mobile header and pricing table polish**
  - `website/style.css` mobile nav spacing tightened so the logo/header sits closer to the left edge on phones
  - Mobile pricing table now uses shorter headers (`Цена`, `По акции`) and shorter period labels (`1/3/6/12 мес.`)
- **Android: recovery screen aligned with the VseMoiOnline app shell**
  - Recovery screen now uses the same top app header/title as the main screen and respects the status-bar inset
  - Toolbar navigation now behaves honestly as a back action instead of a fake drawer opener
  - Recovery hero is full-width; step cards use explicit recovery palette values for stable light/dark readability
  - After Step 1 succeeds, the UI switches to a dedicated Step 2 state instead of stacking both cards
  - Added a `Send code again` / `Отправить код ещё раз` retry affordance for email-delivery retries
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
