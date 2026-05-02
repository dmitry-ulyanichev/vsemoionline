# VseMoiOnline: Implementation Plan

## Updates (2026-05-02)

### Native Android cabinet — current state

Completed in this session:

- Added an in-app `CabinetActivity` and registered it in the Android manifest.
- Replaced the paid-user drawer cabinet browser launch with the native cabinet screen.
- The native cabinet uses the existing backend cabinet JSON APIs after obtaining a normal `cabinet_session`.
- The screen supports:
  - account/family summary
  - member list
  - add member
  - edit member name
  - remove non-owner member
  - allocate one day
  - distribute days evenly
  - reissue activation code
  - copy/open activation links
  - refresh
  - renew via native payment
  - logout
- Mutating cabinet operations mark the activity as changed; returning to `MainActivity` can refresh provisioning/status.

### Trusted-device cabinet access

Problem found after initial implementation:

- The Android client only shows `Личный кабинет` to paid users that the backend already recognizes.
- Requiring email one-time-code login inside the app repeats authorization that already happened through provisioning/status identity.
- That extra step makes sense on the public web page, but it is redundant in the paid Android client.

Implemented behavior:

- Backend now exposes `POST /cabinet/app-session`.
- Android posts:
  - `device_fingerprint` from local `device_id`
  - `xray_uuid` from the last successful provisioning
- Backend validates:
  - device exists
  - `xray_uuid` matches the device row
  - effective entitlement is paid
  - device is not a regular family member
- Backend then creates a regular `cabinet_sessions` row and returns the session token/cookie.
- Android stores the session token and uses it as the `cabinet_session` cookie for existing `/cabinet/api/*` calls.

Email one-time-code login remains as a fallback:

- backend endpoint unavailable
- device identity stale or rejected
- local paid state missing
- recovery/manual access case

If a stored cabinet session expires, Android clears it and retries trusted-device access before showing the email login form.

### Files touched

Android:

- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/CabinetActivity.kt`
- `V2rayNG/app/src/main/res/layout/activity_cabinet.xml`
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`
- `V2rayNG/app/src/main/AndroidManifest.xml`
- `V2rayNG/app/src/main/res/values/strings.xml`

Backend:

- `client-backend/src/routes/cabinet.js`

Validation:

- Android compile: `./gradlew :app:compilePlaystoreDebugKotlin` passed.
- Android compile: `./gradlew :app:compileFdroidDebugKotlin` passed.
- Backend syntax check: `node --check client-backend/src/routes/cabinet.js` passed.

Next sensible steps:

- Test the cabinet happy path on a real paid owner device after backend deploy.
- Test expired-session behavior: saved `cabinet_session` should retry app-session before email.
- Test rejection behavior for regular family members: drawer should remain hidden, and backend should reject direct app-session attempts.
- Consider adding a backend regression test for `/cabinet/app-session` once the backend test harness has route-level coverage for cabinet sessions.

### Native Android payment flow — current state

Completed in this session:

- Added an in-app `PaymentActivity` and registered it in the Android manifest.
- Replaced main-screen pay/renew browser launch with the native payment screen.
- The native screen uses backend runtime config rather than hardcoded pricing:
  - `GET /api/public/payment-config?currency=RUB`
  - available plans
  - available payment methods
  - plan/method compatibility from `available_methods`
- The `3m` plan is selected by default when available.
- Payment creation sends:
  - `plan_code`
  - `currency`
  - `billing_mode`
  - `payment_method`
  - `email`
  - `device_fingerprint`
  - `android_id`
- Only the provider checkout URL opens externally; plan selection, email, waiting state, status polling, success/failure, and recovery handoff remain inside the app.

### Payment processing UX

The native payment screen now has explicit states:

- Initial state:
  - title: `Оплатить подписку`
  - subtitle explains plan/method/email selection and that payment details are entered only on the provider side
  - plan/method/email form is visible
- Processing state:
  - title: `Платёж обрабатывается`
  - subtitle says the app checks status automatically
  - form is hidden
  - progress indicator is visible
  - `Открыть оплату ещё раз` remains as fallback
  - app polls `GET /payment/:id`
- Success state:
  - title: `Оплата подтверждена`
  - user can return to main screen
  - main screen refreshes provisioning
- Failure state:
  - title: `Оплата не прошла`
  - user can go back and create a new payment

This mirrors the website success-page behavior while staying native.

### Existing-email restore handoff

Problem found in testing:

- Fresh emulator/device provisions as an anonymous free account.
- User enters an email that already belongs to a paid account.
- Old backend behavior tried to attach that email to the anonymous account.
- PostgreSQL rejected the write with `idx_accounts_email_unique`.

Implemented behavior:

- Backend now checks whether the email belongs to another account before attaching it to a device/account resolved by `device_fingerprint` or `android_id`.
- If the email belongs to another account, backend returns:
  - HTTP 409
  - `code: email_belongs_to_existing_account`
  - user-facing restore guidance
- Android catches that code and shows `Восстановить подписку`.
- The restore screen opens natively and receives the email via `RestoreSubscriptionActivity.EXTRA_PREFILL_EMAIL`.
- Restore email field is prefilled; the user only has to request the code.

This is the intended lost-device/new-device path: if the user accidentally starts payment with an existing paid email, guide them to restore instead of charging again.

### Files touched

Android:

- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/PaymentActivity.kt`
- `V2rayNG/app/src/main/res/layout/activity_payment.xml`
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`
- `V2rayNG/app/src/main/java/com/v2ray/ang/ui/RestoreSubscriptionActivity.kt`
- `V2rayNG/app/src/main/AndroidManifest.xml`
- `V2rayNG/app/src/main/res/values/strings.xml`
- `V2rayNG/app/src/main/res/values-ru/strings.xml`

Backend:

- `client-backend/src/lib/payments/service.js`
- `client-backend/src/routes/payment.js`
- `client-backend/test/payment-account-resolution.test.js`

Validation:

- Android compile: `./gradlew :app:compilePlaystoreDebugKotlin` passed.
- Backend tests from `client-backend`: `npm test` passed.

Next sensible steps:

- Add native cabinet screens or a cabinet API-backed account summary.
- Consider persisting the last entered payment email locally to prefill future renewals on the same device.
- Add a timeout/expired-payment state if provider confirmation stays pending beyond the polling window.

## Updates (2026-04-30)

### Android Russia split tunneling — current state

Completed in this session:

- Expanded the managed Android bypass package list from MVP size to a broader Russian-market set:
  - banks and business banking
  - Mir Pay / SBPay / YooMoney / QIWI
  - Gosuslugi / Goskey / government services / RZD / Russian Post
  - MTS / Beeline / MegaFon / Tele2 / Rostelecom
  - Yandex, VK/Mail.ru/OK/MAX
  - marketplaces, food delivery, streaming, maps, classifieds, and common RU utilities
- Added `android.package_prefixes` to the backend and bundled Android rulesets.
- Added backend validation and admin UI support for `package_prefixes`.
- Android now expands package prefixes against installed packages, so white-label bank clusters can be handled without listing every regional app explicitly.
- Prefix expansion is merged into the same managed/user overlay model:
  - managed explicit packages
  - managed prefix-expanded packages
  - user-added packages
  - user-removed packages
- Cached/bundled rules are applied on an IO coroutine so installed-package scanning does not block startup UI.
- Added a Settings diagnostics row and dialog for support.
- Added copy-to-clipboard for diagnostics.
- Added reconnect prompt when rules change while VPN is running.
- Added local proxy auth hardening:
  - persistent random app-private local proxy username/password
  - SOCKS inbound requires auth
  - HTTP inbound uses matching accounts
  - `hev-socks5-tunnel` uses the same SOCKS auth
  - internal HTTP proxy calls send `Proxy-Authorization`
  - tun2socks YAML is no longer logged

Important caveat:

- Split tunneling is operationally useful, not a privacy guarantee.
- With local proxy auth, excluded apps should no longer be able to use the localhost proxy without credentials.
- They may still detect that localhost ports exist.
- If LAN proxy sharing is enabled, the proxy may bind beyond loopback; it remains password-protected but should be treated as trusted-network-only.

Validation completed:

