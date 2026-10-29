# The site on wildalert.app: Cloudflare Worker → Cloud Run gateway

```
 browser
        |  https://wildalert.app/            (www.wildalert.app 301s here)
        v
 Cloudflare edge   (Workers Custom Domain -- Cloudflare owns the DNS record and the cert)
        |  Worker  (src/worker.js) -- forces https, canonicalises the host, rewrites Host
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

## Custom Domains, not path routes — and why that is the whole deployment story

This started as two path routes (`wildalert.app/*`) plus two hand-made proxied `AAAA → 100::`
records, which is the usual recipe. It was changed because that recipe **cannot be deployed from
here**: a path route only fires on a hostname that already has a proxied DNS record, `wrangler`
has no DNS-record command, and the OAuth token `wrangler login` stores carries **`zone:read`** —
no `dns_records:write`. So every deploy would have ended with "now go and click two records".

A **Workers Custom Domain** has Cloudflare create the DNS record and issue the certificate
*server-side*, through the Workers API — which those scopes do cover. The result is that
`wrangler deploy` is the entire setup, and the DNS records are not ours to maintain or get wrong.

What the swap changes in practice:

- **All paths go to the worker**, not a chosen prefix. That is what we wanted anyway — the worker
  proxies the whole site.
- **No `100::` placeholder and no orange-cloud toggle to forget.** Cloudflare points the record at
  the Workers platform itself.
- **Zone WAF and rate-limiting rules still apply** to these hostnames, so the abuse-control plan
  is untouched. The usual gotcha — Cloudflare counting a worker's own subrequest against a rate
  limit — does not apply here, because the subrequest leaves for `*.run.app`, which is not this
  zone. (If it ever pointed back at this zone, the rule would need `cf.worker.upstream_zone`.)
- **Limits accepted:** a Custom Domain cannot be created over an existing CNAME on that hostname
  (both were bare — the apex had only MX), and it does not support wildcards. Neither matters
  here.

**`Always Use HTTPS` is not needed either.** It is a zone setting, equally out of reach, so
`src/worker.js` does the http→https redirect itself — in the same hop as the www→apex one. The
upshot is that this directory is the complete configuration of the domain: there is no dashboard
state behind it.

## Setup

1. **Deploy the gateway first.** The worker needs its URL, and a cold 503 from Cloud Run is
   indistinguishable here from a Cloudflare problem. **Done** — `gateway` in `europe-west3`,
   `https://gateway-lznkorfm4q-ey.a.run.app`, health 200 and the registration form served at `/`.
2. **Deploy the worker. That is the whole setup. Done 2026-10-29** — `wildalert-site`, version
   `d5bdc52a-5800-42cc-bd8c-8274af033a54`, 1.27 KiB, startup 1 ms.
   ```bash
   cd deploy/site-worker
   npx wrangler deploy
   ```
   Both Custom Domains attach, Cloudflare creates the `wildalert.app` and `www.wildalert.app`
   records and issues the certificates, and the site is live. No DNS clicks, no SSL toggles —
   see the section above for why it is built this way.

   If it complains about a workers.dev subdomain, `workers_dev = false` has gone missing from
   `wrangler.toml`.

   **On a machine with Node older than 22**, wrangler refuses to run (`nvm list` to see what is
   installed; this project's main checkout runs on 20). Invoking a newer Node directly avoids
   switching the global nvm symlink:
   ```bash
   "$LOCALAPPDATA/nvm/v23.11.1/node.exe" ./node_modules/wrangler/bin/wrangler.js deploy
   ```
3. **Nothing else.** The two steps that a path-route setup would have needed — the proxied DNS
   records and `Always Use HTTPS` — are gone by construction: Cloudflare owns the records, and the
   worker does the https redirect itself.
4. **Optional, a flood brake:** Security → WAF → Rate limiting rules → one rule on path
   `/api/hunters`. Read the limits below before expecting much of it.

## Verifying

All of the following were run and passed on 2026-10-29, right after the deploy above.

```bash
curl -sI https://wildalert.app/ | head -1                       # 200
curl -s  https://wildalert.app/ | grep -o '<title>.*</title>'   # WildAlert — Register
curl -sI https://www.wildalert.app/ | grep -iE 'HTTP|location'  # 301 -> https://wildalert.app/
curl -sI http://wildalert.app/x?a=1                             # 301 -> https://wildalert.app/x?a=1
curl -sI http://www.wildalert.app/x?a=1                         # 301 -> https://wildalert.app/x?a=1  (one hop)
curl -sI https://wildalert.app/api/hunters/by-email              # 404, and nothing in user-account's logs
```

**If `curl` exits 6 ("could not resolve host") right after the deploy**, that is the local
resolver still holding the old negative answer, not a broken deploy — Cloudflare's own resolver
had the record immediately. Check with `nslookup wildalert.app 1.1.1.1`, and pin the address to
test through it meanwhile:

```bash
curl -s --resolve wildalert.app:443:104.21.0.141 https://wildalert.app/ | head -1
```

That form still verifies the certificate properly, so a clean run also proves the Custom Domain's
cert is valid for the hostname.

- **The full chain, without creating a hunter:** `POST /api/hunters` with `{}` should answer
  **400** from user-account's validation. 400 proves the request went worker → gateway → ID token
  → *private* user-account; a **403** would mean the ID token was rejected, and a **404** that the
  gateway never routed it. Verified: 400.
  ```bash
  curl -s -X POST -H 'content-type: application/json' -d '{}' https://wildalert.app/api/hunters
  ```
  Registering a real hunter through the form is still the nicest end-to-end check — it just
  leaves a row behind, so it is not the one to reach for first.
- `npx wrangler tail` streams the worker's logs. A 404 on `/` with a 200 from the `run.app` URL
  means the Host rewrite is not happening; a 502 means the gateway is down.
- **Email is unaffected, and that was checked rather than assumed:** all three
  `route{1,2,3}.mx.cloudflare.net` records still resolve, and
  `wrangler email routing rules get wildalert.app catch-all` still reports
  `Enabled: true` / `worker: wildalert-email-in`. Adding the web records cannot touch MX, but it
  costs one command to know.

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
