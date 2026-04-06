# Technical Implementation Notes

---

## Updates (2026-04-04) — §2.5 Orchestrator: Server Scaling (code complete)

### New files
- `orchestrator/src/providers/kamatera.js` — Kamatera Cloud API client (token cache, queue poll, provisionServer / terminateServer / listServers)
- `orchestrator/src/lib/placement.js` — `PlacementPolicy` class; provider-agnostic provisioning spec; designed for future multi-zone / multi-provider extension
- `orchestrator/src/lib/bootstrap.js` — SSH bootstrap runner (`ssh2`): uploads Xray config + tc-agent source via SFTP, runs `infra/bootstrap-xray.sh`
- `infra/bootstrap-xray.sh` — idempotent Ubuntu 24.04 VPN server setup script
- `orchestrator/src/lib/scaling.js` — scaling event handlers, capacity assessment (traffic headroom), device drain/reassign, `provisionNewServer` flow

### Modified files
- `orchestrator/src/app.js` — subscribed to `server.draining`, `server.exhausted`, `server.unreachable`, `server.healthy`
- `orchestrator/src/routes/admin.js` — added `POST /admin/provision-server`, `POST /admin/drain-server`, `GET /admin/capacity`
- `orchestrator/.env.example` — documented all new env vars (Kamatera creds, provisioning spec, SSH key path, bootstrap config)
- `orchestrator/package.json` — added `ssh2` dependency
- `infra/docker/orchestrator.Dockerfile` — copies bootstrap dependencies (config template, shell script, tc-agent source) into image
- `health-monitor/src/lib/traffic-checker.js` — `getServerGrpcAddr` now uses `server.xray_grpc_addr` from DB (NULL falls back to env for local Docker server)

### Migration
- `client-backend/migrations/20260404000000-add-kamatera-id.js` — adds `servers.kamatera_id VARCHAR(64)` and `servers.xray_grpc_addr VARCHAR(255)`

### Bootstrap script details
The `infra/bootstrap-xray.sh` script (run via SSH on fresh Ubuntu 24.04 servers) does:
1. Disables snapd and unattended-upgrades
2. Installs Docker, iproute2, netcat, Node.js (via NodeSource)
3. Configures Docker log rotation (10 MB × 3 files) and journald cap (50 MB)
4. Configures ufw: VPN ports open; gRPC (8080) and tc-agent (9000) restricted to `MANAGER_IP` only
5. Starts Xray container from `ghcr.io/xtls/xray-core:latest` with pre-generated `config.json`
6. Starts tc-agent as a systemd service

**Status**: code complete; manual test on a real Kamatera server required before automation is enabled.

---

## Updates (2026-04-05) — Provisioning resilience + error UX

### Root cause diagnosed: Cloudflare blocking
`vmonl.store` resolves to Cloudflare proxy IPs (`188.114.96.5/97.5`), not the VPS directly (`103.241.67.124`). After a redeploy, Cloudflare entered "Under Attack" mode and silently dropped all TCP connections — nginx was running fine but unreachable. Fix: pause+unpause in Cloudflare dashboard. Prevention: keep the GitHub Gist updated with the direct VPS IP so the fallback chain can bypass Cloudflare when it fails.

### Android: provisioning fallback chain extended to known devices
**File**: `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`

Previously, the two-cycle fallback chain (last working URL → primary URL → Gist → other platform URLs) was only used for fresh (unprovisioned) devices. Known devices (stored UUID) used a single URL with no fallback — so a Cloudflare outage made reconnection impossible even though the Gist could have provided a working endpoint.

**Changes**:
- Extracted core two-cycle loop into `runProvisionFallbackChain(): Boolean` (private suspend fun). All callers delegate to it.
- `provisionThenConnect()` (VPN button tap, known device) — now uses full chain instead of one-shot attempt.
- `checkAndAutoProvision()` (app open, known device) — falls through to full chain if primary URL fails.
- `tryProvisioningWithFallback()` (fresh device, first launch) — simplified to delegate to same chain.

### Android: VPN button no longer starts VPN when provisioning fails
**File**: `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`

Previously `provisionThenConnect()` always called `startV2Ray()` regardless of provision outcome. The button would turn green but traffic was rejected by Xray (UUID not registered). Now `startV2Ray()` is only called on success; on failure an `AlertDialog` is shown.

Fresh-device path also fixed: previously a null UUID caused an immediate `startV2Ray()` (→ "Select a configuration" legacy toast). It now runs the full fallback chain first.

### Android: provisioning error — dialog replaces toast, message improved
**Files**: `MainActivity.kt`, `values/strings.xml`, `values-ru/strings.xml`

- `toastError(R.string.provisioning_failed_message)` replaced with `showProvisioningFailedDialog()` — stays on screen until dismissed.
- Background provisioning failure (`tryProvisioningWithFallback` on app open) no longer shows any dialog — it fails silently. The user is only informed when they explicitly tap the VPN button.
- Message updated from "check your internet connection" (misleading — could be server-side) to "VPN service is temporarily unavailable… contact support if persists".
- New `provisioning_failed_title` string added in English ("Could not connect") and Russian ("Нет подключения").

---

## Updates (2026-04-05) — Russian plural fix for days remaining