- Android compile: `./gradlew :app:compilePlaystoreDebugKotlin` passed.
- Backend tests from `client-backend`: `npm test` passed.
- Smoke testing after rebuild/deploy looked promising, but full real-device testing remains in progress.

### Ruleset maintenance — most economical workflow

Goal: keep the Android package rules useful with the least ongoing manual work.

Recommended source of truth:

- Keep the deployable source of truth in the backend static ruleset first: `client-backend/src/lib/client-rules/android.js`.
- Keep the Android bundled fallback in sync: `V2rayNG/app/src/main/assets/vsemoionline_ru_bypass_rules.json`.
- Treat `white-lists.md` in the backend repo as the research/reference notebook, not as the deployable artifact.
- Use `package_prefixes` for vendor clusters whenever possible. Prefixes are the cheapest maintenance win because one prefix can cover many regional bank apps installed on real devices.

Recommended maintenance cadence:

- Do small monthly reviews by default.
- Do immediate reviews only when support reports a specific RU app not working, or when a major bank/government/marketplace app changes package name.
- Do not try to keep an exhaustive list perfect. Prioritize apps that affect payment, identity, telecom access, delivery/marketplace usage, and top support complaints.

Economical update loop:

1. Collect evidence.
   - Support ticket says app X fails.
   - User diagnostics show whether split tunneling is on, ruleset version, managed/effective counts, and RU geo/domain rules.
   - Ask for the package name if the user can provide it; otherwise identify it from Play/RuStore/APKMirror/APKPure/SBP registry or from a real-device `pm list packages` dump.

2. Classify the app.
   - If it is a known white-label bank vendor, prefer adding or relying on a prefix.
   - If it is a major app with a stable package name, add it explicitly.
   - If the package is uncertain or looks typo-prone, do not add it until verified on a real device or from a reliable listing.

3. Patch both deployable artifacts.
   - Backend: `client-backend/src/lib/client-rules/android.js`
   - Android fallback: `V2rayNG/app/src/main/assets/vsemoionline_ru_bypass_rules.json`
   - Increment ruleset `version`.
   - Keep `updated_at` current.

4. Run cheap validation.
   - Parse the Android JSON fallback.
   - Run backend tests from `client-backend`: `npm test`.
   - Compile Android: `./gradlew :app:compilePlaystoreDebugKotlin`.

5. Publish backend rules.
   - Deploy backend.
   - Publish through `/admin/client-rules/android` if the DB-published ruleset is in use.
   - Confirm public `GET /client-rules/android` returns the new version.

6. Verify on device opportunistically.
   - Open Settings diagnostics and copy it.
   - Confirm version and package counts.
   - Confirm the target app appears selected in per-app bypass if installed.
   - For high-priority reports, ask the affected user to reconnect VPN and retry the app.

Rules for what to add:

- Add explicit packages for:
  - Sber/T-Bank/VTB/Alfa/Gazprombank/Raiffeisen/Otkritie/PSB/Sovcombank/Rosbank/RSHB/MKB and other high-volume banks
  - Mir Pay / SBPay
  - Gosuslugi / Goskey
  - mobile operators
  - Yandex/VK/Mail.ru/OK/MAX
  - Wildberries/Ozon/Avito/MegaMarket/X5/Magnit/Vkusvill/Samokat/Kuper
  - Kinopoisk/IVI/Okko/START/Wink/Rutube/KION
  - 2GIS/Yandex Maps/RZD/Russian Post
- Use prefixes for:
  - `ru.ftc.faktura.`
  - `ru.faktura.`
  - `ftc.faktura.`
  - `com.bifit.`
  - `com.bssys.`
  - `com.isimplelab.`
- Avoid adding:
  - packages that are not verified
  - generic international apps users usually want inside the tunnel
  - blocked/anti-censorship apps where direct RU routing defeats the product purpose

Suggested next-session automation:

- Create one small script in the backend repo that reads `client-backend/src/lib/client-rules/android.js` and writes the Android fallback JSON with the same package list, prefixes, routing rules, version, and TTL.
- Add a test that compares backend static rules and Android fallback rules for equality on:
  - `version`
  - `ttl_seconds`
  - `android.packages`
  - `android.package_prefixes`
  - `android.routing_domain_strategy`
  - `android.routing_rules`
- This removes the easiest human error: updating backend but forgetting the bundled fallback.

Potential future admin improvement:

- Add a "copy current public rules as Android fallback JSON" button or endpoint, but this is lower priority than a repo-local sync script because the fallback must be updated before app build time.

Open technical checks:

- Confirm on real devices that `geosite:category-ru` exists in shipped geo assets.
- If not, switch to categories known to exist or bundle/update the geo assets.
- Confirm local proxy auth does not break:
  - VPN connect
  - IP/speed test
  - subscription update/download paths that use the local HTTP proxy
  - apps excluded from VPN


## Updates (2026-04-28)

### Xray multi-VPS placement + Kamatera traffic windows

Completed in this session:

- Confirmed Kamatera monthly traffic is prorated for servers created after the 1st of the month, then resets to the full monthly package at the next billing cycle.
- Added server traffic-window handling:
  - new Kamatera servers get a prorated first-month `traffic_cap_mb`
  - `traffic_monthly_cap_mb` stores the full monthly package
  - `traffic_resets_at` now points to the next UTC calendar-month boundary
  - monthly reset restores `traffic_cap_mb = traffic_monthly_cap_mb`
- Added a DB-backed provisioning placement model:
  - `provisioning_regions`
  - `provisioning_pools`
  - `provisioning_datacenters`
  - `servers.provisioning_pool_id`
  - `servers.provisioning_datacenter_id`
- Seeded the initial Europe workhorse placement path: `europe` -> `europe-primary` -> Kamatera `EU-FR`.
- Replaced env-only datacenter choice with DB-backed placement selection. The v1 selection rule is:
  - choose the requested or source-server pool
  - pick enabled datacenters by lowest non-dead server count
  - break ties by `sort_order`
- Added nullable integer `weight` to datacenters as future metadata. It is intentionally ignored by the v1 picker.
- Manual provisioning now accepts a target pool via `pool_code`, for example `{ "pool_code": "europe-primary" }`.
- Health-triggered provisioning now uses the source server pool when the source server is linked to the placement model. Older unlinked servers fall back to `DEFAULT_PROVISION_POOL_CODE` (`europe-primary` by default).
- Added `website/admin-provisioning.html` to manage regions, pools, and datacenters from the browser.
- Added orchestrator admin APIs for the provisioning model and routed `vsemoi.online` `/admin/provisioning` and `/admin/provision-server` to the orchestrator.
- Added regions and pools during manual testing:
  - `europe-primary`, `europe-additional`
  - `us-primary`, `us-additional`
  - `middle-east-primary`, `middle-east-additional`
- Added Kamatera datacenters during manual testing:
  - `EU-FR` in `europe-primary`
  - `EU` in `europe-primary`
  - `EU-ST` in `europe-additional`
  - `US-NY2` in `us-primary`
  - `IL` in `middle-east-primary`

Operational/testing notes:

- The `Provision` button on the provisioning admin page performs real Kamatera provisioning after browser confirmation.
- For cheap tests, `PROVISION_BILLING=hourly` can be set temporarily in `/opt/actions-runner-vsemoi/env/orchestrator.env`; set it back to `monthly` before production provisioning.
- `PROVISION_BILLING`, `PROVISION_TRAFFIC`, `PROVISION_CPU`, `PROVISION_RAM_MB`, and `PROVISION_DISK_GB` still affect new server specs.
- `PROVISION_DATACENTER` is no longer used by the placement picker. `PROVISION_IMAGE_ID` is only a fallback when a datacenter row has no `image_id`.
- Tested browser-driven provisioning with hourly billing: Kamatera created the server and the client saw it as an option, but the admin page eventually showed `Failed to provision server: HTTP 504` because the synchronous request outlived the proxy/browser timeout.
- A realistic health-signal test should publish to the Swarm Redis container, not the old Compose Redis container. The active subscriber was on `vsemoi_redis...`, while `vsemoionline-backend-redis-1` had zero subscribers.
- Publishing `server.draining` is the low-risk realistic trigger: it should assess capacity and provision if needed, without draining current users. `server.exhausted` and `server.unreachable` are more disruptive because they call `drainServer(...)`.

