# VseMoiOnline: Backend & User Experience

I'm working on a VPN project for seniors and people with no tech background. Its main feature should be the least amount of friction in UX – the user just downloads and installs the app, and it gets configured automatically on first launch and becomes ready to use. The user doesn't need to deal with any concepts more sophisticated than "Tap the toggle to make the Internet work". It's very useful in Russia, where a lot of tech-illiterate people have lost access to Youtube, Whatsapp, Telegram, Instagram, and other popular services.

Naturally, I'm getting ready for the censor to block access to the domain names and servers I use, and I need a system in place that updates the client's configuration to connect to a current working VPN server once the previous one was blocked. For this, the client queries the last working provisioning endpoint saved in the phone, then the hardcoded URL, then moves through the list of hardcoded URLs on big platforms (it's a list of just one for now) \- until a working one is found or all fail.

As a last resort, a magic link on the website can be clicked.

I’ve already customized a V2rayNG-based PoC Android client and a V2rayN-based PoC Windows client. The changes involving the former are described in CHANGES.md. Now it’s time to design the backend. It should also be as frictionless as possible to the user and very resistant to possible censor attacks.

Below is the result of my brainstorming.

## User Experience

I imagine the potential paying user as follows:

- lives outside Russia,  
- has family living in Russia,  
- is willing to provide paid VPN access for them.

We find these future customers in Whatsapp groups, Telegram channels, etc. and point them to a website – vsemoi.online. There, we describe to them what they get, including two options – free and paid.

To start using the free option, one only needs to download and install the client. The power user (PU) can download the app himself and then share it with a family member, e.g. through Telegram, or copy and share the download link.

We warn the user that the free plan has limitations both in terms of connection speed (2.5-3 Mbps) and traffic (25 GB). The other, paid plan is either unthrottled or has generous limits (25 Mbps and 250 GB, more than enough in most use cases) – this is yet to be decided.

### Issuing Paid-Use Tokens

The backend distinguishes between a free-tier client and a paid client by its device ID. Upon payment, a one-time-token is issued, which can be copied and pasted in the relevant field in the client, thereby authorizing the device. Another option is clicking the magic link that contains the token.

Since a tech-savvy customer can trivially spoof the device ID and use it to try to establish multiple concurrent connections from multiple clients for the same paid user, we need to implement detection: Client Backend logs every connection with its source IP. The backend watches for a paid device\_id connecting from more than one IP simultaneously. When detected, the new connection is silently dropped — the legitimate user never notices, the freeloader is blocked (possibly with a notification in the client). We enforce a limit of 1 concurrent connection per paid device.

Upon payment, a token for the default user is issued automatically. PU can also create additional users (a one-time-token will be issued for each), with labels like Dad’s PC, Dad’s Phone, Mom’s Phone that can be assigned to identify them. PU doesn’t have to pay for each additional user separately. Rather, the remaining paid-for days of use can be redistributed evenly (with one button click) or granularly (with \+/- buttons in each user) between them.

The order of users in the list can be rearranged, with the one at the top of the list serving as the main deposit of remaining days. The remaining days go there when topping up the account via payment, stopping/removing a user, suspending a user (in the case we decide to handle illicit concurrent use), or decreasing the number of remaining days in a user. In a case zero users are left in the account (all are stopped/removed or suspended), the unspent days go to the passive deposit – this is equivalent to putting on pause the spending of the remaining days for the whole account.

### Token Activation in Clients

While creating users and issuing tokens for them seems to be straightforward, activating the tokens raises questions about UX. The simplest way is copying and pasting the token in an input field in the client, but more frictionless methods are also welcome, as they would be a huge benefit for senior users. Let’s look at two possible scenarios:

#### Scenario 1: PU issues a token, gets a client-download link, and shares it with RU

Actually, PU shares two things with RU: the APK (or the link to download it) and an activation link (vsemoionline://activate?token=ABC123). Once RU installs the app and taps the link, the app activates silently — RU never sees or types the token. This is the standard deep-link pattern used for referral codes and email verification in mobile apps.

#### Scenario 2: Free user clicks a payment link in the client, registers, pays, gets a token issued

After payment, besides showing the token for copy/paste, the website provides a link to vsemoionline://activate?token=ABC123. Android intercepts that redirect and hands it to the app, which activates silently. From RU's perspective: tapped a button, filled in payment details, done. The only fallback to copy/paste or magic link is if the payment was completed on a desktop browser rather than the phone.

### Concurrent Payment Issue

Suppose PU is paying for RU and the remaining days are running out for RU. It would be good UX to warn him in advance that it’s time to top up the account with more days. RU can simply contact PU and ask him to do that. Or he may decide to make the payment himself.

If PU pays, it’s easy – he does it in his own account, just as before. But if RU decides to pay himself, by following a payment link from the client, where do we point him? Probably, to his own account-creation page. When he creates an account of his own and tries to pay for himself, we show a warning, such as “Your VPN use has so far been paid for and handled by another user. By making the payment, you are transferring control to yourself – Continue?”

### Client UI

In the header – Подписка: Бесплатно or Подписка: 30 дней (with orange color code for 2-5 days and red and blinking for 1 day), both linking to Личный кабинет.

Below that \=\> VPN On/Off toggle

Below that \=\> Current server: Madrid, Spain; show this as a drop-down menu if more than one server is deployed. When a free user tries to select another zone, we show them a pop-up message saying selection is only available for paid users; when the message is minimized, we highlight the button “Оплатить подписку” (for example, it blinks a few times) that is located below. Under this button, there’s also an input field with the placeholder text “Ввести код активации”.

Below that \=\> for free users, a pie chart and a dial as two small circles in the same row – the pie chart showing how much has been consumed from the total 25Gb allocated (0/25Gb) and the dial showing throttling (2.8/25 Mbps).

We get this data by plain HTTP polling from the client. We add a /status/\<device\_id\> endpoint to the provisioning server returning a small JSON (e.g. {"traffic\_consumed": 15.13, "throttle\_mbps": 2.8}). The client hits it once on VPN connection and then every 30–60 seconds while active. Paid-tier clients skip the polling entirely if we decide not to throttle them or poll the same as free clients if we do enforce some (generous) limits.

For paid users, instead of a regular pay button, the user sees: “Нравится наш VPN? Подключите родных и близких”.

After the account on the website has been created, we make the client remember it so that the next go-to-the-site click takes him directly to his account page. When a token is activated, the provisioning response includes a personal cabinet URL containing the device\_id as the auth token (e.g. https://vsemoi.online/account?device\_id=ABC123). The client stores this URL and uses it for the "Личный кабинет" link. The cabinet only shows subscription status and days remaining — no payment card details — so the security risk of this approach is acceptable. Free users who haven't activated a token get the general registration/payment page instead.

## Backend Architecture

All services run as Docker containers. The cluster is managed with Docker Swarm from day one, even when it starts as a single node. This keeps the tooling in place before it is needed, and the migration cost from a single-node to a multi-node Swarm is zero.

### Division of responsibility between Orchestrator and Swarm:

* The **Orchestrator** is the decision-making layer. It watches health events, applies business rules (scale up if needed, rotate domain if blocked, decommission if IP is compromised in Russia), and determines what the infrastructure should look like at any given moment.  
* **Swarm** is the deployment and runtime layer. It distributes and runs containers across declared nodes, restarts crashed containers, and routes traffic between services — but has no awareness of business logic.  
* Adding a brand-new VPS to the cluster is the Orchestrator's job: it provisions the server via the cloud provider API, installs Docker, customizes Alpine, and runs docker swarm join on it. Only after that does Swarm know the node exists. Swarm's set of available nodes is effectively maintained by the Orchestrator.

### Topology:

| Node | Type | Hosts |
| :---- | :---- | :---- |
| Permanent VPS (behind Cloudflare) | Non-disposable | Swarm manager, Orchestrator, Postgres, Redis, Health Monitor, Telegram bot, vsemoi.online, Client Backend |
| Xray VPS(es) | Disposable, direct IP | Xray |

The permanent VPS hosts everything except Xray. Its real IP is hidden behind Cloudflare, so the censor cannot easily find and block it — domain rotation (pointing a new domain through Cloudflare to the same origin) requires no server migration. The only reason to eventually move the Client Backend to its own node is load isolation: if provisioning traffic grows large enough to compete with Postgres and the Orchestrator for resources. That is a scaling concern, not a security one, and not relevant at launch.

Xray VPS(es) are separate because VPN traffic cannot be proxied through Cloudflare, leaving their IPs directly exposed. They are therefore the most likely targets for IP blocking and are treated as disposable — replaced by the Orchestrator when compromised.

**Reverse proxy**: Caddy, for automatic HTTPS certificate provisioning — essential for discardable domains that rotate without notice.

**Future scaling**: As load grows, the Orchestrator adds new Swarm nodes (new VPS instances) and instructs Swarm to schedule additional Xray containers on them. Scaling down works the same way in reverse. If Swarm proves insufficient, managed Kubernetes (e.g. DigitalOcean Kubernetes) is the next step — but Swarm is built into Docker, far simpler to operate, and likely sufficient for this use case long-term.

### Services

#### vsemoi.online

Nginx serving a static landing page. Its only dynamic function is a gateway redirect to the current discardable domain for any link that needs to work inside Russia — nothing in the client points to vsemoi.online directly. Also hosts the payment webhook receiver (see Payment flow below), since webhooks come from payment processors outside Russia, not from Russia-based users.

Not Russia-facing for client users. Will eventually be blocked in Russia; this is acceptable since it is only for onboarding customers outside Russia.

#### Client Backend

The primary Russia-facing service. Lives on a discardable, rotatable domain name. Hosts all client-facing endpoints and pages:

* /provision — returns a VLESS URI for the client based on device\_id, tier, and optional server\_id preference; performs load balancing  
* /servers — returns the dynamic list of available servers (id, city, country, load); populated by the Orchestrator  
* /status — returns current traffic consumed and throttle value to be displayed in the client  
* /activate — receives token activation requests from the client (via deep link)  
* Payment UI pages — a few templates for the payment flow  
* APK downloads — serves the latest Android (and eventually other platform) clients  
* Telegram bot webhook receiver

Because the client backend is on a discardable domain, every link the client opens — personal cabinet, payment pages, APK downloads — points to this domain, not vsemoi.online. The provisioning response includes the current cabinet URL (keyed by device\_id, no login/password stored on device).

**Stack:** Node.js \+ Fastify, Dockerized.

#### Xray Server

Runs as a Docker container on dedicated VPN node(s), managed by Swarm alongside all other services. Tunnels VPN traffic for authorized clients. Makes no decisions itself — its running config is derived state, managed entirely by the Orchestrator.

Dockerizing Xray is the correct choice given Swarm use: Swarm can only manage containerized workloads. A bare binary on the host would be invisible to Swarm — no automatic restart on crash, no node failover, no consistent deployment across nodes. With Xray containerized, the Orchestrator updates its config by redeploying the container with a new config file rather than SSH-ing into individual servers.

Two inbounds:

* **Free inbound** (dedicated port): OS-level traffic shaping (tc) applied to this port; changing the throttle value is a single tc command regardless of how many free users are connected  
* **Paid inbound** (dedicated port): no rate limiting OR limited to 25 Mbps

Authorizes clients by VLESS UUID (one per device). To authorize a new device, the Orchestrator adds its UUID via Xray's gRPC API. To revoke, it removes it. Xray has no DB of its own.

**Note on throttling**: tc commands run at the host OS level, not inside the container — containers lack the privileges to shape host network traffic. The Orchestrator therefore requires a small management agent on each Xray host specifically for throttle changes. All other Xray management goes through Swarm.

**Question 1**: Is dockerizing Xray the most optimal option? If not dockerized, I won’t be able to use Docker Swarm, but then again maybe I can implement a no-less-effective solution in Orchestrator? Pros: simpler tech stack, less memory and maybe less CPU used, freeing up these resources for actual connections. Cons: ???

#### Health Monitor

Monitors all entries in the servers and domain\_names DB tables, assesses their health (reachability, latency, connection success, maybe also CPU load and monthly traffic used – Kamatera might have an API to return this data), and writes status back to those tables. Publishes health events to Redis pub/sub for the Orchestrator to consume.

Independent of all other services — it only reads/writes DB and publishes events. Nothing else needs to know how monitoring is implemented.

**Stack**: Node.js, Dockerized.

#### Orchestrator

Subscribes to Redis health events and manages all infrastructure in response:

* **Server scaling**: spins new VPN servers up when the number of users reaches a threshold, tears them down when compromised or when the number of users reduces; updates the servers table  
* **Domain rotation**: detects blocked provisioning domains, provisions new discardable ones, updates domain\_names table and the platform-hosted fallback URLs (GitHub Gist, Cloudflare Workers, etc.) that the client uses as censorship fallbacks  
* **Xray config management**: pushes UUID additions/removals to Xray via gRPC; pushes tc throttle commands to VPN servers via a small management agent when load-based throttle value changes  
* **Client migration**: when a server is decommissioned, updates the servers table so the provisioning service re-provisions affected clients on their next connection attempt

The servers table is the source of truth for throttle values. The Orchestrator writes them there; the client backend reads them for the /status endpoint.

**Stack**: Node.js, Dockerized.

**Cloud provider abstraction**: The Orchestrator is built with a provider abstraction layer from day one — a common interface (createServer, deleteServer, listServers) that each provider implements as a plugin. The core orchestration logic never knows which provider it's talking to. This is a critical design decision: building it Kamatera-specific initially and retrofitting the abstraction later would be painful.

**Initial provider**: Kamatera. Kamatera exposes a REST API suitable for automated provisioning. Its 24 zones give good geographic diversity. The Orchestrator provisions new nodes via the Kamatera API, installs Docker on each, and runs docker swarm join to add them to the cluster.

**Why multiple providers matter**: The censor can block entire IP ranges belonging to a single ASN (autonomous system number). Geographic diversity within one provider helps, but ASN diversity — nodes on different providers in different jurisdictions — is stronger protection. Recommended additions: Hetzner (cheap, excellent API, European ASN) and Vultr or DigitalOcean (mature Node.js SDKs). Each requires writing one adapter module against the common interface.

#### Telegram Bot

Russia-accessible regardless of whether its backend is blocked, because Russia-based users interact with Telegram's UI — Telegram's infrastructure connects to the bot backend, not the user directly. Provides download links and payment links.

**Stack**: Node.js \+ Telegraf, Dockerized.

#### Postgres

Single database, shared by all services via internal Docker network. Source of truth for all persistent state.

Key tables: accounts, devices (device\_id, tier, account\_id, selected\_server\_id, cabinet\_url), users (sub-accounts under a paying user, with labels and days\_remaining), tokens (one-time activation tokens), servers (id, city, country, connection params, status, load, throttle\_kbps), domain\_names (url, purpose, status), payments.

#### Redis

Used as the event bus (pub/sub) between the Health Monitor and Orchestrator. May also serve as a cache layer if needed later.

## Payment Flow

1. User taps "pay" in the client → client backend creates a payment session with the processor (e.g. YooKassa for Russia), receives a redirect URL  
2. Client backend redirects user to the processor's hosted payment page  
3. User completes payment on the processor's side  
4. Processor sends a webhook POST to the receiver on vsemoi.online  
5. Receiver validates the webhook signature, calculates days to credit, updates the payments and users tables in DB  
6. Website redirects to vsemoionline://activate?token=ABC123 to silently activate the client

The payment service is not a separate microservice — it is two routes: a redirect handler in the client backend and a webhook receiver on vsemoi.online.

## Censorship Resilience

Two potential points of failure for Russia-based users:

| Failure | Recovery mechanism |
| :---- | :---- |
| Provisioning domain blocked | Orchestrator provisions new domain, updates platform fallback URLs (GitHub Gist, Cloudflare Workers, etc.); client finds new domain via existing fallback chain |
| Xray server blocked | Client re-provisions on next connection attempt; Orchestrator has already spun a replacement |
| Both blocked simultaneously | Client falls back to platform URLs, then magic link distributed via Telegram |

The Xray connection is a VPN tunnel, not a control channel — it cannot be used to push a new domain to the client. Recovery always goes through the fallback provisioning chain already built into the client.

**Note**: Use microsoft.com, samsung.com, icloud.com (others?) as XTLS-Reality dest sites.

## Server Deployment and Load Distribution

I’ve researched a bit the question of server load – from the perspective of per-user traffic consumption and CPU/RAM load. Here are preliminary results:

Commissioning VPSes by the hour may be prohibitively expensive, as it will cost $0.005 per hour plus $0.01 per 1GB of traffic. For the whole month, that would be equivalent to $53.65 (compared with $4 monthly payment for the same configuration). For the same price, I can make \~13 monthly purchases, meaning that the censor must ban my VPSes approximately every 50-55 hours to level out the cost of both options. These are numbers for Kamatera, with competitors probably offering similar price models.

If blockings aren’t very frequent but still happen, I have the incentive to cram as many users into a server as I can and burn through 5TB of traffic as fast as I can (without compromising the promised connection quality of course). This (having many concurrent users) may lead to CPU and RAM bottlenecks, however.

I propose starting with the cheapest, $4 solution but keeping in mind that more expensive configurations may be more viable. Here they all are (all with the same OS Alpine, 5GB SSD):

| CPU Type | vCPUs | RAM | Price |
| :---- | :---- | :---- | :---- |
| A | 1 | 1 | $4 |
| A | 2 | 1 | $8 |
| A | 2 | 2 | $10 |
| B | 1 | 1 | $9 |
| B | 1 | 2 | $15 |

If I cap traffic at 23 GB per free user (but tell them it’s 25 GB) at 2.5-3 Mbps before they hit the paywall, I can accommodate 100 free users on the cheapest VPS. I assume that tunneled traffic is actually counted twice (inbound and outbound) and that there’s a 5-10% TCP overhead. With 100 users per VPS, I can expect a peak concurrent traffic of no more than 60 users, which is likely to give them a smooth experience, with sufficiently rare hiccups.

**Note**: Consider using 3X-UI to track Xray usage and enforce limits.

### Alpine Customization

I understand that the following measures will be necessary:

* Enable BBR  
* Increase file limits  
* UDP buffer (for Telegram/calls)

In any case, it’s imperative to keep track of each server’s traffic use for the month (the counter probably starts at the time of commissioning), maybe also the use of extra traffic (as a tripwire ensuring we don’t eat into this expensive extra traffic), and each individual user’s traffic use. Based on this data, we can estimate our traffic needs and adjust the number of servers necessary for our current user base.

It’s debatable whether we should use separate servers for free and paid users or host both categories on the same server(s) with appropriate throttling. We should probably do our best to service both types separately – that should be another load-distribution criterion, along with smooth user experience.

To strive for this separate clustering of paid and free users, we can probably look at the number of users of both types on a particular server (all paid \-  predominantly paid \- balanced \- predominantly free \- all free), choosing the most fitting one among healthy servers. With a sufficiently large number of servers, paid and free users will be almost completely segregated. Naturally, a paid user’s selection of a particular server in UI will be an even stronger signal, overridden only if the server they ask for is unhealthy.

## Server-Selection Logic and UX

The server list shown in the client is dynamic, fetched from the backend at runtime and populated server-side by Orchestrator. This allows adding/removing servers without an app update.

How it works:

* A /servers endpoint returns the list of available servers with metadata (id, city, country, load). The client fetches this on launch to populate the dropdown.  
* The /provision endpoint accepts an optional server\_id parameter. If present and the server is healthy, the backend returns that server's VLESS URI. If absent, the backend load-balances as usual.  
* The client stores the selected server\_id in SharedPreferences. On the next session it sends the stored preference with the provisioning request. The backend honors it if the server is still healthy, falls back to load-balancing otherwise.

**Reconnection when the user picks a different server mid-session:** treated as a manual re-provisioning trigger. Client calls /provision with the new server\_id, gets a new VLESS URI, imports it replacing the old config, then stops and restarts the VPN. From the user's perspective: they picked a server, the toggle briefly cycled, they're connected to the new location.

**Question 2**: Do we expose all server IPs to the paid customer if we adopt this model?

## Marketing Strategy

### Estimated Traffic Consumption

| Activity | Per-Hour Consumption | Average |
| :---- | :---- | :---- |
| 1080p YouTube | 1.5-4.1 GB | 3.3 GB |
| 4K Video | 10.6–16 GB | 13.3 GB |
| Netflix 4K | 3.5-7 GB | ? |

### Estimated Server User Capacity

| User profile | Monthly data | Max users on 5 TB |
| :---- | :---- | :---- |
| Casual phone (browsing \+ light YouTube) | 15–25 GB | 200–330 |
| Moderate phone (daily YouTube) | 40–60 GB | 83–125 |
| Heavy phone (heavy YouTube \+ Instagram) | 80–120 GB | 42–63 |
| Windows user (gaming \+ some 4K) | 100–200 GB | 25–50 |
| Heavy Windows user | 200-300 GB | 17-25 |
| Mixed realistic average | \~60 GB | \~83 users |

I’ll be dealing with a high-distrust, high-need, low-tech-literacy market. The best growth model is probably the following:

### Viral free tier, limits kick in after prolonged use

This is the most psychologically sophisticated option and in most contexts the strongest. The mechanism at work is a compound of several effects:

* **Full-value onboarding first.** The user's first experience of the product is almost unrestricted. They see YouTube load fast. They share it. Their reference point becomes "this is how it should work." The endowment effect and loss aversion are both working in my favor, not against it.  
* **Delayed friction.** The limit doesn't arrive until the user has built a habit and a reference point.  
* **Conversion pressure that's socially acceptable.** When a limit kicks in after genuine use, it doesn't feel punitive or suspicious. It feels like a natural progression. "I've been using this for 2 weeks and it's been great, now they're asking me to pay" is a reasonable situation that most users accept.

For my specific scenario, where dockerized Xray is deployed on a Kamatera VPS, I can give new free users a generous speed limit of 2.5-3 Mbps (allowing 720p–1080p YouTube quality), with traffic capped at 23 GB. With the server being able to handle up to 60 simultaneous connections, I can give free users an experience that varies from premium at low load to quite good at peak, while showing a constant though unobtrusive reminder (pie chart and speed dial in the client) that they are using a limited version on a free server.

As for the issue of account farming, we fight it by device fingerprinting during first-time provisioning. If the user tries to reset the free-tier limit by reinstalling the app, the fingerprint remains the same (unless the user resets his device to factory defaults) with all the limits associated with it.

**Fingerprinting implementation (sideloaded APK, no Play Store):** Android has blocked hardware identifiers (IMEI, MAC) since API 29, so we cannot rely on them. The recommended approach is a two-signal scheme:

1. **Primary: server-assigned UUID stored in Android AccountManager** (`AccountManager` account storage survives app uninstall/reinstall on the same device, unlike app-local SharedPreferences). On first provisioning, the server issues a UUID and the client stores it via AccountManager. On subsequent reinstalls, the client finds the existing UUID and sends it — the server recognizes the device.
2. **Secondary: `ANDROID_ID`** (unique per app+user+device, resets only on factory reset). Sent alongside the UUID as a corroborating signal.

If a re-provisioning request arrives with a known `ANDROID_ID` but a missing or mismatched AccountManager UUID (indicating a possible factory reset or a new device), the server treats it as a new device and starts a fresh free-tier allocation. If both signals are absent (genuine first install), a new UUID is issued.

**Note:** Google Play Integrity API would provide the strongest cryptographic device attestation, but it requires Play Store distribution and is therefore not available for sideloaded APKs.

I can imagine a scenario where a user discovers this hard-reset backdoor and shares it publicly, let’s say on a popular Discord channel (“Here’s how you can get unlimited free VPN, guys\!”). This may lead to a non-negligible number of young, tech-savvy users starting to abuse my VPN and will require something like rate limits on first provisioning by IP ranges.