### Modified files
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt` — replaced `getQuantityString(R.plurals.vsm_days_remaining, …)` with `formatDaysRu(n)`, a helper that hardcodes Russian plural rules (11–14 → дней; ends in 1 → день; ends in 2–4 → дня; else → дней). `getQuantityString` selects forms based on the device locale, so it produced wrong results on non-Russian-locale devices.
- `V2rayNG/app/src/main/res/values-ru/strings.xml` — added `vsm_days_remaining` plurals entry (kept for completeness; no longer used in the primary display path).

---

**Project**: VseMoiOnline (v2rayNG Fork)

This document contains detailed technical information for developers working on VseMoiOnline. For user-facing documentation, see README.md. For change history, see CHANGELOG.md.

---

## Updates (2026-04-03) — Subscription expiry + pre-connect provision

### Backend: paid_until replaces paid_days_remaining
**Files**: `client-backend/migrations/20260403130000-paid-until.js`, `activate.js`, `status.js`, `provision.js`

`accounts.paid_days_remaining` (static counter, never decremented) replaced with `paid_until TIMESTAMPTZ`.

**Why**: The old counter only changed when a token was activated or manually updated in the DB. It never counted down, so clients would see a stale number indefinitely.

**How it works now**:
- `/status` and `/provision` compute `days_remaining = Math.ceil((paid_until - now) / 86400000)`, clamped to 0. No cron job needed — the value decays automatically.
- Activation: `paid_until = GREATEST(COALESCE(paid_until, NOW()), NOW()) + N days`. Handles fresh accounts, active subscriptions (stacking), and expired accounts correctly.
- Android client receives the same `days_remaining` integer field — no client changes required.

**Migration**: existing `paid_days_remaining` values converted to `paid_until = NOW() + N days` at migration time.

### Android: pre-connect re-provision (provisionThenConnect)
**File**: `MainActivity.kt` — `provisionThenConnect()`, FAB click handler, `requestVpnPermission` callback

When the user taps the FAB to connect, `/provision` is called (3 s timeout) before `startV2Ray()`.

**Why**: When Xray restarts mid-session, the UUID is wiped from its in-memory list. The user notices traffic stops, disconnects, then reconnects. Without this, the reconnect would fail silently (Xray rejects the unregistered UUID at the VLESS protocol level — V2rayNG shows "connected" but no traffic flows). With this, the reconnect re-registers the UUID first.

**Behaviour**: on success, imports fresh VLESS URI and resets the cold-start rate-limit clock. On failure (backend unreachable), falls through to `startV2Ray()` with cached config — best effort.

---

## Updates (2026-04-03) — Provisioning robustness + speed dial fix

### Android: app-open re-provision replaces status poll
**File**: `MainActivity.kt` — `checkAndAutoProvision()`

The previous behaviour (status-poll-only on app open) was replaced with a full `/provision` call, rate-limited to 30 min via `PREF_LAST_STATUS_POLL_MS`.

**Why**: Xray's user list is in-memory. Any Xray restart (crash, redeploy, future server migration) wipes all registered UUIDs. Without a `/provision` call the client would attempt to connect with an unregistered UUID and fail silently. The status-poll-only approach also could not handle server reassignment (a different server IP in the VLESS URI).

**Behaviour**:
- Known device, elapsed ≥ 30 min: calls `/provision` with `PROVISION_TIMEOUT_QUICK_MS` (3 s). On success: fresh VLESS URI imported + status updated + timestamp saved. On failure: falls back to cached config so the user can still attempt a connection.
- Known device, elapsed < 30 min: re-imports VLESS URI from SharedPreferences into MMKV if MMKV was cleared; otherwise no-op.
- Fresh device (no stored UUID): unchanged — `tryProvisioningWithFallback()`.

The `/provision` endpoint for a returning device is idempotent (`addUser` "already exists" is swallowed), so calling it on every cold start is safe.

### Backend: speed dial fix for paid tier
**File**: `client-backend/.env.example`

`PAID_TIER_THROTTLE_MBPS` corrected from `0` to `25`. Setting it to `0` meant "no throttle" to the operator but was treated as a literal 0 Mbps fallback by `status.js` when the tc-agent has no tc class for the paid port (normal — paid users are unthrottled by tc). The Android client received `throttle_mbps: 0.0` and displayed "–" in the speed ring instead of 25 Mbps.

**Live fix**: change `PAID_TIER_THROTTLE_MBPS=0` → `25` in `/opt/actions-runner-vsemoi/env/client-backend.env` and `docker compose restart client-backend`.

---

## Recent Updates (2026-04-01) — UI animation pass

### Android: animated backgrounds
**File**: `MainActivity.kt` — `applyAnimatedBackgrounds()`, `animateBackground()`

`ValueAnimator.ofArgb(*colors)` with `REVERSE` repeat and `LinearInterpolator` applied to `binding.toolbar` (9 000 ms half-cycle, 5 navy-purple-wine keyframes) and `binding.mainContent` (12 000 ms, 5 lavender-blush keyframes for light / deeper blues for dark). Colors are hardcoded arrays in code rather than resources because they are intermediate interpolation waypoints, not semantic design tokens. `applyAnimatedBackgrounds()` is called at end of `onCreate`.

**File**: `VsmGradientBackground.kt` (new — unused; PoC for radial gradient blob backgrounds)

Standalone singleton `VsmGradientBackground.attach(view, baseColor, blobs)` that overrides the view's `Drawable` background with a `RadialGradient`-based custom drawable driven by per-blob `ValueAnimator`s. Each `BlobConfig` specifies colour, radius fraction, start/end X/Y fractions, and cycle duration. Soft-layer rendering enabled via `LAYER_TYPE_SOFTWARE`. Not wired into MainActivity yet — kept for Phase 2 richer backgrounds.

### Android: FAB power icon + halo pulse
**Files**: `MainActivity.kt` — `setFabConnected()`, `startFabPulse()`, `stopFabPulse()`, `refreshFabIdleAppearance()`; `drawable/ic_power.xml`, `drawable/fab_halo.xml`

`ic_power.xml` is a single path power-button vector, replacing the separate `ic_play_24dp` / `ic_stop_24dp`. `fab_halo` is a sibling View (large circle) positioned behind the FAB via FrameLayout layering.

`setFabConnected(true)` → dark red tint + semi-transparent red halo + `startFabPulse()`. `startFabPulse()` uses `ObjectAnimator.ofPropertyValuesHolder` (scaleX, scaleY, alpha) on `fabHalo`, 1200 ms REVERSE INFINITE with AccelerateDecelerate interpolator.

`refreshFabIdleAppearance()` → green tint + faint green halo if no block reason; grey tint + transparent halo if `connectionBlockedReason()` non-null. Sets `tv_vpn_status` text accordingly.

### Android: connection blocking
**File**: `MainActivity.kt` — `connectionBlockedReason()`

Returns localised string if `paid_days_remaining == 0` (reads `PREF_PAID_DAYS_REMAINING`) or `traffic_remaining_gb <= 0f` (reads `PREF_TRAFFIC_REMAINING_GB`). FAB click checks this first — shows toast and returns if blocked. Idle FAB appearance also reflects block state via `refreshFabIdleAppearance()`.

New string resources: `vpn_connected`, `vpn_disconnected`, `vpn_connecting`, `vpn_blocked_days`, `vpn_blocked_traffic`.

### Android: effectiveDays traffic-exhaustion logic
**File**: `MainActivity.kt` — `updateSubscriptionHeader()`, `updateSubBlock()`

`effectiveDays = if (plan == "paid" && trafficRemainingGb <= 0f) 0 else days`. Drives urgency colours, activation section visibility, and button state. Speed ring fraction is 0 + red colour when traffic exhausted; ghost arc hidden when exhausted.

### Android: subscription button pulse animations
**File**: `MainActivity.kt` — `updateSubButtons()`, `startRenewPulse()`, `stopRenewPulse()`

`updateSubButtons(plan, daysRemaining, trafficFraction)` replaces the inline visibility logic in `updateSubBlock()`. `btnPayContainer` (wraps btnPay) shown for free users; `btnRenew` shown for paid users with ≤ 3 days or < 10 % traffic; `btnFamily` shown otherwise. When `btnRenew` is shown, `startRenewPulse()` animates its text colour between `vsm_sub_urgent` and `vsm_sub_urgent_hi` (700 ms REVERSE INFINITE).

### Android: tv_sub_value urgent pulse
**File**: `MainActivity.kt` — `setSubValueUrgentPulse()`

Same 700 ms ValueAnimator on `tv_sub_value` text colour when urgency threshold hit (≤ 3 `effectiveDays`). Replaces the previous `AlphaAnimation`-based `startBlinking()` call. Alarm threshold widened from 1 day to 3 days.

### Android: collapsed hint badge
**File**: `MainActivity.kt` — `updateCollapsedHint()`; `activity_main.xml` — `tv_sub_traffic_hint`

Small badge shown inside the subscription header row when `layoutSubBody` is not visible. Three states: hidden (> 30 % traffic + > 3 days), warn (10–30 % traffic — orange text), urgent (< 10 % or ≤ 3 days — pulsing red). `updateCollapsedHint` called from both `toggleSubBlock()` and `updateSubBlock()` so it stays in sync on every data refresh.

### Android: chevron rotation on expand/collapse
**File**: `MainActivity.kt` — `toggleSubBlock()`; `drawable/ic_chevron_down.xml`

`binding.tvSubChevron.animate().rotation(...).setDuration(200).start()` replaces the text swap `"∨"`/`"∧"`. `tvSubChevron` changed from `TextView` to `ImageView` using the new vector drawable.

### Android: app-open status refresh (rate-limited 30 min) — superseded 2026-04-03
**File**: `MainActivity.kt` — `checkAndAutoProvision()`, `PREF_LAST_STATUS_POLL_MS`

Original implementation: when `serverList.size == 1`, triggered a `/status` poll if 30 min had elapsed. Superseded by the full re-provision approach documented above (2026-04-03), which covers status refresh as a side effect while also fixing Xray UUID registration and server reassignment.

### Android: cabinet link visibility
`tvSubLink` (Личный кабинет) is now hidden for free users (`View.GONE`) — it was always shown before. Night-mode variant uses `pill_link_bg_dark` drawable applied in `setupVsmUi()`.

### Android: server row arrow → ImageView
`tvServerArrow` changed from `TextView` to `ImageView` in layout. Tint set via `imageTintList` (green = paid, border grey = free).

### New drawables and resources
| File | Purpose |
|---|---|
| `drawable/ic_power.xml` | Power-button FAB icon |
| `drawable/fab_halo.xml` | Circular halo behind FAB |
| `drawable/fab_bg_go.xml` | Green gradient for idle FAB bg |
| `drawable/fab_bg_stop.xml` | Red gradient for connected FAB bg |
| `drawable/ic_chevron_down.xml` | Animated expand/collapse chevron |
| `drawable/ic_chevron_right_small.xml` | Server row arrow |
| `drawable/ic_arrow_right.xml` | Generic right-arrow |
| `drawable/pill_link_bg.xml` | Cabinet link pill (light) |
| `drawable/pill_link_bg_dark.xml` | Cabinet link pill (dark) |
| `drawable/btn_pay_bg.xml` | Pay button gradient background |
| `drawable/btn_submit_bg.xml` | Submit/renew button background |
| `drawable/divider_fade.xml` | Fade-out horizontal divider |
| `drawable-night/divider_fade.xml` | Night-mode divider variant |
| `drawable/ic_launcher_background_gradient.xml` | Gradient launcher icon bg |
| `drawable/ic_launcher_foreground_padded.xml` | Launcher foreground with padding |
| `values/colors.xml` | `vsm_sub_urgent_hi` (#FF5252), `vsm_pill_stroke` |
| `values/dimens.xml` | `fab_elevation_default` (8dp), `fab_elevation_pulse` (22dp) |
| `VsmGradientBackground.kt` | Radial blob gradient helper (PoC, not yet wired) |

---

## Recent Updates (2026-03-30) — Phase 1 Complete

### Android: comparison table free-traffic cell wired dynamically
**File**: `MainActivity.kt`, `activity_main.xml`

`tv_cmp_free_traffic` TextView in the comparison table now has an ID and is set programmatically in `updateSubBlock()` to `"${trafficTotalGb.toInt()} ГБ"` — driven by `PREF_TRAFFIC_TOTAL_GB` (populated from `traffic_cap_mb` in `/status`). Previously hardcoded to the string resource "25 ГБ". The paid column remains a static string resource — the backend does not return the paid cap to free users.

### Android: cabinet_url wired to payment/cabinet buttons
**Files**: `MainActivity.kt`; Backend: `provision.js`, `status.js`

Both `/provision` and `/status` now return `cabinet_url: "${CABINET_BASE_URL}/payment"` (env var `CABINET_BASE_URL=https://vmonl.store`). The Android `pollStatus` coroutine now saves `cabinet_url` from the `/status` response to `PREF_CABINET_URL` (previously only `/provision` saved it). "Оплатить подписку", "Продлить подписку", and "Личный кабинет →" all call `openCabinetUrl()` which reads `PREF_CABINET_URL` and opens it in the browser.