TODOs:

- Improve provisioning admin UX for long-running real provisioning:
  - show an immediate in-progress state after confirmation
  - avoid waiting synchronously until the full Kamatera + SSH bootstrap completes
  - expose provisioning/job status or poll by server/task id
- Decide whether to expose `/admin/capacity` through nginx or keep it local-only on `127.0.0.1:3002`.
- Link or intentionally classify pre-placement servers such as the current Madrid server (`EU-MD`) so future health signals do not rely on fallback pool behavior.
- Add explicit admin controls for deleting or disabling pools/regions if needed; current page supports editing them and deleting datacenters.
- Consider a future weighted placement algorithm using the existing nullable `weight` column after enough operational data exists.

---

## Updates (2026-04-24)

### Backend housekeeping + canonical family bootstrap

Completed in this session:

- Added a shared backend housekeeping module so transient-row cleanup is centralized instead of duplicated per route.
- Cabinet traffic now prunes stale `cabinet_access_codes`, `cabinet_sessions`, `post_payment_cabinet_sessions`, and activation `tokens`.
- Restore traffic now prunes stale `recovery_codes`.
- Payment traffic now prunes stale `payment_attempts` and non-successful `payments` (`pending`, `processing`, `cancelled`, `failed`).

Retention rules now encoded in the backend:

- `cabinet_access_codes`: keep 1 day after expiry
- `cabinet_sessions`: keep 7 days after expiry
- `post_payment_cabinet_sessions`: keep 7 days after expiry
- `recovery_codes`: keep 1 day after expiry
- activation `tokens`: keep 7 days after they become unusable (`used_at` for used tokens, `expires_at` for unused expired tokens)
- `payment_attempts`: keep 30 days after `updated_at`
- non-successful `payments`: keep 30 days after `created_at`

Intentional no-prune decisions:

- `payment_events`: keep forever as incoming webhook/event audit history
- `payment_config_audit`: keep forever as admin payment-config change history
- `payment_provider_health`: keep as live runtime state; it updates in place and should stay small

Canonical family bootstrap hardening:

- Added `ensureCanonicalFamilyForAccount(...)` and wired it into payment account resolution and post-payment email correction.
- This closes the gap where payment-created or post-payment-corrected accounts could exist without the canonical `families` / `family_members` rows, which in turn could leave `family_member_devices` unmapped.

Validation notes:

- Recovery-code housekeeping was verified against live stale rows after deploy.
- Cabinet/session/token/payment housekeeping should be verified the same way after deploy by comparing row counts before and after route traffic.

---


### Post-payment email confirmation — fixes and hardening

Bug fixes:

- `confirmPayment` was sending the confirmation email to `accounts.email` instead of `payment.customer_email`. Fixed: `SELECT` in `confirmPayment` now includes `customer_email`; the email payload uses `payment.customer_email` first and falls back to `accounts.email`.
- `isValidEmail` client-side regex was embedded inside a JS template literal without escaped backslashes — `\s` was silently stripped to `s`, causing valid emails containing the letter `s` to be rejected. Fixed by doubling the backslashes.
- `add-email` input in the add-member form was `type="email"`, letting the browser's native validator reject valid addresses before JS could run. Changed to `type="text"` with `inputmode="email"`.
- New family members always showed "Устройство привязано" instead of an activation token. Root cause: `buildFamilyContext` only fetched existing tokens and never created them for new members. Fixed: `buildFamilyContext` now fetches which accounts have devices, then auto-creates activation tokens for members with no device and no existing token.

Behaviour changes:

- Email review step is now skipped for accounts that already have bound devices — if the email is linked to a device it has already been confirmed as working, so the review step is unnecessary friction.
- When the user corrects their post-payment email to one that already belongs to a different account, the system now merges atomically instead of returning 409: credited days are re-applied to the existing account via `creditAccountDepositDays`, the payment record and the post-payment session are reassigned, and the temporary account is deleted. Safe to do unconditionally because the review step only appears for no-device accounts.
- Post-payment session TTL extended from 30 minutes to 7 days to match the normal cabinet session lifetime. The session grants no more privilege than a regular OTP-authenticated session; the shorter TTL had no security justification.

UI:

- Payment success page status indicator replaced with an animated pill + pulsing blue dot, matching the design mockup. Pending state shows the pulse; cancelled/failed states show plain text.

Validated in this session:

- Confirmation email delivered to `customer_email` on webhook-triggered `confirmPayment`
- Accounts with bound devices bypass email review and go straight to the cabinet
- Email correction to an existing account: merge completes, days credited, confirmation email sent to corrected address, temp account deleted
- New member activation token shown immediately on cabinet load (no manual reissue required)
- `isValidEmail` accepts emails containing `s` (e.g. `3john.watts2@gmail.com`) after deploy

---

## Updates (2026-04-22)

### Transactional email + payment-success flow

Completed in this session:

- Brevo SMTP is now configured for production transactional mail. Authenticated sending domain: `notify.vsemoi.online`; sender: `code@notify.vsemoi.online`.
- Cabinet login OTP and subscription recovery OTP now send through Brevo instead of the old log-only stub.
- `/cabinet/send-code` and `/restore/send-code` were hardened:
  - generic success response for unknown emails (no account enumeration)
  - per-email limits in the backend
  - per-IP limits in the backend
  - nginx edge rate limiting on both `vmonl.store` and `vsemoi.online`
  - Cloudflare real-IP restoration added so edge limits apply per visitor, not per Cloudflare proxy
  - nginx now returns `429` when the edge limit is exceeded
- Payment success UX was redesigned and implemented:
  - `/payment/success` now polls for confirmation and supports both internal `payment_intent_id` and provider payment ids in the success URL
  - when payment becomes confirmed, the browser gets a short-lived post-payment cabinet session
  - the user is taken into a restricted email-review gate before the rest of the cabinet opens
  - the gate supports both `Email верный` and `Исправить email` paths
  - the old `Вернуться к выбору тарифа` link was removed from the success page to reduce the risk of losing the only easy chance to fix a mistyped email
- Payment confirmation email is now sent immediately after backend payment confirmation, and is sent again if the user changes the email in the post-payment review gate.

Validation completed in this session:

- Brevo DNS auth completed and verified
- live cabinet OTP delivery verified
- live recovery OTP delivery verified
- send-code throttling verified at nginx edge (`429`)
- post-payment success -> email review -> cabinet flow verified with test payments
- post-payment email correction path verified

Remaining note:

- A full end-to-end confirmed-payment credits paid days into canonical family deposit test was not completed in this session because the ad-hoc test accounts used here were not attached to the canonical family tables.

## Updates (2026-04-15)

### Family-member runtime validation completed

The canonical family/member model is now validated through the main member-device runtime paths.

Validated end to end:

- cabinet family state and member allocation
- member activation/claim from the Android app
- `/provision`
- `/status`
- `/servers`
- `/restore`

Important implementation note:

- Android activation no longer relies on legacy token-only semantics
- the app now claims the current device explicitly through backend `POST /activate/claim`
- backend activation token semantics are now explicit:
  - `grant_days`
  - `claim_account`

### Deposit-first accounting ✅ COMPLETE (2026-04-17)

The deposit-first model is fully implemented. Behavior:

- purchases/renewals add paid days to `family_deposit`
- if the family has exactly one active member, those newly added days auto-allocate to that member
- if the family has multiple active members, days remain in `family_deposit` and distribution follows `allocation_policy` (`manual` or `auto_distribute_evenly`)
- operator/owner UX supported: distribute evenly, rebalance, manual `+`/`-` allocation, member removal (returns days to deposit)

### Cabinet UI redesign ✅ COMPLETE (2026-04-17)

The owner cabinet UI has been fully implemented as server-rendered HTML in `cabinet.js`. All major views are live:

- Two-step OTP login (email → 4-digit code), with resend affordance
- Single-member and multi-member home views with dark navy / glass-morphism theme matching `website/style.css`
- Deposit pill, allocation-policy toggle, distribute-evenly dialog, per-member `+`/`−` allocation controls, transaction history (expandable)
- Activation card per member: monospace token, copy-token button, full activation URL, copy-URL button
- `GET /cabinet/logout` route

