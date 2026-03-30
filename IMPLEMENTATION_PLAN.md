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

### 1.5 APK Download Endpoint

- `GET /download/apk` — serves APK with `Content-Type: application/vnd.android.package-archive`.
- APK stored on server filesystem, not in git.

### 1.6 Xray gRPC Client ✅

Thin gRPC client (used by Client Backend, reused by Orchestrator in Phase 2):
- ✅ Add UUID to inbound
- ✅ Remove UUID from inbound
- ✅ Query inbound stats — `getUserTrafficBytes(xrayUuid)` fetches uplink + downlink via `StatsService/GetStats`, handles gRPC NOT_FOUND (code 5) as zero traffic. Wired into `/status` as `traffic_consumed_mb` (bytes ÷ 1 048 576). Android client reads `traffic_cap_mb` from response (÷ 1000 → GB) and saves to `PREF_TRAFFIC_TOTAL_GB`; falls back to 25 GB / 250 GB if field absent. Backend defaults corrected to 25 000 MB (free) / 250 000 MB (paid).

### 1.7 tc Agent Implementation

Runs on Xray VPS host as a systemd service (not in Docker — intentional):
- `POST /throttle` with `{ port, rate_kbps }` → applies tc rule to that port (replaces any existing rule for same port)
- `DELETE /throttle/:port` → removes tc rule for that port
- `GET /throttle/:port` → returns `{ port, rate_kbps }` (live read from `tc class show`; `null` if no rule set) ✅
- Idempotent: double-application does not stack rules
- Binds to `0.0.0.0`; requires shared secret in `x-agent-secret` header for all endpoints
- When backend runs in Docker on the same host: add iptables rule to allow the Docker bridge interface to reach port 9000 (`iptables -I INPUT -i <br-xxx> -p tcp --dport 9000 -j ACCEPT`)
- Translates to the exact tc commands documented in Phase 0 runbook

### 1.8 Throttling Integration

- The tc agent applies the free-tier rate limit to the free inbound port at server bootstrap time, not per device. The Orchestrator adjusts the rate via the agent when the configured throttle value changes.
- Throttle rate is a config value, not hardcoded.
- Manual `DELETE /throttle/:uuid` simulates an upgrade (used for testing before Phase 3).
- ✅ `/status` returns the **live** `throttle_mbps` by querying `GET /throttle/:port` on the tc-agent for the device's tier port. Falls back to env-var default if tc-agent is unreachable.
- ✅ Bootstrap throttle applied at agent startup via `FREE_TIER_PORT` / `FREE_TIER_RATE_KBPS` env vars in `/etc/tc-agent.env`. Survives reboots.
- **Multi-server TODO**: `TC_AGENT_URL` is currently a single env var (single-server PoC). When multiple Xray VPSes are added, `status.js` must look up `servers.tc_agent_url` by `devices.assigned_server_id` instead.

### 1.9 Caddy Configuration

- Reverse proxy in front of Client Backend with automatic HTTPS.
- Set `X-Forwarded-For` correctly (needed for abuse detection).
- Route `/download/apk` to APK file.

### 1.10 Swarm Deployment Stack

`infra/swarm-stack.yml` deploying: Caddy, Client Backend, Postgres, Redis (wired in now even though Phase 2 uses it — avoids infrastructure changes later).

Use Swarm secrets for: Postgres password, Xray gRPC auth token, tc agent shared secret.

### 1.11 Android Client Updates

- On first launch: call `/provision`, store returned `xray_uuid` and `server_address` in Android AccountManager.
- On subsequent launches: call `/status`, update config if server address changed.
- Implement deep link handler for `vsemoionline://activate?token=ABC123`.
- Implement domain fallback chain: last known → hardcoded → Gist/Worker → magic link.
- Client UI elements:
  - ✅ Subscription header: "Подписка: Бесплатно" or "Подписка: N дней" (orange at 2–5 days, red+blinking at 1 day)
  - ✅ VPN on/off FAB (mint ▶ / red ■)
  - ✅ Server row with flag + city; free-user tap → AlertDialog + pay button highlight
  - ✅ Traffic donut chart + speed donut chart (values from `/status` poll every 45s)
  - ✅ "Оплатить подписку" button + 8-field activation code input (free users)
  - ✅ "Подключите родных и близких" / "Продлить подписку" button (paid users)
  - ✅ Collapsible "УПРАВЛЕНИЕ ПОДПИСКОЙ" block with comparison table

**Testable when**: Fresh device installs APK → calls `/provision` → gets throttled free-tier config → connects → browses at reduced speed. Operator issues token → deep link sent → user taps → account upgrades to paid → unthrottled.

---

## Phase 2: Health Monitor + Orchestrator

**Goal**: The system detects failures and rotates domains/scales servers without manual intervention.

### Dependencies
- Phase 1 fully deployed and stable.
- Kamatera API access confirmed (manually provision a VPS first before automating).
- Cloudflare DNS API access confirmed.
- Redis pub/sub topic schema agreed before writing either service.

### 2.1 Redis Event Schema

Contract between Health Monitor and Orchestrator:

| Topic | Payload |
|---|---|
| `server.unhealthy` | `{ server_id, reason, timestamp }` |
| `server.healthy` | `{ server_id, timestamp }` |
| `server.overloaded` | `{ server_id, connection_count, threshold, timestamp }` |
| `domain.unreachable` | `{ domain_id, domain, timestamp }` |
| `domain.rotated` | `{ old_domain, new_domain, server_id, timestamp }` |

Events fire on state **transitions** only — not on every check. This prevents event storms.

### 2.2 Health Monitor

- **Server checks**: TCP connect + HTTP probe to each active server's Xray port every 30s.
- **Domain checks**: HTTP probe with body token verification (detect hijacking, not just 200).
- **Metrics**: Pull connection counts from Xray gRPC stats, write to `server_metrics`.
- **Startup**: Treat all servers as unknown on start; require 2 consecutive positive checks before declaring healthy.
- **Publishing**: On transition, publish to Redis. Debounce: do not publish the same state twice.

### 2.3 Orchestrator: Domain Rotation

Implement first (testable without Kamatera):
- Subscribe to `domain.unreachable`.
- Pick new domain from pool or generate new subdomain.
- Update DNS via Cloudflare API.
- Update `domain_names` table.
- Update GitHub Gist / Cloudflare Worker fallback URL.
- Publish `domain.rotated`.
- Add `current_domain` to `/status` response so clients pick up the change on next poll.
- Cooldown: no more than one rotation per server per N minutes.

### 2.4 Orchestrator: Server Scaling

Implement second:
- Subscribe to `server.overloaded` (provision new server) and `server.unhealthy` (drain + replace).
- On provision: call Kamatera API, run bootstrap script, register in `servers` table.
- Bootstrap script: installs Docker, deploys Xray container, installs tc agent. Must be idempotent. Test manually before automating.
- On drain: update device assignments in DB → affected clients get new server on next `/status` poll.
- **Provider abstraction layer**: `providers/kamatera.js` implements `{ provisionServer, terminateServer, listServers }`. Core orchestration logic never imports Kamatera directly.

### 2.5 Swarm Multi-Node Prep

- Add placement constraints to `swarm-stack.yml` so Xray containers are pinned to their VPS nodes by label.
- tc agent installation is part of the server bootstrap script (not Swarm-managed).

**Testable when**: Kill an Xray container → Health Monitor detects → Orchestrator marks unhealthy → clients receive new server address on next poll → traffic resumes. Make a domain unreachable → DNS rotates → Gist updated. Lower overload threshold → new Kamatera VPS auto-provisions.

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

### 3.5 Telegram Bot

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