### Backend: POST /admin/throttle
**File**: `client-backend/src/routes/admin.js`

Protected by `x-admin-secret` header (`ADMIN_SECRET` env var). Body: `{ tier: "free"|"paid", rate_kbps }`. Resolves port from `FREE_TIER_PORT`/`PAID_TIER_PORT` env vars, proxies to tc-agent `POST /throttle`. Returns `{ ok, tier, port, rate_kbps }`. Returns 503 if `ADMIN_SECRET` unset (fail-closed).

### Backend: POST /admin/token
**File**: `client-backend/src/routes/admin.js`

Same `x-admin-secret` auth. Body: `{ xray_uuid, days, expires_hours? }` (default expiry 48h). Looks up `account_id` from `devices`, generates a Crockford Base32 token via `lib/token.js`, inserts into `tokens` table, returns `{ token, token_raw, activation_url, days, expires_at }`. `activation_url` is `${CABINET_BASE_URL}/activate/app?token=...` — ready to send to the user.

### Infrastructure: nginx reverse proxy replacing Caddy
**Files**: `infra/nginx/vmonl.store.conf`, `.github/workflows/nginx.yml`, `docker-compose.yml`

Caddy was ruled out because nginx was already running on the VPS managing other sites (ports 80/443 in use). `infra/nginx/vmonl.store.conf` proxies `vmonl.store` → `http://127.0.0.1:3000` with correct forwarding headers. TLS via certbot `--nginx`. Workflow deploys config to `/etc/nginx/sites-enabled/` and runs certbot on first deploy (idempotent thereafter). `CERTBOT_EMAIL` stored as GitHub repo secret.

### Infrastructure: APK volume mount
**File**: `docker-compose.yml`

Added `volumes: [/opt/vsemoi:/opt/vsemoi:ro]` to the `client-backend` service so the container can read `/opt/vsemoi/vsemoivpn.apk` from the host. `APK_PATH=/opt/vsemoi/vsemoivpn.apk` in `client-backend.env`.

---

## Recent Updates (2026-03-29) — Phase 1.6: Xray gRPC traffic stats wired into /status

### Backend: traffic_consumed_mb in /status
**File**: `client-backend/src/routes/status.js`

`getUserTrafficBytes(xrayUuid)` is now called in the `/status` route. Returns uplink + downlink bytes from Xray `StatsService/GetStats`. Converted to MB (÷ 1 048 576) and returned as `traffic_consumed_mb`. gRPC NOT_FOUND (code 5) is silently treated as zero — normal for a user who hasn't sent any traffic yet. Any other gRPC failure logs a warn and falls back to 0 so the route doesn't break.

