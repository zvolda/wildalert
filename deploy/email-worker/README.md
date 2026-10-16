# Inbound email: Cloudflare Email Routing → Email Worker → email-ingestion

```
hunter's camera / forwarding rule
        |  <token>@wildalert.app
        v
 Cloudflare Email Routing  (apex catch-all)
        |  Email Worker  (src/worker.js)
        v
 POST https://email-ingestion-....run.app/api/emails?envelopeTo=<token>@wildalert.app
      header X-Webhook-Secret: <shared secret>
      body   raw RFC-822 bytes
```

## Why the catch-all is on the apex, not `in.wildalert.app`

The original plan was a catch-all on an inbound subdomain. Cloudflare does not allow it:
**catch-all rules are apex-only**, and a subdomain needs one explicit routing rule per
address — which would mean a Cloudflare API call during hunter registration.

So hunter addresses live on the apex (`<token>@wildalert.app`). Human mailboxes still work:
an explicit custom address is matched **before** the catch-all, so `hello@wildalert.app` can
forward to a real inbox while everything else reaches the worker.

The cost of this choice: the apex MX records belong to Cloudflare, so another mail provider
cannot host mailboxes on the apex later.

## Why email-ingestion is the one public service

Cloudflare Workers reach our services over the public internet, so email-ingestion cannot use
`Require authentication` / internal ingress the way the Pub/Sub consumers do. It is public and
guarded by `WebhookAuthFilter`'s shared secret instead — which is what that filter exists for.
Keep `WEBHOOK_SECRET` identical in Secret Manager and in the worker.

## Setup

1. **Domain on Cloudflare.** Register `wildalert.app` (Cloudflare Registrar sells at cost) or
   transfer it in. Email Routing needs Cloudflare as the authoritative nameservers. **Done
   2026-10-14.**
2. **Enable Email Routing** (dashboard → the zone → Email → Email Routing) and let it add the
   MX + SPF records it proposes. **Done 2026-10-14** — `route1/2/3.mx.cloudflare.net`
   (45/70/91) + `v=spf1 include:_spf.mx.cloudflare.net ~all`, confirmed from 8.8.8.8 and
   1.1.1.1.
3. **Verify a destination address** — your own mailbox. Needed for step 6 and for a smoke test
   before the worker exists. **Done:** `animalrecognition@gmail.com`.
   *Smoke test passed* — with the catch-all temporarily set to **Send to an email** → that
   address, a test message to the zone arrived. Do this before wiring the worker: it proves
   DNS/MX/SPF/catch-all independently, so any later failure is ours, not Cloudflare's.
4. **Deploy email-ingestion first.** The worker needs its URL, and a 401 or a cold 503 from it is
   indistinguishable here from a Cloudflare problem. `deploy/CONSOLE.md` steps 10a-10e build it,
   deploy it and verify the whole webhook path with `curl` before any mail is involved.
5. **Deploy the worker:**
   ```bash
   cd deploy/email-worker
   npx wrangler deploy
   npx wrangler secret put INGESTION_URL     # https://email-ingestion-....run.app, no trailing slash
   npx wrangler secret put WEBHOOK_SECRET    # same value as the Cloud Run env/secret
   ```
6. **Point the catch-all at it:** Email Routing → Catch-all address → Action **Send to a
   Worker** → `wildalert-email-in` → Save. This replaces the temporary forward-to-Gmail rule
   from step 3.
7. **Custom addresses first** (optional): add `hello@wildalert.app` → your mailbox. Explicit
   rules take precedence over the catch-all.
8. **Set `INBOUND_EMAIL_DOMAIN=wildalert.app`** on the user-account Cloud Run service and deploy a
   new revision. Existing hunters need nothing — `InboundAddresses` stores only the token and reads
   the domain from config, so their addresses move domain with the revision. Until then they carry
   `…@in.wildalert.local`, which no mail can reach, so the webhook would answer 200 with
   `matchedHunterId: null`.

## Verifying

- `npx wrangler tail` streams the worker's logs while you send a test email.
- Send to a non-existent address on the zone: the catch-all should invoke the worker, and
  email-ingestion should answer 200 with `matchedHunterId: null` (unknown recipients are not
  an error).
- Send to a real hunter's address with a photo attached: the response carries `matchedRecipient`,
  a non-null `matchedHunterId` and `imageCount`. **No SMS yet** — `image.received` has no consumer
  until recognition and notification are deployed; read the event with
  `gcloud pubsub subscriptions pull image.received.inspect --limit 5 --auto-ack`.
- A bad `WEBHOOK_SECRET` shows up as a 401 in `wrangler tail` and the message is retried by
  Email Routing, not lost.

## Known limits

- 25 MiB maximum inbound message (Cloudflare), rejected outright above that.
- Free-plan Workers have a 10 ms CPU budget per invocation. Streaming a few hundred KB is
  I/O, not CPU, so this fits; a very large attachment is the case to watch.
- Email Routing retries a worker that throws. There is no dead-letter queue for inbound mail —
  if ingestion is down long enough, the sending server eventually bounces the message.
