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

**`GET /status/:xray_uuid`**
- Output: plan, days remaining, traffic consumed, throttle_mbps, current_domain (for client fallback chain updates).
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
  - ✅ "Оплатить подписку", "Продлить подписку", "Личный кабинет" buttons open `cabinet_url` from backend; cabinet link hidden for free users
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

### 2.6 Zone-Based Server Selection (next to build)

**Design decisions (finalized 2026-04-06):**
- UI shows zones (city-level labels), not individual servers. `servers.display_name` stores the Russian zone label.
- Free users: backend auto-assigns server; zones visible in dropdown but tapping shows upsell dialog.
- Paid users: can select any zone; backend picks healthiest `active` server in that zone.
- `tier` column is NOT used for client-facing zone filtering — both current servers are `'both'`.
- Server change flow: Android → `POST /servers/select { zone }` → backend does gRPC migration (RemoveUser old server, AddUser new server, update `devices.assigned_server_id`) → returns new VLESS URI → Android stores URI and reconnects.

**Backend tasks:**
- `GET /zones` (or update `GET /servers`): return zone-grouped list `[{ zone, region, available }]`. Do not expose server IPs or IDs.
- Consider adding `servers.region` column (e.g. `'EU-MD'`, `'EU-FR'`) as a stable zone key (migration needed).
- `POST /servers/select`: body `{ zone }`, headers `x-device-fingerprint` + `x-android-id`. Verify paid plan, find healthiest server in zone, gRPC-migrate UUID, update DB, return `{ ok, vless_uri }`. Free user → 403 `upgrade_required`. No active server → 503 `zone_unavailable`.

**Android tasks:**
- Call `GET /zones` on resume; populate zone dropdown in `MainViewModel`.
- Free user taps zone → upsell `AlertDialog` → button opens `cabinet_url`.
- Paid user taps zone → call `POST /servers/select` → on success store VLESS URI + reconnect; on error show dialog.
- Highlight currently connected zone.

### 2.7 Phase 2 Pending Items

- **Zone-based server selection** — see §2.6 above. Backend + Android. Handoff doc: `HANDOFF_server_selection.md`.
- **Multi-server throttle**: `TC_AGENT_URL` in `status.js` and `admin.js` is a single env var. Fix: look up `servers.tc_agent_url` for `device.assigned_server_id` and use that. Needed now that server 2 is live.
- **Real-time speed ring**: hook `MainViewModel` speed tracking into donut ring animation.

### 2.8 Swarm Multi-Node Prep

- Add placement constraints to `swarm-stack.yml` so Xray containers are pinned to their VPS nodes by label.
- tc agent installation is part of the server bootstrap script (not Swarm-managed).

**Build order**: ~~Orchestrator domain rotation~~ ✅ → ~~Health Monitor~~ ✅ → ~~Orchestrator server scaling~~ ✅ → Zone-based server selection → Multi-server throttle.

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
- Constraint: total allocated days ≤ remaining days derived from `accounts.paid_until`.
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