Backend tier cap defaults corrected: `FREE_TIER_TRAFFIC_CAP_MB` 5 000 → **25 000**, `PAID_TIER_TRAFFIC_CAP_MB` 100 000 → **250 000** (matching the Android UI display values of 25 GB / 250 GB).

### Android: traffic cap read from /status response
**File**: `MainActivity.kt`

`pollStatus` now reads `traffic_cap_mb` from the JSON response (÷ 1000 → GB) and saves it as `PREF_TRAFFIC_TOTAL_GB`. Falls back to 25 GB / 250 GB if the field is absent (old server). `updateSubBlock` reads `PREF_TRAFFIC_TOTAL_GB` from prefs instead of hardcoding the value. Log line extended to include `cap=XXgb` for easier verification.

---

## Recent Updates (2026-03-29) — Throttle display + activation UI polish

### Kotlin 2.x compile fix
`String.toUpperCase()` was removed in Kotlin 2.0 (not just deprecated). Three occurrences in `setupCodeFields()` and `handleCodePaste()` replaced with `.toString().uppercase()`. The explicit `.toString()` call is required because the InputFilter lambda's type context prevents the compiler from resolving `.filter {}` as `String` — without it, `.uppercase()` is also "Unresolved reference".

### Activation code UI additions
**File**: `layout/activity_main.xml`, `values/strings.xml`
- Added `TextView` ("Ввести код активации", 13sp, secondary text colour) above the code input row.
- Added `android:hint="×"` + `android:textColorHint="#80AAAAAA"` to all 8 `EditText`s.
- Added `tv_code_success` TextView ("✓ Код успешно активирован", mint colour, initially `GONE`) above the activation section — shown for 3 s after a successful activation, then auto-hides via `postDelayed`.
- Wrapped the label + fields row in `layout_activation_section` (`LinearLayout`, vertical) for unified show/hide.

### Activation section visibility logic
**File**: `MainActivity.kt` — `updateSubBlock()`

Activation section visibility now tracks plan and days:
- `plan == "free"` → `VISIBLE`
- `plan == "paid"` && `days ≤ 3` → `VISIBLE` (renewal still relevant)
- `plan == "paid"` && `days > 3` → `GONE`

Success flash triggered in `handleActivateDeepLink` success branch (after `updateSubBlock` has already hidden the section — the flash re-surfaces briefly without re-showing the fields).

### Backend: live throttle_mbps in /status
**Files**: `xray-agent/agent.js`, `client-backend/src/routes/status.js`, `docker-compose.yml`

#### tc-agent — new `GET /throttle/:port` endpoint
Parses `tc class show dev {IFACE}` for the classid matching the port (port in hex = classid minor). Returns `{ port, rate_kbps }` or `{ port, rate_kbps: null }` if no rule is set. `parseClassRate()` helper handles `Kbit`/`Mbit`/`Gbit`/`bit` unit variants.

tc-agent changed from binding `127.0.0.1` to `0.0.0.0` so Docker containers on the host's bridge network can reach it.

#### status.js — live throttle query with fallback
`fetchTcThrottle(port)` makes a `GET /throttle/{port}` call to tc-agent with a 2 s timeout. On any failure (unreachable, timeout, bad JSON) it returns `null` and the route falls back to the `FREE/PAID_TIER_THROTTLE_MBPS` env default. `TIER_CONFIG` extended with `port` field; `TC_AGENT_URL` and `TC_AGENT_SECRET` read from env.

#### Docker networking
The client-backend container runs on the custom `vsemoionline-backend_default` bridge (gateway `172.19.0.1`), not `docker0` (`172.17.0.1`). Required changes:
- `TC_AGENT_URL=http://172.19.0.1:9000` in `client-backend.env`
- iptables rule on the host: `iptables -I INPUT -i br-3416f66db0c3 -p tcp --dport 9000 -j ACCEPT`
  (bridge interface name derived from `docker network inspect vsemoionline-backend_default`)

**Note for multi-server production**: `TC_AGENT_URL` is currently a single env var pointing to the local tc-agent. When multiple Xray VPSes are added, `status.js` should look up the tc-agent URL from `servers.tc_agent_url` in the DB using the device's `assigned_server_id`, rather than a global env var.

---

## Recent Updates (2026-03-29)

### Android UI Phase 1 — Subscription Management Screen

Complete implementation of the VseMoiOnline subscription UI (section 1.11). Single source of truth for visual decisions: `ui_mockup_final.html` (14 screens, free/paid × light/dark).