Token auto-minting logic corrected: a token is minted only when a member has allocated days AND has no bound device. Device existence in the `devices` table is the authoritative signal — role (owner vs member) is not considered.

The next major work item is the **cabinet UI redesign** — see `HANDOFF_2026-04-16_cabinet_ui.md` (now complete).

## Preliminary Decisions Needed Before Starting

1. **tc agent interface** — ✅ HTTP server on `127.0.0.1:9000`, shared-secret auth. Port-based (not per-UUID): `POST /throttle` with `{ port, rate_kbps }`, `DELETE /throttle/:port`. One tc command changes the rate for all free users simultaneously, matching the design doc spec.
2. **Domain fallback** — ✅ GitHub Gist, already implemented and working in `MainActivity.kt`.
3. **DNS provider** — ✅ Namecheap registration + Cloudflare nameservers. Cloudflare API manages DNS records (A record updates via `PATCH /zones/{zone_id}/dns_records/{record_id}`) regardless of registrar.
4. **Kamatera API credentials** — confirm access and test manual VPS provisioning before Phase 2.
5. **`platega.io` merchant credentials + API/webhook docs** — confirm before Phase 3 payment adapter implementation.
6. **Payment amounts and plan durations** — ✅ decided: 1 / 3 / 6 / 12 months at 100 / 300 / 550 / 1000 RUB.
7. **Multi-user days redistribution policy** — ✅ explicit owner-controlled allocation from a shared family deposit; when a family has only one member, top-ups auto-allocate to that member so no redistribution UI is shown.

## Payment Architecture Principles

Phase 3 payment work must be designed for multiple providers and methods from the start. The system should support:

- Russian-local payment providers such as `platega.io`
- International options such as `lava.top`
- Multiple payment methods under one provider (for example: SBP, RU cards, international cards, PayPal)
- Future crypto payments without redesigning the main purchase flow

Design rules:

1. Treat **provider** and **payment method** as separate concepts.
   - `provider`: `platega`, `lava`, `crypto`, etc.
   - `method`: `sbp`, `bank_card_ru`, `bank_card_intl`, `paypal`, `crypto_btc`, `crypto_usdt_trc20`, etc.

2. Create an internal **payment intent** first.
   - Frontend never talks directly in provider terms.
   - Backend creates and stores an internal payment record before calling any gateway.

3. Normalize all provider flows into one internal lifecycle.
   - create payment intent
   - redirect / show QR / show crypto invoice
   - receive webhook or poll provider
   - confirm once
   - apply subscription purchase once

4. Keep provider-specific code inside adapters only.
   - Main business logic must not depend on provider field names or webhook payload shape.

5. Use backend-confirmed payment only.
   - Redirect to success page is not proof of payment.
   - Subscription activation must happen only after verified backend confirmation.

Recommended backend structure:

- `src/lib/payments/catalog.js`
  - source of truth for plans, prices, durations, and enabled methods/providers
- `src/lib/payments/service.js`
  - create intent, validate plan/method, mark paid, credit days, generate activation flow
- `src/lib/payments/providers/base.js`
  - adapter contract
- `src/lib/payments/providers/platega.js`
- `src/lib/payments/providers/lava.js`
- later: `src/lib/payments/providers/crypto.js`

Recommended adapter contract:

- `createPayment(intent)`
- `verifyWebhook(request)`
- `parseWebhook(request)`
- `normalizeStatus(payload)`
- optional `getPaymentStatus(providerPaymentId)`

Recommended API shape:

- `POST /payment/create`
  - input: `plan_code`, `method`, `email`, optional `provider_preference`
  - output: normalized response containing `payment_id`, `status`, `redirect_url` and/or provider-specific display data
- `GET /payment/:id`
  - returns normalized payment status
- `POST /payment/webhook/:provider`
  - provider-specific ingress, normalized internally

Recommended normalized payment statuses:

- `created`
- `pending`
- `waiting_user`
- `paid`
- `failed`
- `cancelled`
- `refunded`
- `expired`

Provider-specific statuses must be stored separately as `provider_status`.

---

## Phase 1: Client Backend + Client UI

**Goal**: End-to-end free tier. A new device installs the APK, gets provisioned, connects, and is throttled. Paid upgrades work via token/deep link.

### Dependencies
- One real domain registered and behind Caddy.
- Postgres schema drafted before any backend code is written.

### 1.1 Postgres Schema

Tables (in FK-safe order):

- `servers`: id, provider, region, ip, status (active/draining/dead), tier (free/paid/both), capacity, created_at
- `domain_names`: id, domain, server_id (FK, nullable), status (active/rotating/dead), is_primary, created_at
- `accounts`: id, account_uuid, android_id, plan (free/paid), paid_until (timestamptz), created_at, last_seen
- `devices`: id, account_id (FK), device_fingerprint, assigned_server_id (FK), assigned_inbound (free/paid), xray_uuid, created_at
- `tokens`: id, account_id (FK), token (unique), type (activation/payment), expires_at, used_at
- `users`: id, account_id (FK), display_name, days_allocated (sub-accounts under paying user)
- `payments`: id, account_id (FK), provider_payment_id, amount, currency, status, created_at
- `server_metrics`: id, server_id (FK), timestamp, connection_count, cpu_pct, rx_bytes, tx_bytes

Use a migration tool (db-migrate or Flyway) from day one. Never alter the schema manually.

### 1.2 Project Scaffolding

Monorepo structure:
```
client-backend/
health-monitor/
orchestrator/
telegram-bot/
xray-agent/          ← runs on host, not in Docker
infra/               ← Swarm stack YAML, Caddy config, Nginx config
```

Each service has its own `Dockerfile` and `package.json`. Root `docker-compose.dev.yml` for local development (no Swarm, bind mounts, plain Postgres and Redis).

### 1.3 Client Backend: Core Endpoints

Implement in this order (each testable independently with curl):

**`POST /provision`**
- Input: `{ android_id, device_fingerprint }`
- Logic: Look up account by `android_id`. If new: create account + device record, call Xray gRPC to add UUID to free inbound, assign to least-loaded free-tier server. If existing: return current config.
- Output: `{ xray_uuid, server_address, server_port, plan, cabinet_url }`
- Deduplication: if `android_id` is missing, create account but flag it for review.

`cabinet_url` is now a generic owner-cabinet entry URL (`/cabinet`), not a device-authorized management page.

**`GET /status/:xray_uuid`**
- Output: plan, days remaining, traffic consumed, throttle_mbps, current_domain (for client fallback chain updates), `family_role` ("owner" / "member" / null — used by the Android client to show/hide family controls).
- Polled by client every 30–60s while VPN is active.

**`GET /servers`**
- Returns list of active server addresses for the client's tier.
- Auth: client sends its `xray_uuid` in header.

**`GET /activate` + `POST /activate`**
- GET: renders HTML page explaining activation (deep link landing).
- POST: consumes token, upgrades account to paid, sets `paid_until = GREATEST(COALESCE(paid_until, NOW()), NOW()) + N days` (stacking-safe), redirects to confirmation page.
- Token must be single-use and time-limited (suggest 48h expiry).

### 1.4 Payment UI Pages (Placeholder)

Serve as static/template HTML from Client Backend. Must work on old Android WebViews — no ES6+, minimal JS:
- `/payment` — paid plan landing (price, features)
- `/payment/success` — post-payment confirmation (placeholder text for Phase 3)
- `/payment/cancel` — abandoned payment page
- `/activate` — deep link landing

### 1.5 APK Download Endpoint ✅

- ✅ `GET /download/apk` — serves APK with `Content-Type: application/vnd.android.package-archive`.
- ✅ `GET /get` — download landing page served from the disposable domain; card-grid design matching `website/index.html`; JS refreshes file sizes from `/api/public/downloads`; used as the share URL by regular family members in the Android app.
- APK stored at `/opt/vsemoi/vsemoivpn.apk` on the host, mounted read-only into the container via `docker-compose.yml` volumes. Not in git.

### 1.6 Xray gRPC Client ✅

