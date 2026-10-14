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
   transfer it in. Email Routing needs Cloudflare as the authoritative nameservers.
2. **Enable Email Routing** (dashboard → the zone → Email → Email Routing) and let it add the
   MX + SPF records it proposes.
3. **Verify a destination address** — your own mailbox. Needed for step 5 and for a smoke test
   before the worker exists.
4. **Deploy the worker:**
   ```bash
   cd deploy/email-worker
   npx wrangler deploy
   npx wrangler secret put INGESTION_URL     # https://email-ingestion-....run.app, no trailing slash
   npx wrangler secret put WEBHOOK_SECRET    # same value as the Cloud Run env/secret
   ```
5. **Point the catch-all at it:** Email Routing → Catch-all address → Action **Send to a
   Worker** → `wildalert-email-in` → Save.
6. **Custom addresses first** (optional): add `hello@wildalert.app` → your mailbox. Explicit
   rules take precedence over the catch-all.
7. **Set `INBOUND_EMAIL_DOMAIN=wildalert.app`** on the user-account Cloud Run service and
   redeploy. Hunters created before this carry addresses on the old placeholder domain and
   need their tokens regenerated.

## Verifying

- `npx wrangler tail` streams the worker's logs while you send a test email.
- Send to a non-existent address on the zone: the catch-all should invoke the worker, and
  email-ingestion should answer 200 with `matchedHunterId: null` (unknown recipients are not
  an error).
- Send to a real hunter's address with a photo attached: the response carries
  `matchedRecipient` and `imageCount`, and the pipeline produces an SMS.
- A bad `WEBHOOK_SECRET` shows up as a 401 in `wrangler tail` and the message is retried by
  Email Routing, not lost.

## Known limits

- 25 MiB maximum inbound message (Cloudflare), rejected outright above that.
- Free-plan Workers have a 10 ms CPU budget per invocation. Streaming a few hundred KB is
  I/O, not CPU, so this fits; a very large attachment is the case to watch.
- Email Routing retries a worker that throws. There is no dead-letter queue for inbound mail —
  if ingestion is down long enough, the sending server eventually bounces the message.