#### Files added
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/DonutChartView.kt` — canvas-drawn donut ring. Properties: `fraction`, `ringColor`, `ringBgColor`, `centerValue`, `centerSub`, `subTextColor`, `showGhostArc`, `ghostColor`, `ghostAlpha`. Ghost arc spans from `fraction×360°` to `360°` (BUTT cap). Main arc starts at −90°, ROUND cap (BUTT when fraction ≥ 1.0). Centre text: 17sp bold + 10sp, vertically centred as two-line block.
- `V2rayNG/app/src/main/res/drawable/code_field_bg.xml` — selector: 2dp mint stroke when focused, 1dp vsm_border otherwise.

#### Files modified
- `values/colors.xml` — 20 vsm_ brand colours added; upstream colours untouched.
- `values-night/colors.xml` — 10 dark-mode overrides appended.
- `values/strings.xml` — all Russian UI strings (subscription labels, server row, sub-block title, chart labels, comparison table, button labels, plurals for days remaining).
- `values/themes.xml` + `values-night/themes.xml` — parent changed to `Theme.MaterialComponents.DayNight`; `colorPrimary` → `vsm_link` (#1565C0); `colorPrimaryDark` + `statusBarColor` → `vsm_toolbar`.
- `menu/menu_main.xml` — `service_restart` moved to overflow (`showAsAction=never`).
- `layout/activity_main.xml` — full restructure. Kept all original IDs for binding compatibility (tab_group, recycler_view, layout_test set to GONE/0dp height). New structure: AppBarLayout(Toolbar with Dark.ActionBar overlay) → subscription row (42dp) → server row (46dp) → CoordinatorLayout VPN area (weight=1, FAB centred at 88dp) → collapsible sub-block. Sub-block body: two DonutChartViews (96×96dp), comparison table (LinearLayout border trick), pay/family/renew buttons, 8-field code input row.
- `MainActivity.kt` — new SharedPrefs constants (PREF_PAID_DAYS_REMAINING, PREF_TRAFFIC_REMAINING_GB, PREF_THROTTLE_MBPS, PREF_CABINET_URL); new methods: `setupVsmUi`, `setupCodeFields`, `handleCodePaste`, `checkSubmitEnabled`, `submitActivationCode`, `toggleSubBlock`, `openCabinetUrl`, `onServerRowTapped`, `updateSubscriptionHeader`, `startBlinking`, `updateServerRow`, `updateSubBlock`, `trafficRingColor`, `formatGb`; `pollStatus` extended to parse and persist days_remaining/traffic/throttle; immediate `pollStatus` call after paid activation to avoid "0 дней" blink.

#### Key design notes
- `tab_group`, `recyclerView`, `layoutTest` kept in layout as GONE — preserves all existing ViewModel and adapter wiring without refactor.
- Traffic ring colour thresholds: >50% green · 25–50% amber · 10–25% orange · <10% red.
- Speed ring: blue (free) / mint (paid). Free users get ghost arc at 22% opacity (light) / 30% (dark) showing paid-tier potential.
- Free user sub-block: charts + comparison table + "Оплатить подписку" + code input.
- Paid user sub-block: charts + "❤ Подключите родных" (normal) or "Продлить подписку" (≤3 days) + code input.
- Comparison table borders implemented without custom drawables: parent `LinearLayout` with `vsm_border` background + 1dp padding + 1dp `View` separators between rows and columns.

---

## Recent Updates (2026-03-27)

### Activation Flow Fixes

#### 1. Stale free-tier config removed after upgrade (`checkAndAutoProvision`)
**File**: `MainActivity.kt`

**Problem**: After a free→paid upgrade, the old free VLESS config remained in the server list alongside the new paid one. The app showed two toggles; toggling the wrong one produced an EOF error.

**Root cause**: `checkAndAutoProvision` returned immediately if MMKV contained any servers (`isNotEmpty()`), so a stale free config was never cleaned up.

**Fix**: On startup, if MMKV contains more than one server (unexpected for VseMoiOnline), remove all configs and re-import from the stored `PREF_VLESS_URI` (which holds the most recently provisioned URI).

```kotlin
if (serverList.size > 1) {
    // Multiple configs — stale entries from a previous free→paid upgrade. Clean up.
    val storedVlessUri = prefs.getString(PREF_VLESS_URI, null)
    if (storedVlessUri != null) {
        mainViewModel.removeAllServer()
        importBatchConfig(storedVlessUri)
        return
    }
    mainViewModel.removeAllServer()
    // fall through to full provisioning
} else if (serverList.size == 1) {
    return
}
```

#### 2. VPN tunnel auto-restarts after activation (`handleActivateDeepLink`)
**File**: `MainActivity.kt`

**Problem**: If the VPN was running (on the free config) when the activation deep link fired, the new paid config was imported but the running tunnel was not restarted. The UI showed "Connected" but traffic failed until the user toggled off and back on.

**Fix**: After a successful re-provision following activation, call `restartV2Ray()` if the VPN is currently running.

```kotlin
if (success) {
    toastSuccess("Подписка активирована!")
    if (mainViewModel.isRunning.value == true) restartV2Ray()
}
```

### Modified Files (2026-03-27)
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt` — both fixes above

---

## Recent Updates (2026-01-07)

### Two-Cycle Provisioning for Improved UX

**Objective**: Reduce perceived wait time during provisioning while maintaining robustness for slow/unreliable connections.

**Problem**: Single 8-second timeout per URL caused poor UX:
- Working endpoints took 8s to respond even if available in 2s
- Multiple blocked endpoints resulted in 24+ second hangs with no feedback
- App appeared frozen to non-technical users

**Solution**: Two-cycle provisioning strategy with differential timeouts.

**Key Changes**:

#### 1. Two-Cycle Timeout Strategy
- **Cycle 1 (Quick scan)**: 3-second timeout per URL
  - Tries all URLs sequentially
  - Catches fast-responding endpoints quickly
  - Distinguishes between timeouts and HTTP errors
  - Only timeouts added to retry list

- **Cycle 2 (Patient retry)**: 10-second timeout per URL
  - Only retries URLs that timed out in Cycle 1
  - Skips URLs that returned HTTP errors (non-recoverable)
  - Shows user-friendly toast before starting: "Still trying to connect you, please wait..."
  - Provides more time for genuinely slow but functional connections

#### 2. Timeout Constants
**File**: `MainActivity.kt:60-62`

```kotlin
// Two-cycle provisioning timeouts for better UX
private const val PROVISION_TIMEOUT_QUICK_MS = 3000   // Cycle 1: quick scan
private const val PROVISION_TIMEOUT_PATIENT_MS = 10000 // Cycle 2: patient retry
```

Replaced single `PROVISION_TIMEOUT_MS = 8000`.

#### 3. Enhanced Error Handling
**File**: `MainActivity.kt:345-415`

Modified `tryProvisioningWithFallback()` to:
- Catch `SocketTimeoutException` specifically in Cycle 1
- Track timed-out URLs separately from HTTP errors
- Re-throw exceptions from helper methods for proper timeout detection
- Show localized toast message between cycles

#### 4. Parameterized Helper Methods
**Files**:
- `fetchProvisioningEndpoint(platformUrl: String, timeoutMs: Int)` - line 439
- `fetchAndImportConfig(baseUrl: String, timeoutMs: Int)` - line 479

Both methods now accept `timeoutMs` parameter and re-throw exceptions to enable timeout detection in caller.

#### 5. Localized User Feedback
**Files**:
- `res/values/strings.xml:409-410`
- `res/values-ru/strings.xml:403-404`

Added string resources:
```xml
<!-- English -->
<string name="provisioning_retry_message">Still trying to connect you, please wait...</string>
<string name="provisioning_failed_message">Unable to connect to provisioning service. Please check your internet connection.</string>

<!-- Russian -->
<string name="provisioning_retry_message">Всё ещё пытаемся подключиться, пожалуйста, подождите...</string>
<string name="provisioning_failed_message">Не удалось подключиться к серверу. Пожалуйста, проверьте подключение к интернету.</string>
```

### Performance Characteristics

**Best case** (working endpoint responds quickly):
- **Before**: 8 seconds (waited full timeout)
- **After**: 2-3 seconds (actual response time)

**One URL blocked** (timeout), others work:
- **Before**: 8s + 2s = 10 seconds
- **After**: 3s + 2s = 5 seconds

**All URLs blocked** (all timeout):
- **Before**: 3 URLs × 8s = 24 seconds (no feedback)
- **After**:
  - Cycle 1: 3 URLs × 3s = 9 seconds
  - Toast: "Still trying to connect you, please wait..."
  - Cycle 2: 3 URLs × 10s = 30 seconds
  - **Total**: ~40 seconds with user feedback

**HTTP errors** (401, 404, 500):
- **Before**: Failed immediately, no retry
- **After**: Same behavior (not retried in Cycle 2)

### User Experience Improvements

1. **Faster success path**: Working endpoints found in ~3s vs 8s
2. **Visual feedback**: Toast message prevents "app is frozen" perception
3. **Localized messages**: Automatic language selection for Russian users
4. **Intelligent retry**: Only timeouts retried, not permanent HTTP errors
5. **Bounded worst case**: Still completes within ~40s even if all URLs timeout