Thin gRPC client (used by Client Backend, reused by Orchestrator in Phase 2):
- ✅ Add UUID to inbound
- ✅ Remove UUID from inbound
- ✅ Query inbound stats — `getUserTrafficBytes(xrayUuid)` fetches uplink + downlink via `StatsService/GetStats`, handles gRPC NOT_FOUND (code 5) as zero traffic. Wired into `/status` as `traffic_consumed_mb` (bytes ÷ 1 048 576). Android client reads `traffic_cap_mb` from response (÷ 1000 → GB) and saves to `PREF_TRAFFIC_TOTAL_GB`; falls back to 25 GB / 250 GB if field absent. Backend defaults corrected to 25 000 MB (free) / 250 000 MB (paid).

### 1.7 tc Agent Implementation ✅

Runs on Xray VPS host as a systemd service (not in Docker — intentional):
- ✅ `POST /throttle` with `{ port, rate_kbps }` → applies tc rule to that port (replaces any existing rule for same port)
- ✅ `DELETE /throttle/:port` → removes tc rule for that port
- ✅ `GET /throttle/:port` → returns `{ port, rate_kbps }` (live read from `tc class show`; `null` if no rule set)
- ✅ Idempotent: double-application does not stack rules
- ✅ Binds to `0.0.0.0`; requires shared secret in `x-agent-secret` header for all endpoints
- ✅ Docker bridge iptables rule in place (`iptables -I INPUT -i br-xxx -p tcp --dport 9000 -j ACCEPT`)
- Bootstrap throttle rules applied at startup from `/etc/tc-agent.env` (`FREE_TIER_PORT=8444`, `FREE_TIER_RATE_KBPS=2800`)

### 1.8 Throttling Integration

- ✅ The tc agent applies the free-tier rate limit to the free inbound port at server bootstrap time, not per device.
- ✅ Throttle rate is a config value (`FREE_TIER_RATE_KBPS`), not hardcoded.
- ✅ `/status` returns the **live** `throttle_mbps` by querying `GET /throttle/:port` on the tc-agent. Falls back to env-var default if tc-agent is unreachable.
- ✅ Bootstrap throttle applied at agent startup via `/etc/tc-agent.env`. Survives reboots.
- ✅ `POST /admin/throttle` on the client-backend — operator can update the free/paid tier throttle without SSH. Protected by `ADMIN_SECRET` header. Body: `{ tier, rate_kbps }`.
- ✅ **Multi-server throttle (Phase 2, COMPLETE 2026-04-07)**: `status.js` looks up `servers.tc_agent_url` by `devices.assigned_server_id`; `admin.js /admin/throttle` broadcasts to all active servers. Falls back to `TC_AGENT_URL` env var if column is NULL.

### 1.9 Nginx Reverse Proxy + TLS ✅

The VPS runs nginx (host process) which was already managing other sites. Caddy was ruled out to avoid port conflicts.

- ✅ `infra/nginx/vmonl.store.conf` — server block proxying `vmonl.store` → `http://127.0.0.1:3000` with correct `X-Forwarded-For` headers.
- ✅ TLS via Let's Encrypt / certbot (`--nginx` driver). Cert auto-renews.
- ✅ `.github/workflows/nginx.yml` — deploys config to `/etc/nginx/sites-enabled/` and runs certbot on first deploy.

### 1.10 Docker Swarm Deployment ✅ (migrated from Compose 2026-04-07)

Single-manager Swarm on Ziplex. Kamatera workers join automatically via bootstrap script.

- ✅ `infra/swarm/stack.yml` — 6 permanent services: postgres, redis, client-backend, orchestrator, health-monitor, xray; all constrained to manager node; ports mode: host; update_config order: stop-first
- ✅ `infra/swarm/deploy.sh` — sources all env files, creates postgres_password Swarm secret, runs `docker stack deploy`
- ✅ Secrets: `postgres_password` as external Swarm secret; all others via env file sourcing
- ✅ GH Actions workflows: `docker build + docker service update --force` per service; `infrastructure.yml` triggers `deploy.sh`
- ✅ `orchestrator` mounts `/var/run/docker.sock` for dockerode (labels nodes, creates xray-{ip} services)
- ✅ Dynamic Xray services: `xray-{ip}` per Kamatera worker, pinned by `node.labels.xray.node.{ip}==true`

### 1.11 Android Client Updates

- On first launch: call `/provision`, store returned `xray_uuid` and `vless_uri` in SharedPreferences + import into MMKV.
- On subsequent cold starts (known device): call `/provision` again, rate-limited to 30 min. This re-registers the UUID with Xray (handles server restarts) and fetches a fresh VLESS URI (handles server reassignment). Falls back to cached config if the backend is unreachable. `/provision` for a returning device is idempotent — "already exists" is swallowed on `addUser`.
- Implement deep link handler for `vsemoionline://activate?token=ABC123`.
- Implement domain fallback chain: last known → hardcoded → Gist/Worker → magic link.
- Client UI elements:
  - ✅ Subscription header: "Подписка: Бесплатно" or "Подписка: N дней" (orange at 2–5 days, red pulse at ≤ 3 days)
  - ✅ VPN on/off FAB: power icon, green idle / red connected + halo pulse; grey + block reason when quota/days exhausted
  - ✅ VPN status label below FAB: "Нажмите для подключения" / "Подключено" / block reason strings
  - ✅ Connection blocking: FAB tap blocked with toast when `paid_days_remaining == 0` or `traffic_remaining_gb <= 0`
  - ✅ Server row with flag + city; free-user tap → AlertDialog + pay button highlight; server arrow is `ImageView` (green paid / grey free)
  - ✅ Traffic donut chart + speed donut chart (values from `/status` poll every 45s); speed ring red+0.0 when traffic exhausted
  - ✅ "Оплатить подписку" button + 8-field activation code input (free users)
  - ✅ "Продлить подписку" button with urgent pulse animation when ≤ 3 days or < 10 % traffic (paid users)
  - ✅ "Подключите родных и близких" button (comfortable paid users)
  - ✅ Collapsible "УПРАВЛЕНИЕ ПОДПИСКОЙ" block with comparison table; chevron rotates 200 ms on expand/collapse
  - ✅ Collapsed hint badge (`tv_sub_traffic_hint`): shows days/traffic warning when block is collapsed
  - ✅ Comparison table free-traffic cell wired dynamically to `PREF_TRAFFIC_TOTAL_GB` (from `traffic_cap_mb` in `/status`)
  - ✅ `Оплатить подписку` / `Продлить подписку` open backend `/payment`
  - ✅ `Личный кабинет` removed from the main screen and moved into the drawer menu
  - ✅ drawer `Личный кабинет` opens backend `/cabinet`, which authenticates owner access by email + one-time code
  - ✅ Animated toolbar/VPN area backgrounds (colour-cycling via `ValueAnimator.ofArgb`)
  - ✅ App-open re-provision rate-limited to 30 min (`PREF_LAST_STATUS_POLL_MS`): re-registers UUID with Xray + fetches fresh VLESS URI; falls back to cached config on failure
- ✅ `POST /admin/token` on client-backend — operator generates single-use activation tokens without DB access. Body: `{ xray_uuid, days, expires_hours? }`. Returns `token`, `activation_url`, `expires_at`.

**Testable when**: Fresh device installs APK → calls `/provision` → gets throttled free-tier config → connects → browses at reduced speed. Operator issues token → deep link sent → user taps → account upgrades to paid → unthrottled.

---

## Phase 2: Health Monitor + Orchestrator

**Goal**: The system detects failures and rotates domains/scales servers without manual intervention.

### Dependencies
- Phase 1 fully deployed and stable.
- Kamatera API access confirmed (manually provision a VPS first before automating). ✅
- Cloudflare DNS API: not needed. Replacement domains are pre-bought with A records already pointing at the VPS. Rotation only requires updating the Gist and DB. ✅
- Redis pub/sub topic schema agreed before writing either service.

### 2.1 Server State Model

Traffic is the primary resource per server, not CPU/RAM. Each Kamatera monthly server includes 5TB traffic; overage is expensive.

| State | Meaning | New user assignments |
|---|---|---|
| `active` | Healthy, traffic below threshold | Yes |
| `draining` | Traffic ≥ 90% of cap (TRAFFIC_CAP_THRESHOLD_PCT=90) | No — keep existing users connected |
| `exhausted` | Traffic cap hit (100%) | No — kill switch: refuse new VPN connections |
| `unreachable` | TCP/HTTP probe fails | No — emergency drain + replace |
| `dead` | Terminated | — |

