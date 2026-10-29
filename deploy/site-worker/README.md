# The site on wildalert.app: Cloudflare Worker → Cloud Run gateway

```
 browser
        |  https://wildalert.app/            (www.wildalert.app 301s here)
        v
 Cloudflare edge   (proxied AAAA -> 100::, so the route attaches)
        |  Worker  (src/worker.js) -- rewrites the Host header
        v
 https://gateway-lznkorfm4q-ey.a.run.app/    Host: gateway-lznkorfm4q-ey.a.run.app
        |
        +-- /                 the built React app, out of the gateway's own jar
        +-- POST /api/hunters forwarded to private user-account with a Google ID token
```

## Why a worker, and not just a DNS record

Cloud Run routes by **Host header**. A request arriving at the `*.run.app` front door with
`Host: wildalert.app` matches no service and gets a 404, so pointing a proxied CNAME at the
gateway does not work. Something has to send the Host that Cloud Run already knows.

Confirmed against the live service rather than assumed — same URL, same path, one header apart:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: wildalert.app' \
  https://gateway-lznkorfm4q-ey.a.run.app/     # 404
curl -s -o /dev/null -w '%{http_code}\n' \
  https://gateway-lznkorfm4q-ey.a.run.app/     # 200
```

Four ways to do that, and why this is the one:

| Option | Verdict |
|---|---|
| **Cloud Run domain mapping** | **Not possible here.** `europe-west3` is not in the supported region list, and Google labels the feature preview and "not production-ready … due to latency issues" regardless |
| **Cloudflare host-header override** (Origin Rules) | **Enterprise only.** The free plan's Origin Rules can change the destination *port* and nothing else |
| **Global external Application Load Balancer** | Google's own recommendation, and the only option that can take the `run.app` URL off the public internet (ingress → internal + load balancing). **~€18–20/month** for the forwarding rule, on a project whose entire premise is that infrastructure is nearly free. Revisit if traffic or abuse ever justifies it |
| **This worker** | €0, one file, and the zone/registrar/`wrangler` workflow already existed for the email worker. Rewriting a header is a line of code |

**Firebase Hosting** would also have worked, free, with rewrites to Cloud Run (`europe-west3`
*is* supported there). It was passed over because it adds a second CDN in front of a service that
already serves its own static files, and because its rewrites cannot add a header — so it has no
answer to the bypass below.

## The apex is canonical

`https://wildalert.app` serves the site; `www` 301s to it. The apex wins because hunter
addresses are already `<token>@wildalert.app`, so the domain on the page and the domain in the
address are the same string.

**This does not disturb inbound email.** MX records are independent of A/AAAA/CNAME, so Email
Routing, the apex catch-all and every hunter address keep working exactly as before — see
[`../email-worker/README.md`](../email-worker/README.md). The two workers share a zone and
nothing else: one has an `email` handler and no routes, this one has a `fetch` handler and two.

## The AAAA to `100::` is not a mistake

A Workers route only attaches to a hostname that has a **proxied** DNS record. The worker answers
before anything is dialled, so the record's content is never used — the convention is therefore to
point it at the IPv6 discard prefix `100::`, which is unroutable on purpose. A real address there
would be misleading, and leaving the record out means the route silently never fires.

Both records must be **Proxied** (orange cloud). Grey-clouded, the request goes straight to
`100::` and times out.

## Setup

1. **Deploy the gateway first.** The worker needs its URL, and a cold 503 from Cloud Run is
   indistinguishable here from a Cloudflare problem. **Done** — `gateway` in `europe-west3`,
   `https://gateway-lznkorfm4q-ey.a.run.app`, health 200 and the registration form served at `/`.
2. **DNS** (dashboard → the zone → DNS → Records). Add both, **Proxied**:

   | Type | Name | Content | Proxy |
   |---|---|---|---|
   | AAAA | `@` | `100::` | Proxied |
   | AAAA | `www` | `100::` | Proxied |

   Leave the MX and SPF records alone.
3. **Deploy the worker:**
   ```bash
   cd deploy/site-worker
   npx wrangler deploy
   ```
   Unlike the email worker this one *has* routes, so `wrangler deploy` attaches them and exits 0.
   If it complains about a workers.dev subdomain, `workers_dev = false` has gone missing from
   `wrangler.toml`.
4. **SSL/TLS → Overview → Full (strict)**, and **Edge Certificates → Always Use HTTPS → On**.
   Universal SSL already covers `wildalert.app` and `*.wildalert.app`, so there is no certificate
   to request. Strict costs nothing here: the only origin is `*.run.app`, which Google serves with
   a publicly valid certificate.
5. **Optional, a flood brake:** Security → WAF → Rate limiting rules → one rule on path
   `/api/hunters`. Read the limits below before expecting much of it.

## Verifying

```bash
curl -sI https://wildalert.app/ | head -1                      # 200
curl -s https://wildalert.app/ | grep -o '<title>.*</title>'   # WildAlert — Register
curl -sI https://www.wildalert.app/ | grep -iE 'HTTP|location' # 301 -> https://wildalert.app/
curl -sI https://wildalert.app/api/hunters/by-email             # 404, and nothing in user-account's logs
```

- `npx wrangler tail` streams the worker's logs. A 404 on `/` with a 200 from the `run.app` URL
  means the Host rewrite is not happening; a 502 means the gateway is.
- **Register a hunter through the form on the real domain.** That is the only check that exercises
  the whole path — worker → gateway → ID token → private user-account — and the inbound address it
  returns should read `<token>@wildalert.app`.
- Confirm email still works afterwards: send a photo to a hunter's address and watch
  `npx wrangler tail` in `deploy/email-worker`. The DNS change should be invisible to it.

## Known limits

- **The `run.app` URL stays public.** `https://gateway-lznkorfm4q-ey.a.run.app` keeps answering,
  so anything enforced at the Cloudflare edge — a rate-limiting rule, a WAF rule, a country block
  — can be sidestepped by calling it directly. See below.
- **Cloudflare's free rate limiting is thin:** one rule, keyed on IP only, a 10-second counting
  window and a 10-second block. It stops a crude flood. A patient script at 5 requests per
  10 seconds still gets ~43,000 registrations a day through, so it is not the abuse control —
  **Turnstile on the form is**, and it works regardless of who fronts the domain.
- **Workers free plan: 100,000 requests/day, 10 ms CPU per invocation.** Proxying is I/O, not CPU.
  Every page view, asset and API call now counts against that number.
- **Nothing is cached.** Deliberate — see the comment in `src/worker.js`. The hashed `assets/*`
  files are safe to cache and worth doing later; `index.html` is not, because the React app ships
  inside the gateway's jar so that the page and the API can never be different versions.

## Closing the bypass

When the `run.app` URL needs to stop being a way around the edge, and the load balancer is still
not worth €18/month:

1. `printf %s "$(openssl rand -hex 32)" | npx wrangler secret put ORIGIN_SECRET` — the worker
   starts sending it as `x-origin-secret` immediately, and nothing yet requires it.
2. Store the same value in Secret Manager and have the gateway reject requests without it, with
   one exception for `/actuator/health` so Cloud Run's probes still pass.

Do those in that order: the header must be arriving *before* the gateway demands it, or the site
is down in between. Until step 2, `x-client-ip` is also only as trustworthy as any other client
header — the worker sets it from `CF-Connecting-IP`, but a direct caller can claim anything.