### Modified Files (Two-Cycle Provisioning - 2026-01-07)
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt` - Two-cycle logic
- `V2rayNG/app/src/main/res/values/strings.xml` - English messages
- `V2rayNG/app/src/main/res/values-ru/strings.xml` - Russian translations

---

## Recent Updates (2026-01-05)

### Censorship-Resistant Provisioning System

**Objective**: Enable clients to automatically provision VPN configs even when primary provisioning domain is blocked by censors. Implement multi-tier fallback mechanism with distributed platform-hosted endpoints.

**Threat Model**:
- DNS/IP blocking of provisioning domain
- DNS/IP blocking of VPN server
- Both provisioning and VPN server blocked simultaneously
- Port scanning and infrastructure discovery by censors
- Traffic analysis and information leakage

**Key Changes**:

#### 1. Multi-Tier Fallback System
- **Last working URL** (Priority 1): Client tries previously successful URL first
- **Primary URL** (Priority 2): Hardcoded provisioning endpoint
- **Platform URLs** (Priority 3): GitHub Gists, Cloudflare Workers, Vercel, etc.
- Each attempt has 8-second timeout for fast failover
- Sequential retry stops at first success

#### 2. Duplicate Prevention
- Added `isProvisioningInProgress` flag
- Prevents race conditions when `onResume()` called multiple times
- Ensures only one provisioning attempt runs at a time

#### 3. Security Architecture Decision
**VPN-based update push mechanism was initially implemented but removed for security reasons:**
- Exposing additional ports on VPN server creates discoverable infrastructure
- Status API enables information leakage to censors monitoring responses
- Creates DDoS attack surface and traffic analysis vectors
- Risk of preemptive blocking when censors learn new provisioning URLs

**Final architecture relies solely on:**
- Distributed platform fallback URLs (harder to block all simultaneously)
- Local persistence of last working URL
- Magic links for emergency distribution (`vsemoionline://import?url=...`)

### Modified Files (Censorship Resistance - 2026-01-05)
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt` - Multi-tier fallback logic
- `.gitignore` - Exclude abandoned provisioning-update-backend/

### Implementation Details

#### Client-Side Fallback System
**File**: `MainActivity.kt:56-75`

Added companion object constants for fallback configuration:
```kotlin
companion object {
    private const val PREFS_NAME = "vsemoionline_prefs"
    private const val PREF_LAST_WORKING_URL = "last_working_provisioning_url"
    private const val PROVISION_TIMEOUT_MS = 8000 // 8 seconds per attempt

    private const val PRIMARY_PROVISION_URL = "http://103.241.67.124:8888/provision"

    private val FALLBACK_PLATFORM_URLS = listOf(
        "https://gist.githubusercontent.com/.../endpoint.txt"
        // GitHub, Cloudflare Workers, Vercel, etc.
    )
}
```

#### Fallback Logic Implementation
**File**: `MainActivity.kt:307-377`

The `tryProvisioningWithFallback()` method:
1. Checks `isProvisioningInProgress` flag to prevent duplicates
2. Builds URL list in priority order (last working → primary → platforms)
3. Iterates through URLs sequentially with 8-second timeout each
4. For platform URLs, calls `fetchProvisioningEndpoint()` to get actual URL
5. Calls `fetchAndImportConfig()` for each URL until success
6. Saves successful URL using `saveLastWorkingProvisionUrl()`
7. Clears flag in `finally` block to ensure cleanup

**Platform URL Resolution**:
Platform URLs return plain text like:
- `103.241.67.124:8888` (IP:port format)
- `provision.example.com` (domain format)
- `https://provision.example.com/provision` (full URL)

The client constructs the full provisioning URL from this response.

#### Duplicate Prevention
**File**: `MainActivity.kt:103`

Added instance variable:
```kotlin
private var isProvisioningInProgress = false
```

Used in `tryProvisioningWithFallback()`:
```kotlin
if (isProvisioningInProgress) {
    Log.i(AppConfig.TAG, "Provisioning already in progress, skipping")
    return
}
isProvisioningInProgress = true
try {
    // ... provisioning logic ...
} finally {
    isProvisioningInProgress = false
}
```

### Censorship Resistance Coverage

| Scenario | Primary | Last URL | Platforms | Magic Link |
|----------|---------|----------|-----------|------------|
| Normal ops | ✅ | ✅ | ➖ | ➖ |
| Primary blocked | ❌ | ✅ | ✅ | ✅ |
| Provision blocked, VPN works | ❌ | ❌ | ✅ | ✅ |
| Both blocked | ❌ | ❌ | ✅ | ✅ |
| All infrastructure blocked | ❌ | ❌ | ❌ | ✅ |

**Magic Link** (`vsemoionline://import?url=...`) remains the ultimate fallback, distributable via Telegram, email, or messaging apps.

**Note**: VPN-based update push was considered but rejected to avoid exposing additional ports and creating information leakage vectors.

---

## Recent Updates (2026-01-04)

### UI/UX Redesign for Non-Technical Users

**Objective**: Transform v2rayNG into a simple, one-tap VPN solution suitable for family members with no technical knowledge.

**Key Changes**:
1. Custom branding with app-specific icons
2. WireGuard-style inline toggle switches
3. Removed all technical information from UI
4. Streamlined navigation to essential features only
5. Localized connection names for better UX

### Modified Files (UI Redesign)
- `V2rayNG/app/src/fdroid/res/values/strings.xml` - App name
- `V2rayNG/app/src/main/res/values/strings.xml` - Connection name strings
- `V2rayNG/app/src/main/res/values-ru/strings.xml` - Russian translations
- `V2rayNG/app/src/main/res/layout/item_recycler_main.xml` - Connection list item
- `V2rayNG/app/src/main/res/layout/nav_header.xml` - Navigation header
- `V2rayNG/app/src/main/res/layout/activity_main.xml` - Main activity (FAB hidden)
- `V2rayNG/app/src/main/res/layout/activity_about.xml` - About screen (gutted)
- `V2rayNG/app/src/main/res/menu/menu_drawer.xml` - Navigation menu
- `V2rayNG/app/src/main/res/menu/menu_main.xml` - Toolbar menu
- `V2rayNG/app/src/main/res/mipmap-*/ic_launcher*.png` - Custom icons (all densities)
- `V2rayNG/app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` - Adaptive icons
- `V2rayNG/app/src/main/res/drawable/ic_refresh_24dp.xml` - Refresh icon (new)
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt` - Navigation handlers
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainRecyclerAdapter.kt` - Toggle logic
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/AboutActivity.kt` - Simplified
- `V2rayNG/app/src/main/java/com/v2ray/ang/handler/V2RayServiceManager.kt` - Test result

### Modified Files (Auto-Provisioning - 2026-01-02)
- `V2rayNG/app/build.gradle.kts`
- `V2rayNG/app/src/main/AndroidManifest.xml`
- `V2rayNG/app/src/main/res/values/strings.xml`
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`

---

## Android Client Implementation

### Application Identity
**File**: `app/build.gradle.kts:8-12`