**Traffic tracking**: Xray gRPC stats (summed per server). Kamatera's billing API does not provide live traffic data or overage signals — we own cap enforcement entirely.

**Monthly reset**: Kamatera resets traffic on the server's billing anniversary (day of month it was provisioned), not on the calendar 1st. Store `traffic_resets_at` in the `servers` table (computed from `created_at`). Health Monitor resets the traffic counter and flips `exhausted` → `active` on this date.

**Exhausted servers**: keep alive until `traffic_resets_at` (already paid for the month). Only decommission if also `unreachable`.

**Schema addition**: `servers` table needs `traffic_cap_mb` (default 5 120 000), `traffic_used_mb`, `traffic_resets_at`.

### 2.2 Redis Event Schema

Contract between Health Monitor and Orchestrator. Events fire on state **transitions** only — not on every check.

| Topic | Payload |
|---|---|
| `server.unreachable` | `{ server_id, reason, timestamp }` |
| `server.healthy` | `{ server_id, timestamp }` |
| `server.draining` | `{ server_id, traffic_used_mb, cap_mb, timestamp }` |
| `server.exhausted` | `{ server_id, timestamp }` |
| `server.traffic_reset` | `{ server_id, timestamp }` |
| `domain.unreachable` | `{ domain_id, domain, timestamp }` |
| `domain.rotated` | `{ old_domain, new_domain, server_id, timestamp }` |
| `domain.rotate` | `{ server_id, reason, timestamp }` ← manual admin trigger |

### 2.3 Health Monitor ✅

- ✅ `health-monitor/` service: Fastify on port 3003, ioredis pub, pg pool
- ✅ Server reachability: TCP probe to `server.probe_host || server.ip` on port 8444 every 30s; 2 consecutive failures → `server.unreachable`; 2 consecutive successes → `server.healthy`
- ✅ `probe_host` column on `servers` (migration `20260403120000-add-probe-host.js`): set to Docker service name `xray` for local server; NULL for remote servers (falls back to `server.ip`)
- ✅ Traffic checks: sums `getUserTrafficBytes` per device per server every 5 min; adds delta to `servers.traffic_used_mb`; publishes `server.draining` at ≥ 90%, `server.exhausted` at 100%; threshold checked every tick regardless of traffic delta
- ✅ Monthly reset: on `traffic_resets_at`, zeros `traffic_used_mb`, advances date by 1 month, publishes `server.traffic_reset`, flips `exhausted` → `active`
- ✅ Domain checks: HTTPS probe to `/{domain}/health` every 5 min; optional body token verification via `HEALTH_TOKEN` env; 2 consecutive failures → `domain.unreachable` (orchestrator subscribes and rotates)
- ✅ Proto files reused from `client-backend/src/lib/proto` via Dockerfile COPY — no duplication
- ✅ Deployed: `vsemoionline-backend-health-monitor-1`; secrets at `/opt/actions-runner-vsemoi/env/health-monitor.env`
- ✅ GH Actions workflow: `.github/workflows/health-monitor.yml`
- ✅ All 8 test scenarios passed (2026-04-03): server.unreachable, server.healthy, traffic accumulation, server.draining, server.exhausted, server.traffic_reset, domain.unreachable, healthy domain unaffected

### 2.4 Orchestrator: Domain Rotation ✅

- ✅ `orchestrator/` service: Fastify on port 3002, ioredis pub/sub, pg pool
- ✅ `POST /admin/rotate-domain` — body: `{ server_id }` or `{ domain }`; header: `x-admin-secret`
- ✅ Subscribes to `domain.unreachable` and `domain.rotate` Redis topics
- ✅ Picks next `standby` domain for the server, marks old `dead` / new `active` in a single DB transaction
- ✅ Updates GitHub Gist (`GIST_ID` + `GITHUB_TOKEN` env vars) with `https://{new_domain}/provision`
- ✅ Publishes `domain.rotated` to Redis
- ✅ Per-server cooldown enforced in memory (`DOMAIN_ROTATION_COOLDOWN_MINUTES`)
- ✅ Deployed: `vsemoionline-backend-orchestrator-1`; secrets at `/opt/actions-runner-vsemoi/env/orchestrator.env`
- ✅ GH Actions workflow: `.github/workflows/orchestrator.yml`
- ✅ Android: Gist URL hash removed from `MainActivity.kt` — client now always reads latest Gist revision
- ✅ Migration `20260402135944-phase2-schema.js` applied: `domain_names` gains `standby` status; `servers` gains `traffic_cap_mb`, `traffic_used_mb`, `traffic_resets_at`, `tc_agent_url`; `servers.status` extended with `exhausted`, `unreachable`

### 2.5 Orchestrator: Server Scaling ✅ (bootstrap test complete 2026-04-05)

- ✅ `orchestrator/src/providers/kamatera.js` — `{ provisionServer, terminateServer, listServers }`. Bearer token auth with caching, async queue polling.
- ✅ `orchestrator/src/lib/placement.js` — `PlacementPolicy` class. Provider-agnostic `getSpec(context)` → `{ provider, spec }`. Phase 2.5 = static env vars. Designed for multi-zone / multi-provider extension.
- ✅ `orchestrator/src/lib/bootstrap.js` — SSH via `ssh2`, uploads Xray config + tc-agent files via SFTP, runs `infra/bootstrap-xray.sh`.
- ✅ `infra/bootstrap-xray.sh` — idempotent Ubuntu 24.04 setup: Docker (with log rotation), Node.js, journald size cap, ufw firewall, Xray container, tc-agent systemd service.
- ✅ `orchestrator/src/lib/scaling.js` — event handlers: `server.draining` → assess headroom → provision if < `CAPACITY_MIN_MB`. `server.unreachable` → drain only (no decommission — paid monthly). `server.exhausted` → drain + assess + provision. `server.healthy` → re-activate if watchlisted.
- ✅ Migration `20260404000000-add-kamatera-id.js` — adds `servers.kamatera_id`, `servers.xray_grpc_addr`.
- ✅ `health-monitor/src/lib/traffic-checker.js` — `getServerGrpcAddr` reads `server.xray_grpc_addr` (NULL → env fallback for local Docker server).
- ✅ Admin endpoints: `POST /admin/provision-server`, `POST /admin/drain-server`, `GET /admin/capacity`.
- ✅ Target spec: EU-FR, ubuntu_server_24.04_64-bit, 1A CPU, 1 GB RAM, 20 GB disk, monthly, t5000.
- ✅ Bootstrap test passed 2026-04-05 on EU-FR hourly server (185.181.10.161, since terminated). Fixed: `xray run` → `run` (image ENTRYPOINT is already `xray`); added `npm install` step before tc-agent start.
- ✅ API end-to-end test passed 2026-04-06: `POST /admin/provision-server` provisioned server 2 (63.250.59.47, kamatera_id: e767529f-0da3-446a-9f0a-84aa95c1403d). Multi-server `/provision` routing confirmed.
- ✅ DB state: server 1 = Молдова, Кишинёв (Ziplex); server 2 = Германия, Франкфурт (Kamatera EU-FR). Both `tier='both'`, `status='active'`.

**Design decisions recorded:**
- Shared Reality key pair across all servers (`XRAY_PRIVATE_KEY`) — simpler; each server uses the same public key in VLESS URIs. Future improvement: per-server key pair + `xray_public_key` column.
- `server.unreachable` does NOT trigger decommission — server is paid for monthly; drain + watchlist + re-activate on recovery is safer.
- `PlacementPolicy` is the extension point for: multi-zone awareness, IP-blocking pre-check (provision hourly → probe from Russian IP → commit or retry), multi-provider support.
- `TC_AGENT_URL` per-server lookup in client-backend (`status.js`, `admin.js`) is a separate TODO for when a second server is live.

### 2.6 Zone-Based Server Selection ✅ COMPLETE (2026-04-06)

