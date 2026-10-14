/**
 * Cloudflare Email Worker: the bridge between Email Routing and email-ingestion.
 *
 * Every address on the zone is routed here by the apex catch-all, so a hunter's personal
 * address (`<token>@wildalert.app`) needs no per-hunter setup on the Cloudflare side. The
 * worker POSTs the raw RFC-822 bytes to the ingestion webhook and passes the SMTP envelope
 * recipient as `?envelopeTo=` — `message.to` is the RCPT TO, which is what identifies the
 * hunter. The email's own headers are only a fallback, because `From` is spoofable and
 * auto-forwarding rewrites it.
 *
 * Failure handling: throwing makes Email Routing retry the message later. `setReject()` is
 * deliberately NOT used for our own outages — it bounces the mail, and some senders
 * (Outlook) then retry every hour for hours without backoff. Reject only what we will never
 * accept.
 *
 * Secrets (set with `wrangler secret put`, never in wrangler.toml):
 *   INGESTION_URL  - https://email-ingestion-....run.app (no trailing slash)
 *   WEBHOOK_SECRET - must equal email-ingestion's WEBHOOK_SECRET
 */

// Cloudflare accepts up to 25 MiB inbound; Cloud Run's request limit is 32 MiB, so the
// envelope is the binding constraint. Trail-cam photos are a few hundred KB.
const MAX_SIZE_BYTES = 25 * 1024 * 1024;

export default {
  async email(message, env) {
    if (!env.INGESTION_URL || !env.WEBHOOK_SECRET) {
      // A config error, not a mail error: retry rather than bounce the hunter's photo.
      throw new Error('INGESTION_URL or WEBHOOK_SECRET is not set on the worker');
    }

    if (message.rawSize > MAX_SIZE_BYTES) {
      message.setReject('Message too large');
      return;
    }

    const raw = await new Response(message.raw).arrayBuffer();
    const url = `${env.INGESTION_URL}/api/emails?envelopeTo=${encodeURIComponent(message.to)}`;

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'content-type': 'message/rfc822',
        'x-webhook-secret': env.WEBHOOK_SECRET,
      },
      body: raw,
    });

    if (!response.ok) {
      // Includes 401 (secret mismatch) on purpose: that is our bug, and a retry after the
      // fix still delivers the photo. Dropping it here would lose it silently.
      const body = await response.text().catch(() => '');
      throw new Error(`ingestion returned ${response.status}: ${body.slice(0, 500)}`);
    }

    console.log(`delivered ${message.rawSize}B for ${message.to} -> ${response.status}`);
  },
};