Changed application ID while preserving namespace:
```kotlin
android {
    namespace = "com.v2ray.ang"  // Unchanged for BuildConfig compatibility
    compileSdk = 36

    defaultConfig {
        applicationId = "online.vsemoi"  // New package ID
```

**Rationale**: Allows installation alongside original v2rayNG without code refactoring.

### Application Name
**File**: `app/src/main/res/values/strings.xml:3`

```xml
<string name="app_name" translatable="false">VseMoiOnline</string>
```

### URI Scheme Registration
**File**: `AndroidManifest.xml:70-76`

Registered `vsemoionline://` custom URI scheme with intent filter on MainActivity:
```xml
<!-- VseMoiOnline Magic Link Support -->
<intent-filter android:label="VseMoiOnline Configuration">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="vsemoionline" />
</intent-filter>
```

### Magic Link Handler
**File**: `MainActivity.kt:210-220`

The `handleMagicLink()` method:
1. Checks for `vsemoionline://` scheme in intent
2. Extracts `?url=` query parameter
3. Calls `fetchAndImportConfig()` with provision URL
4. Logs all operations to logcat with "VseMoiOnline:" prefix

**Lifecycle Integration**:
- `onCreate()` (line 197): Handles cold start clicks
- `onNewIntent()` (line 200-204): Handles clicks when app already running

### Device Identity System
**File**: `MainActivity.kt:226-239`

`getOrCreateDeviceId()` implementation:
- Uses SharedPreferences with key `"vsemoionline_prefs"`
- Generates UUID via `java.util.UUID.randomUUID()`
- Persists across app sessions
- Avoids hardware identifiers (privacy-compliant)
- Returns same UUID for all backend requests

### Configuration Provisioning
**File**: `MainActivity.kt:245-287`

`fetchAndImportConfig()` method:
1. Shows progress spinner (`pbWaiting`)
2. Appends `device_id=UUID` parameter to provision URL
3. Downloads VLESS URI via HTTP GET (10s timeout)
4. Validates HTTP 200 response
5. Passes URI to existing `importBatchConfig()` method
6. Displays success/error toast
7. Hides progress spinner

**Network Operations**:
- Uses `lifecycleScope.launch(Dispatchers.IO)` for async download
- `HttpURLConnection` with 10-second connect/read timeout
- `withContext(Dispatchers.Main)` for UI updates
- Exception handling with user-facing error messages

### Auto-Provisioning Until Success
**File**: `MainActivity.kt:372-383`

`checkAndAutoProvision()` implementation:
- Called from `onResume()` every time app resumes (line 365)
- Checks if server list is empty via `MmkvManager.decodeServerList()`
- If empty → triggers provisioning from hardcoded backend URL
- **Retries on every resume until successful**
- No persistent "attempted" flag - server list itself is the state tracker
- Stops retrying once any server is successfully added to list

**Hardcoded Backend URL** (line 380):
```kotlin
val defaultBackendUrl = "http://103.241.67.124:8888/provision"
```

**Retry Behavior:**
- ✅ Backend unavailable → Retries on next app resume
- ✅ Network error → Retries on next app resume
- ✅ Config import fails → Retries on next app resume
- ✅ Success → Stops trying (server list no longer empty)

---

## UI/UX Redesign Implementation Details (2026-01-04)

### Custom Launcher Icons

**Files Modified**: All `mipmap-*/ic_launcher*.png` files

Replaced v2rayNG icons with custom VseMoiOnline branding:
- **Foreground**: `/icons/ic_launcher_foreground.png` (192x192px)
- **Background**: `/icons/ic_launcher_background.png` (192x192px)
- Deployed to all density folders: mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi

**Adaptive Icon Configuration**:
```xml
<!-- ic_launcher.xml -->
<adaptive-icon>
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

Changed from color-based background to image-based for proper layering.

### WireGuard-Style Toggle Switch

**File**: `MainRecyclerAdapter.kt:128-159`

Added inline toggle functionality:
```kotlin
// Setup switch for VPN connection
val isSelected = guid == MmkvManager.getSelectServer()
holder.itemMainBinding.switchConnection.isChecked = isRunning && isSelected
holder.itemMainBinding.switchConnection.isEnabled = isSelected

holder.itemMainBinding.switchConnection.setOnCheckedChangeListener { _, isChecked ->
    if (isChecked) {
        // Start VPN with permission check
        if (VpnService.prepare() == null) {
            mActivity.startV2Ray()
        } else {
            // Request permission, revert switch
            holder.itemMainBinding.switchConnection.isChecked = false
            mActivity.requestVpnPermission.launch(intent)
        }
    } else {
        // Stop VPN
        V2RayServiceManager.stopVService(mActivity)
    }
}
```

**Layout**: `item_recycler_main.xml:38-42`
```xml
<com.google.android.material.switchmaterial.SwitchMaterial
    android:id="@+id/switch_connection"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginEnd="@dimen/padding_spacing_dp8" />
```

**Bug Fix**: Toggle state synchronization
```kotlin
// MainActivity.kt:305
mainViewModel.isRunning.observe(this) { isRunning ->
    adapter.isRunning = isRunning
    adapter.notifyDataSetChanged()  // Critical: refresh switch states
    // ... rest of FAB logic
}
```

Without `notifyDataSetChanged()`, switch stays OFF after VPN permission grant until app resume.

### Localized Connection Names

**Implementation**: Replace profile.remarks with localized string

**Adapter**: `MainRecyclerAdapter.kt:65`
```kotlin
holder.itemMainBinding.tvName.text = mActivity.getString(R.string.vpn_connection_name)
```

**String Resources**:
```xml
<!-- values/strings.xml -->
<string name="vpn_connection_name">Turn VPN On/Off</string>

<!-- values-ru/strings.xml -->
<string name="vpn_connection_name">Вкл./Выкл. VPN</string>
```

Automatically uses device language. Actual connection name ("VseMoiOnline") remains in database for backend compatibility.

### Simplified Connection List UI

**File**: `item_recycler_main.xml`

Reduced from 224 lines to 96 lines by:
1. Removing nested layouts for statistics, type, test results
2. Hiding action buttons (share, edit, delete, more) with `visibility="gone"`
3. Keeping IDs to prevent adapter crashes but setting dimensions to 0dp
4. Clean single-line layout: `[indicator] [name] [toggle]`

**Result**: Clean, minimal display suitable for non-technical users.

### Navigation Drawer Cleanup

**Menu**: `menu_drawer.xml`

Removed 8 items, kept 5:
```xml
<group android:id="@+id/group_main">
    <item android:id="@+id/per_app_proxy_settings" />  <!-- Essential for banking apps -->
    <item android:id="@+id/source_code" />
    <item android:id="@+id/oss_licenses" />
    <item android:id="@+id/tg_channel" />
    <item android:id="@+id/privacy_policy" />
    <item android:id="@+id/placeholder" />  <!-- Version display -->