- ✅ `GET /zones` — public; returns `[{ zone, region, available }]` grouped by display_name/region
- ✅ `POST /servers/select` — x-device-fingerprint auth; body `{ region }`; gRPC migration (removeUserAt old, addUserAt new); returns `{ ok, vless_uri, zone, region }`
- ✅ `servers.region` column added (migration `20260406120000-add-server-region.js`; EU-MD for server 1, EU-FR for server 2)
- ✅ `provision.js` bug fixed: was calling `xrayGrpc.addUser()` on local xray:8080 regardless of assigned server; fixed to `addUserAt(server.xray_grpc_addr || DEFAULT_GRPC_ADDR)`
- ✅ Android: `ZonePickerAdapter` (BaseAdapter, header+zone types), `ListPopupWindow` anchored to rowServer, 55% screen height, grouped by region, free-user upsell dialog, `restartV2Ray()` only if connected

### 2.6a Orchestrator: Server Termination ✅ COMPLETE (2026-04-07)

- ✅ `POST /admin/terminate-server` — body `{ server_id }`, x-admin-secret auth; powers off then terminates; sets `servers.status = 'dead'`
- ✅ `terminateServer()` uses `DELETE /server/{id}/terminate` with `{ confirm: 1, force: 1 }`
- ✅ `display_name` auto-populated on provision from `DATACENTER_DISPLAY_NAME` map in `scaling.js`
- ⚠️ **UNTESTED ASSUMPTION**: `force:1` assumed reliable for running servers (previous "General Error" 2026-04-06 may have been transient). Verify on next test server creation: terminate while running, confirm force:1 succeeds without prior power-off.
- Power-off API (`PUT /server/{id}/power`) investigated but abandoned — Kamatera docs show POST but API returns 405; body format unclear. `docs/kamatera-api.md` updated.

### 2.7 Phase 2 Pending Items

- ✅ Zone-based server selection — COMPLETE (§2.6)
- ✅ Multi-server throttle — COMPLETE (§1.8, §2.6a)
- ~~Real-time speed ring~~ — **dropped**: static throttle-cap display is more informative than live throughput.
- ⚠️ Verify `force:1` termination assumption — see §2.6a

### 2.8 Docker Swarm Migration ✅ COMPLETE (2026-04-07)

- ✅ Ziplex initialized as Swarm manager (node ID: ybijlox4upc8ex4cxilgaje8q)
- ✅ `infra/swarm/stack.yml` + `infra/swarm/deploy.sh` replace docker-compose.yml for all permanent services
- ✅ `infra/bootstrap-xray.sh`: §3 added — worker joins Swarm, writes `/tmp/swarm-node-id`
- ✅ `orchestrator/src/lib/bootstrap.js`: steps 5–7 — reads nodeId, labels node via dockerode, creates xray-{ip} Swarm service
- ✅ `orchestrator/package.json`: `dockerode ^4.0.4` added
- ✅ DB migration `20260407120000-add-swarm-fields.js`: `servers.swarm_node_id`, `servers.swarm_service_name`
- ✅ `scaling.js`: stores swarm_node_id + swarm_service_name in INSERT
- ✅ All 4 GH Actions workflows updated to `docker service update --force`
- ✅ End-to-end test passed: worker joined Swarm, labeled, xray-{ip} at 1/1 replica
- ✅ **EU-FR client connectivity fixed (2026-04-08)**: `XRAY_PUBLIC_KEY` was missing from orchestrator service `environment:` block in `stack.yml` — provisioned servers got null `public_key` in `xray_params`, causing Reality handshake failure. Fixed.
- ✅ **Dead server reassignment (2026-04-08)**: `provision.js` now handles dead/deleted assigned servers — reassigns device to best available server using 3-level zone fallback (exact zone → same continental prefix → any). `pickServer()` extended with `preferredRegion` param.
- ✅ **Client zone flash fixed (2026-04-08)**: zone row shows "🌐 —" while re-provisioning instead of stale cached value.
- ✅ **503 fallback chain fix (2026-04-08)**: client no longer retries fallback URLs on 503 (no servers available) — aborts immediately via `NoServersAvailableException`.
- ⚠️ `PROVISION_BILLING=hourly` in orchestrator.env — **must revert to `monthly` before production**
- ⚠️ Wire Health Monitor auto-termination to Redis (deferred)

**Build order**: ~~Orchestrator domain rotation~~ ✅ → ~~Health Monitor~~ ✅ → ~~Orchestrator server scaling~~ ✅ → ~~Zone-based server selection~~ ✅ → ~~Multi-server throttle~~ ✅ → ~~Swarm migration~~ ✅ → ~~Client/server resilience fixes~~ ✅ → Phase 3.

**Testable when**: Trigger `POST /admin/rotate-domain` → Gist updated → client picks up new domain on next poll. Kill an Xray container → Health Monitor detects → Orchestrator drains → clients get new server. Server hits 90% traffic → `server.draining` → new Kamatera VPS provisions.

---

## Phase 3: Payments + Telegram Bot

**Goal**: Real users can pay. Operators can manage accounts without DB access.

### Dependencies
- `platega.io` merchant credentials and webhook/API documentation available.
- Payment amounts, plan durations, and refund policy decided.
- Multi-user days redistribution policy finalized.
- Telegram Bot token registered.

### 3.1 Payment Gateway Integration (`platega.io`)

