# VseMoiOnline: Implementation Plan

## Preliminary Decisions Needed Before Starting

1. **tc agent interface** — ✅ HTTP server on `127.0.0.1:9000`, shared-secret auth. Port-based (not per-UUID): `POST /throttle` with `{ port, rate_kbps }`, `DELETE /throttle/:port`. One tc command changes the rate for all free users simultaneously, matching the design doc spec.
2. **Domain fallback** — ✅ GitHub Gist, already implemented and working in `MainActivity.kt`.
3. **DNS provider** — ✅ Namecheap registration + Cloudflare nameservers. Cloudflare API manages DNS records (A record updates via `PATCH /zones/{zone_id}/dns_records/{record_id}`) regardless of registrar.
4. **Kamatera API credentials** — confirm access and test manual VPS provisioning before Phase 2.
5. **YooKassa sandbox credentials** — confirm before Phase 3.
6. **Payment amounts and plan durations** — needed before Phase 3.
7. **Multi-user days redistribution policy** — pro-rata on payment, or operator-defined split?

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
- `accounts`: id, account_uuid, android_id, plan (free/paid), paid_days_remaining, created_at, last_seen
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

**`GET /status/:xray_uuid`**
- Output: plan, days remaining, traffic consumed, throttle_mbps, current_domain (for client fallback chain updates).
- Polled by client every 30–60s while VPN is active.

**`GET /servers`**
- Returns list of active server addresses for the client's tier.
- Auth: client sends its `xray_uuid` in header.

**`GET /activate` + `POST /activate`**
- GET: renders HTML page explaining activation (deep link landing).
- POST: consumes token, upgrades account to paid, sets `paid_days_remaining`, redirects to confirmation page.
- Token must be single-use and time-limited (suggest 48h expiry).

### 1.4 Payment UI Pages (Placeholder)

Serve as static/template HTML from Client Backend. Must work on old Android WebViews — no ES6+, minimal JS:
- `/payment` — paid plan landing (price, features)
- `/payment/success` — post-payment confirmation (placeholder text for Phase 3)
- `/payment/cancel` — abandoned payment page
- `/activate` — deep link landing

### 1.5 APK Download Endpoint ✅

- ✅ `GET /download/apk` — serves APK with `Content-Type: application/vnd.android.package-archive`.
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
- **Multi-server TODO (Phase 2)**: `TC_AGENT_URL` is currently a single env var (single-server PoC). When multiple Xray VPSes are added, `status.js` and `admin.js` must look up `servers.tc_agent_url` by `devices.assigned_server_id` instead.

### 1.9 Nginx Reverse Proxy + TLS ✅

The VPS runs nginx (host process) which was already managing other sites. Caddy was ruled out to avoid port conflicts.

- ✅ `infra/nginx/vmonl.store.conf` — server block proxying `vmonl.store` → `http://127.0.0.1:3000` with correct `X-Forwarded-For` headers.
- ✅ TLS via Let's Encrypt / certbot (`--nginx` driver). Cert auto-renews.
- ✅ `.github/workflows/nginx.yml` — deploys config to `/etc/nginx/sites-enabled/` and runs certbot on first deploy.

### 1.10 Docker Compose Deployment ✅

No Docker Swarm — single VPS at this stage. Services managed by `docker-compose.yml` + per-service GitHub Actions workflows (self-hosted runner on the VPS).

- ✅ Services: `postgres`, `redis`, `client-backend`, `xray`
- ✅ Secrets via files in `/opt/actions-runner-vsemoi/env/` (outside git)
- ✅ Workflows: `infrastructure.yml`, `client-backend.yml`, `xray.yml`, `nginx.yml`
- Swarm migration deferred to Phase 2 if/when multi-node is needed.

### 1.11 Android Client Updates

- On first launch: call `/provision`, store returned `xray_uuid` and `server_address` in Android AccountManager.
- On subsequent launches: call `/status`, update config if server address changed.
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
  - ✅ "Оплатить подписку", "Продлить подписку", "Личный кабинет" buttons open `cabinet_url` from backend; cabinet link hidden for free users
  - ✅ Animated toolbar/VPN area backgrounds (colour-cycling via `ValueAnimator.ofArgb`)
  - ✅ App-open status refresh rate-limited to 30 min (`PREF_LAST_STATUS_POLL_MS`)
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

### 2.3 Health Monitor

Build second (after Orchestrator domain rotation is working).

- **Server reachability**: TCP connect + HTTP probe to each active server's Xray port every 30s. Require 2 consecutive failures before publishing `server.unreachable`; require 2 consecutive successes before publishing `server.healthy`.
- **Traffic checks**: Sum Xray gRPC user stats per server every 5 min; update `server_metrics` and `servers.traffic_used_mb`. Publish `server.draining` at 90%, `server.exhausted` at 100%.
- **Monthly reset**: On `traffic_resets_at`, zero out `traffic_used_mb`, publish `server.traffic_reset`, flip state to `active`.
- **Domain checks**: HTTP probe with body token verification (detects hijacking, not just 200 OK).
- **No CPU/RAM monitoring** in Phase 2 — traffic cap is the binding constraint on Type-A servers.

### 2.4 Orchestrator: Domain Rotation

Build first (testable without Kamatera or Health Monitor — use manual admin trigger).