</group>
```

**Version Display**: `nav_header.xml:20-27`
```xml
<TextView
    android:id="@+id/tv_version_header"
    android:textAppearance="@style/TextAppearance.AppCompat.Small"
    android:textColor="?android:attr/textColorSecondary" />
```

**Population**: `MainActivity.kt:163-165`
```kotlin
binding.navView.getHeaderView(0)?.findViewById<TextView>(R.id.tv_version_header)?.text =
    "v${BuildConfig.VERSION_NAME} (${SpeedtestManager.getLibVersion()})"
```

### Toolbar Simplification

**Menu**: `menu_main.xml`

Reduced from 100+ lines to 9 lines:
```xml
<menu>
    <item
        android:id="@+id/service_restart"
        android:icon="@drawable/ic_refresh_24dp"
        android:title="@string/title_service_restart"
        app:showAsAction="always" />
</menu>
```

Only service restart button remains for troubleshooting stuck connections.

### About Screen Gutted

**Strategy**: Move all useful items to navigation drawer, bypass About activity

**AboutActivity.kt**: Removed 179 lines
- Deleted backup/restore/share functionality
- Deleted permission launchers
- Deleted file chooser logic
- Kept only version display (now redundant)

**Navigation Handler**: `MainActivity.kt:670-682`
```kotlin
when (item.itemId) {
    R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
    R.id.source_code -> Utils.openUri(this, AppConfig.APP_URL)
    R.id.oss_licenses -> {
        val webView = WebView(this)
        webView.loadUrl("file:///android_asset/open_source_licenses.html")
        AlertDialog.Builder(this).setView(webView).show()
    }
    R.id.tg_channel -> Utils.openUri(this, AppConfig.TG_CHANNEL_URL)
    R.id.privacy_policy -> Utils.openUri(this, AppConfig.APP_PRIVACY_POLICY)
}
```

### Security Enhancement: Connection Test Results

**File**: `V2RayServiceManager.kt:255-260`

**Removed**:
```kotlin
// OLD: Exposed server IP and country
SpeedtestManager.getRemoteIPInfo()?.let { ip ->
    MessageUtil.sendMsg2UI(service, MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
}
```

**Result**: Only shows `"Success: Connection took 12ms"` without IP/country.

**Rationale**: Prevents non-technical users from accidentally sharing server IP in screenshots, reducing risk of censorship.

### Floating Action Button (FAB)

**File**: `activity_main.xml:110`

```xml
<FloatingActionButton
    android:id="@+id/fab"
    android:visibility="gone"  <!-- Hidden -->
    ... />
```

Replaced by inline toggle switches. FAB update logic remains for future compatibility but button is invisible.

---

## End-to-End Flow

### Magic Link Flow
1. User clicks `vsemoionline://import?url=http://server:8888/provision` link
2. Android intent system launches MainActivity
3. `handleMagicLink()` extracts URL parameter
4. Device UUID retrieved from SharedPreferences
5. HTTP GET: `http://server:8888/provision?device_id=<uuid>`
6. Server generates VLESS config and returns URI
7. Client downloads VLESS URI string
8. `importBatchConfig()` parses and imports config
9. User sees server in list, can connect immediately

### Auto-Provision Flow
1. User installs and opens app for first time
2. `onResume()` calls `checkAndAutoProvision()`
3. Checks if `serverList` is empty
4. If empty, triggers `fetchAndImportConfig()` automatically
5. Config imported silently in background
6. User sees server appear in list
7. On next resume, `serverList` is not empty → stops retrying

**If Backend Unavailable:**
1. User opens app, backend is down
2. Provisioning fails (network error)
3. User closes app, backend comes online
4. User reopens app → `onResume()` triggers retry
5. Provisioning succeeds → server added to list

---

## Integration with v2rayNG Architecture

### Config Import Integration
VseMoiOnline reuses v2rayNG's existing import infrastructure:
- `AngConfigManager.importBatchConfig()` - Handles VLESS URI parsing
- `MainViewModel.reloadServerList()` - Refreshes UI after import
- Existing QR code, clipboard, and manual import paths unchanged

### VLESS URI Format
Backend returns standard VLESS URI:
```
vless://UUID@SERVER:PORT?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUBLIC_KEY&sid=SHORT_ID&type=tcp#VseMoiOnline
```

Parsed by v2rayNG's native `AngConfigManager` without modification.

---

## Testing & Validation

### Verified Scenarios
✅ Cold start: App not running, click magic link → Config imports
✅ Hot resume: App already open, click magic link → Config imports
✅ Auto-provision: First launch with empty server list → Config imports
✅ Device persistence: Same UUID across app restarts
✅ Server recognition: Returning devices get existing config
✅ Build success: Compiles without errors (namespace preservation)

### Test Command (ADB)
```bash
# Trigger magic link
adb shell am start -a android.intent.action.VIEW \
  -d "vsemoionline://import?url=http://103.241.67.124:8888/provision"

# View logs
adb logcat | grep "VseMoiOnline:"
```

### Expected Log Output
```
VseMoiOnline: Magic link detected, provisioning from: http://...
VseMoiOnline: Generated new device UUID: <uuid>  (or "Using existing device UUID")
VseMoiOnline: Fetching config from: http://.../provision?device_id=<uuid>
VseMoiOnline: Received config, importing...
```

---

## Branding Notes

### Application Name
- **Display name**: "VseMoiOnline"
- **Package ID**: `online.vsemoi`
- **Namespace**: `com.v2ray.ang` (preserved for compatibility)

### Backend URL
Hardcoded in `MainActivity.kt:390`. To change:
1. Edit `defaultBackendUrl` variable
2. Rebuild APK
3. Future: Make configurable via Settings

### Logos & Icons
- Currently using original v2rayNG icons
- Future: Replace launcher icons in `app/src/main/res/mipmap-*/`

---

## Privacy & Security

### Device Identification
- Random UUID generated on first launch
- No hardware identifiers (IMEI, MAC address, etc.)
- UUID stored in app-private SharedPreferences
- Cannot be traced to physical device

### Network Traffic
- HTTP used for provisioning (config download)
- ⚠️ **Production Note**: VLESS URI transmitted in plaintext
- VLESS connection itself uses Reality protocol (encrypted)
- Auto-provisioning only on first launch (no background activity)

### Permissions
No additional permissions required beyond original v2rayNG:
- `INTERNET` - Already present
- `ACCESS_NETWORK_STATE` - Already present
- No camera, location, or sensitive permissions

---

## Future Enhancements

### Potential Improvements
1. **HTTPS Backend**: Encrypt provisioning traffic
2. **Configurable Backend URL**: Settings UI for custom backend
3. **Multi-Server Support**: Provision multiple configs at once
4. **Subscription Updates**: Periodic config refresh from backend
5. **Error Recovery**: Retry button in UI if auto-provision fails

### Migration Path
To update backend URL without rebuilding:
1. Implement Settings preference for backend URL
2. Store in MmkvManager (v2rayNG's config storage)
3. Use stored URL if present, fall back to hardcoded
4. Add UI in SettingsActivity for URL configuration

---