- `POST /payment/create`: create `platega.io` payment session, store pending record in `payments`, return redirect URL.
- `POST /payment/webhook` (on vsemoi.online, proxied to Client Backend via Nginx): verify provider authenticity/signature if supported, check idempotency (don't double-credit), update `payments` to confirmed, credit days, trigger same activation logic as `/activate`.
- **Webhook authenticity verification is not optional** — implement from day one once provider docs are available.
- Idempotency: payment providers can send duplicates or repeated status notifications. Check `payments.status` before applying.

Implementation note:
- even while integrating `platega.io` first, build the route and service layer around the multi-provider adapter model above so adding `lava.top` and crypto later does not require route redesign.

### 3.2 Payment UI Completion

Complete the placeholder pages from Phase 1 with real payment redirect flow. Pages must work on old Android WebViews — no ES6+, no CSS Grid assumptions.

Payment UI must be method-aware from the start:
- allow multiple payment methods to be enabled/disabled by config
- do not hardcode a single provider into the HTML flow
- support future display variants such as redirect URL, QR payment, hosted checkout, or crypto invoice/address

Current implementation status (2026-04-13):
- backend-hosted `/payment`, `/payment/success`, `/payment/cancel` are implemented and work across both `vsemoi.online` and `vmonl.store`
- payment page theme is now host-aware and can also be hinted explicitly with `theme=dark|light`
- Android app now appends the explicit theme hint when opening `cabinet_url`
- payment page branding now uses a reusable backend-served asset path (`/assets/logo.svg`)
- public website pricing pages now render from `GET /api/public/plans` instead of duplicating plan data in static HTML
- mobile pricing table copy/spacing has been shortened for better phone rendering

Still pending in this area:
- real `platega.io` adapter binding
- real webhook signature verification
- explicit support for provider-specific UI variants beyond the current redirect flow

### 3.3 Multi-User Management

Canonical model locked on 2026-04-14:

- every paying identity is a **family account**
- the creator is also the **first family member**
- a normal single user is just a **family of one**
- target end-state: payment credits the family-level deposit
- if the family has only one active member, new credit may auto-allocate to that member
- only after a second family member is added does explicit redistribution UI become visible

Recommended backend entities:

- `accounts` — owner identity, recovery email, cabinet access
- `families` — one per owner account; shared paid deposit
- `family_members` — actual consumer identities; device usage belongs here
- `devices` — attached to `family_members`, not directly to the owner concept
- `payments` — credit either a family deposit or a later standalone-emancipated family
- `allocation_events` — audit log for deposit/member redistribution and emancipation

Canonical entitlement semantics:

- paid usage is tethered to both **time** and **traffic**
- example: 30-day purchase => 30 days + 250 GB
- 90-day purchase => 90 days + 750 GB
- these values should be stored explicitly, not inferred later

Canonical user states:

- `free_active`
- `free_exhausted`
- `paid_active`
- `paid_exhausted`

Important rule:

- paid users do **not** downgrade to free when days or paid traffic reach zero
- they remain in the paid track, but in an exhausted state pending renewal

Owner/member surface split:

- owner cabinet: full family management, authenticated by email + one-time code
- member view: subscription status inside the client; no full family-management rights from device identity alone

Emancipation model ✅ COMPLETE (2026-04-24):

- a regular family member who pays for themselves is automatically detached from the original family into a new family of their own
- backend identifies the member by `device_fingerprint` (highest-priority lookup in `resolveAccount`)
- payment page shows emancipation warning and pre-fills the member’s email
- on payment confirmation, `emancipateMemberInTx` runs atomically: detaches the member, carries over remaining allocated days, credits new paid days on top, records a `member_emancipated` audit event in the original family’s history
- the original family’s owner cabinet shows the emancipation event in transaction history

Implementation caution:

- an exploratory backend `/users` linked-account model exists from 2026-04-13
- it is **not** the canonical design and should not be extended further

Recommended next implementation order (updated 2026-04-14):
1. add explicit paid/free exhaustion semantics before more family UX is built
2. introduce canonical family tables and allocation bookkeeping
3. migrate device ownership from top-level accounts toward family members
4. replace the exploratory linked-account `/users` API with canonical family/deposit/member APIs
5. expand the owner cabinet from today’s login scaffold into real family management pages

Status update as of 2026-04-15:

- steps 1–5 above have progressed substantially enough to validate the member runtime path in production
- however, payment/renewal crediting is still transitional and not yet truly deposit-first
- the next recommended implementation slice is:
  1. move purchases/renewals to `family_deposit`
  2. auto-allocate only for a true family of one
  3. make all later member allocation/rebalance operations move days between member allocation and deposit

Status update as of 2026-04-17 — deposit-first fully implemented and tested:

- steps 1–3 above are complete
- purchases/renewals credit `family_deposit`; single-member families auto-allocate immediately
- multi-member families respect `allocation_policy` (`manual` or `auto_distribute_evenly`)
- `POST /cabinet/api/family/members/:id/allocate` supports deposit-first with steal-from-richest fallback when deposit is insufficient
- `POST /cabinet/api/family/policy` sets the family allocation policy
- `POST /cabinet/api/family/rebalance` redistributes deposit days evenly across active members
- `POST /cabinet/api/family/members/:id/remove` returns member days to deposit atomically, expires tokens, downgrades devices
- email-optional member slots implemented: `display_name` required when no email provided; anonymous account created with NULL email
- next major work item is the cabinet UI redesign (handoff at `HANDOFF_2026-04-16_cabinet_ui.md`)

### 3.4 Paid Concurrency Enforcement ✅ COMPLETE (2026-04-17)

- ✅ `/provision` checks `findActiveSiblingDevice` (active = `last_seen_at` within `PAID_SESSION_LOOKBACK_MIN`, default 5 min) and returns HTTP 409 with `code: paid_session_active` for paid users
- ✅ `POST /provision/release` sets `last_seen_at = NOW() - interval '1 hour'` to immediately release the session lock when the user disconnects
- ✅ Android: `PaidSessionActiveException` propagated through the fallback chain; caught in `provisionThenConnect()` to show a toast ("VPN уже активно на другом устройстве") without the generic failure dialog
- ✅ `releaseProvisionSession()` called in FAB handler when stopping the VPN, before `stopVService()`
- This is a heuristic, not a cryptographic lock — documented limitation

### 3.5 Subscription Recovery ("Восстановить подписку")

**Context**: A paid user on a new phone (different `android_id`) has no automatic recovery path. Activation codes alone cannot be used for recovery — they are single-use and code-only re-activation would allow takeover if the code is leaked. Email OTP is required as a second factor.

**Prerequisites**: email collected and stored in `accounts` at payment time (Phase 3.1 payment confirmation flow must save it).

**Recovery ladder**:
1. Same-device reinstall/clear-data → automatic via `android_id` dedup in `/provision` (no user action needed)
2. New phone / factory reset → "Восстановить подписку" email OTP flow (this section)
3. Email forgotten / inaccessible → Telegram support → operator issues new token via `POST /admin/token`

**Android**: Add "Восстановить подписку" item to `menu_drawer.xml`. Preferred implementation: native two-step screen inside the app. The app should send `device_fingerprint` automatically; the user should only enter `email` and the 4-digit OTP. Browser fallback may remain for operator/support use, but it is not the target UX.

Current implementation status (2026-04-12):
- native Android recovery flow is implemented
- end-to-end recovery was validated on-device
- the user-facing flow is `email -> OTP -> done`
- `device_fingerprint` is sent automatically by the app and is not shown to the user
- the browser restore flow remains only as a scaffold/support fallback
- dedicated recovery mockups were added to `ui_mockup_final.html`
- light-theme and dark-theme screenshots were captured after testing for a final UX polish pass

**Backend endpoints**:
- `GET /restore` — HTML page with email input field
- `POST /restore/send-code` — look up account by email; generate 4-digit OTP (short expiry, e.g. 10 min); send via email provider (Resend / Mailgun)
- `POST /restore/verify` — validate OTP + `device_fingerprint` from body; reassign device to the account found by email; return success page

Implementation note from testing:
- browser form with manual `device_fingerprint` entry is usable only as a scaffold/support fallback
- production user flow should hide `device_fingerprint` completely and have the Android client pass it automatically

Owner cabinet access status (2026-04-14):
- backend `/cabinet` owner login flow is implemented
- owner access uses email + one-time code
- Android no longer exposes owner cabinet on the main screen; it opens `/cabinet` from the drawer menu instead

**Security constraints**:
- OTP is 4 digits, expires in 10 min, single-use
- Rate-limit `/restore/send-code` per email (e.g. 3 attempts / 15 min)
- Account reassignment (moving a device to a different account) must only happen via this verified flow — never via activation code alone
- Log all reassignment events with old/new `account_id` and `device_fingerprint`

**UX**: Two-step flow max — enter email → enter 4-digit code → done. No magic links (seniors struggle with cross-app flows).

### 3.6 Telegram Bot

**Operator commands** (restricted by Telegram user ID):
- `/adddays {uuid} {days}` — manually credit days
- `/ban {uuid}` — disable account, remove from Xray inbound
- `/serverstatus` — state of all servers from DB
- `/rotatedomain {server_id}` — manual domain rotation trigger
- `/provisionserver` — manual server provisioning trigger

**User-facing commands**:
- `/start` — onboarding, APK download link
- `/status` — plan, days remaining, server address
- `/activate {token}` — alternative to deep link
- `/help` — FAQ

Security: bot must not expose account UUIDs in chat. Use short-lived lookup tokens or have the user send their UUID from app settings.

**Testable when**: Test user completes a test payment through `platega.io` → receives deep link → taps it → upgraded to paid. Operator sends `/adddays` → days update in seconds. Paid user connects from second device → sees "session active" error.

---

## Cross-Cutting Standards (Apply Across All Phases)

**Logging**: Structured JSON with pino. Never `console.log`. Include `request_id`, `account_id`, `server_id` in every log line where applicable. DEBUG in dev, INFO in production.

**Config/Secrets**: All secrets via Swarm secrets or env vars. No secrets in code or committed config files. Maintain `.env.example` documenting all required variables.

**External call resilience**: All calls to Xray gRPC, Kamatera, Cloudflare, and the payment provider must have timeouts and exponential backoff retry. Failure of any single external service must not crash the Client Backend.

**DB migrations**: Run on container startup for simplicity at this scale.

**Testing strategy**: Integration tests over unit tests. At minimum, test `/provision` end-to-end against real local Postgres and mocked Xray gRPC. Do not chase unit test coverage of internal functions.

**Monitoring**: Add Prometheus metrics endpoint to each service in Phase 2. At minimum: request latency, error rate, active connections. Add Grafana to the Swarm stack.

---

## Critical Files (to Be Created)

| File | Why Critical |
|---|---|
| `infra/swarm-stack.yml` | Central manifest; all service wiring, secrets, network topology |
| `client-backend/src/routes/provision.js` | Most complex single function; touches DB, Xray gRPC, tc agent, and server assignment simultaneously |
| `xray-agent/agent.js` | Only component outside Docker; interface contract gates entire throttling feature |
| `health-monitor/src/checker.js` | Transition detection must be correct; false positives trigger unnecessary rotations |
| `orchestrator/src/providers/kamatera.js` | Provider abstraction seam; retrofitting this later is painful |