- Subscribe to `domain.unreachable` (from Health Monitor) and `domain.rotate` (manual admin trigger via `POST /admin/rotate-domain`).
- Pick next domain from pool in `domain_names` table (status = 'standby'). All standby domains are pre-bought with A records already pointing at the VPS — no DNS API call needed.
- Mark old domain `dead`, new domain `active` in `domain_names`.
- Update GitHub Gist with new domain URL.
- Publish `domain.rotated`.
- `/status` already returns `current_domain` — clients pick it up on next poll.
- Cooldown: no more than one rotation per server per `DOMAIN_ROTATION_COOLDOWN_MINUTES` (env var).

### 2.5 Orchestrator: Server Scaling

Build third (after Health Monitor).

- Subscribe to `server.draining` (provision additional server) and `server.unreachable` (drain + replace).
- On provision: call Kamatera API (Bearer token auth — exchange clientId+secret via POST /authenticate first), run bootstrap script, register in `servers` table with `traffic_resets_at` = provisioned_at + 1 month.
- Bootstrap script: installs Docker, deploys Xray container, installs tc agent. Must be idempotent. **Test manually before automating.**
- On drain: reassign devices in DB → affected clients get new server on next `/status` poll.
- On `server.exhausted`: no new provisioning — server idles until `server.traffic_reset`.
- **Provider abstraction layer**: `providers/kamatera.js` implements `{ provisionServer, terminateServer, listServers }`. Core orchestration logic never imports Kamatera directly.

### 2.6 Swarm Multi-Node Prep

- Add placement constraints to `swarm-stack.yml` so Xray containers are pinned to their VPS nodes by label.
- tc agent installation is part of the server bootstrap script (not Swarm-managed).

**Build order**: Orchestrator domain rotation → Health Monitor → Orchestrator server scaling.

**Testable when**: Trigger `POST /admin/rotate-domain` → Gist updated → client picks up new domain on next poll. Kill an Xray container → Health Monitor detects → Orchestrator drains → clients get new server. Server hits 90% traffic → `server.draining` → new Kamatera VPS provisions.

---

## Phase 3: Payments + Telegram Bot

**Goal**: Real users can pay. Operators can manage accounts without DB access.

### Dependencies
- YooKassa sandbox credentials available.
- Payment amounts, plan durations, and refund policy decided.
- Multi-user days redistribution policy finalized.
- Telegram Bot token registered.

### 3.1 YooKassa Integration

- `POST /payment/create`: create YooKassa payment session, store pending record in `payments`, return redirect URL.
- `POST /payment/webhook` (on vsemoi.online, proxied to Client Backend via Nginx): verify webhook signature, check idempotency (don't double-credit), update `payments` to confirmed, credit days, trigger same activation logic as `/activate`.
- **Signature verification is not optional** — implement from day one.
- Idempotency: YooKassa can send duplicates. Check `payments.status` before applying.

### 3.2 Payment UI Completion

Complete the placeholder pages from Phase 1 with real YooKassa redirect flow. Pages must work on old Android WebViews — no ES6+, no CSS Grid assumptions.

### 3.3 Multi-User Management

- `POST /users` — create sub-user, return activation deep link token.
- `GET /users` — list sub-users with days allocated.
- `POST /users/:id/allocate` — redistribute days.
- Constraint: total allocated days ≤ `accounts.paid_days_remaining`.
- Constraint: 1 concurrent connection per sub-user.

### 3.4 Paid Concurrency Enforcement

- On `/provision` for paid user: check for existing active session for that sub-user (active = called `/status` within last 5 min).
- If conflict: return error; Android client displays localized message to user.
- This is a heuristic, not a cryptographic lock — document the limitation.

### 3.5 Subscription Recovery ("Восстановить подписку")

**Context**: A paid user on a new phone (different `android_id`) has no automatic recovery path. Activation codes alone cannot be used for recovery — they are single-use and code-only re-activation would allow takeover if the code is leaked. Email OTP is required as a second factor.

**Prerequisites**: email collected and stored in `accounts` at payment time (Phase 3.1 webhook must save it).

**Recovery ladder**:
1. Same-device reinstall/clear-data → automatic via `android_id` dedup in `/provision` (no user action needed)
2. New phone / factory reset → "Восстановить подписку" email OTP flow (this section)
3. Email forgotten / inaccessible → Telegram support → operator issues new token via `POST /admin/token`

**Android**: Add "Восстановить подписку" item to `menu_drawer.xml`. On tap: open `https://vmonl.store/restore` in browser (no new Activity needed — `Utils.openUri`).

**Backend endpoints**:
- `GET /restore` — HTML page with email input field
- `POST /restore/send-code` — look up account by email; generate 4-digit OTP (short expiry, e.g. 10 min); send via email provider (Resend / Mailgun)
- `POST /restore/verify` — validate OTP + `device_fingerprint` from body; reassign device to the account found by email; return success page

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

**Testable when**: Test user completes YooKassa sandbox payment → receives deep link → taps it → upgraded to paid. Operator sends `/adddays` → days update in seconds. Paid user connects from second device → sees "session active" error.

---

## Cross-Cutting Standards (Apply Across All Phases)

**Logging**: Structured JSON with pino. Never `console.log`. Include `request_id`, `account_id`, `server_id` in every log line where applicable. DEBUG in dev, INFO in production.

**Config/Secrets**: All secrets via Swarm secrets or env vars. No secrets in code or committed config files. Maintain `.env.example` documenting all required variables.

**External call resilience**: All calls to Xray gRPC, Kamatera, Cloudflare, YooKassa must have timeouts and exponential backoff retry. Failure of any single external service must not crash the Client Backend.

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
