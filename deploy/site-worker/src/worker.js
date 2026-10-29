/**
 * Cloudflare Worker: serves wildalert.app from the Cloud Run gateway.
 *
 * Why a worker and not a DNS record. Cloud Run routes by Host header -- a request arriving at
 * the `*.run.app` front door with `Host: wildalert.app` matches no service and gets a 404. The
 * fix is either to teach Cloud Run that hostname (a domain mapping, which does not exist in
 * europe-west3 and is preview-only anyway) or to send the Host it already knows. Cloudflare can
 * rewrite the Host header natively, but only on Enterprise. A worker does it in one line, on the
 * free plan, so that is what this is: a reverse proxy whose entire job is the Host header.
 *
 * It also owns the canonical hostname. www 301s to the apex here rather than in a Cloudflare
 * redirect rule, so there is one place to look and nothing in the dashboard to keep in sync with
 * this file. The apex is canonical because hunter addresses are already `<token>@wildalert.app`
 * -- the same string on the page and in the email.
 *
 * What it deliberately does NOT do:
 * - **No caching.** Nothing opts into `cacheEverything`, and the gateway sends no `Cache-Control`
 *   on its static files, so Cloudflare caches nothing. That is on purpose: the React app is built
 *   into the gateway's own jar precisely so the page and the API it calls can never be different
 *   versions, and a cached `index.html` surviving a deploy is exactly how that guarantee would be
 *   lost. Caching the hashed `assets/*` files is safe and worth doing later -- but as a rule that
 *   names them, never as a blanket one.
 * - **No auth, no rate limiting, no body inspection.** It is a pipe. Abuse control is a
 *   Cloudflare rule in front of this worker (step 11c) or Turnstile on the form -- neither of
 *   which belongs in proxy code.
 *
 * Vars (committed, in wrangler.toml):
 *   ORIGIN_HOST     - the Cloud Run gateway hostname, e.g. gateway-xxxx-ey.a.run.app
 *   CANONICAL_HOST  - the one hostname the site is served on
 * Secret (optional, `wrangler secret put`):
 *   ORIGIN_SECRET   - sent as x-origin-secret; see README, "Closing the bypass"
 */

export default {
  async fetch(request, env) {
    if (!env.ORIGIN_HOST || !env.CANONICAL_HOST) {
      // Misconfiguration, not a client error. 500 and a log line, because a 404 or a redirect
      // loop here would look like a DNS problem and send you looking in the wrong place.
      console.error('ORIGIN_HOST or CANONICAL_HOST is not set on the worker');
      return new Response('Worker is misconfigured', { status: 500 });
    }

    const url = new URL(request.url);

    // One redirect handles both "not HTTPS" and "not the canonical hostname", so http://www/x
    // reaches https://wildalert.app/x in a single hop rather than two. Path and query are
    // carried over, and 301 because neither the scheme nor the choice of apex is going to change.
    //
    // **The HTTPS half is deliberately here and not Cloudflare's "Always Use HTTPS" toggle.**
    // That is a zone setting, and the credentials this worker deploys with cannot reach zone
    // settings -- so doing it in code is both the only self-contained option and the one that
    // leaves nothing to remember in a dashboard.
    if (url.protocol !== 'https:' || url.hostname !== env.CANONICAL_HOST) {
      url.protocol = 'https:';
      url.hostname = env.CANONICAL_HOST;
      // Clearing the port matters: an http request carries :80, which would otherwise survive
      // into the https URL and produce a redirect to https://wildalert.app:80/.
      url.port = '';
      return Response.redirect(url.toString(), 301);
    }

    // The one thing this worker is for: same path, same query, same method, same body -- new
    // hostname. The runtime derives the Host header from the URL, so changing it here is what
    // makes Cloud Run recognise the request.
    const originUrl = new URL(request.url);
    originUrl.hostname = env.ORIGIN_HOST;
    originUrl.protocol = 'https:';
    originUrl.port = '';

    // `new Request(url, request)` is Cloudflare's clone-with-a-different-URL form: it carries the
    // method, headers and body over without the worker having to touch the body at all. Its
    // headers are mutable, unlike the incoming request's.
    const proxied = new Request(originUrl, request);
    proxied.headers.delete('host');

    // Cloud Run overwrites X-Forwarded-For with its own peer, which from here is Cloudflare --
    // so the hunter's address would be lost. Pass it separately. Nothing reads this header yet;
    // it exists so that per-IP logic in the gateway is a code change and not also a worker
    // change. Trust it only behind ORIGIN_SECRET: a client can send any header it likes.
    const clientIp = request.headers.get('cf-connecting-ip');
    if (clientIp) {
      proxied.headers.set('x-client-ip', clientIp);
    }

    // The public hostname, for whenever the gateway needs to generate an absolute URL. Spring
    // ignores X-Forwarded-* unless `server.forward-headers-strategy` is set, and it is not, so
    // today this is only information for the logs.
    proxied.headers.set('x-forwarded-host', env.CANONICAL_HOST);

    // Present only once the secret is set, and harmless until the gateway requires it.
    if (env.ORIGIN_SECRET) {
      proxied.headers.set('x-origin-secret', env.ORIGIN_SECRET);
    }

    try {
      // Redirects are left on `follow`, the default. The gateway has one proxied route and a
      // pile of static files and issues no redirects of its own, so there is nothing to preserve
      // yet; revisit this if it ever starts answering 3xx, since following them here would hide
      // a redirect the browser should have seen.
      return await fetch(proxied);
    } catch (error) {
      // A cold Cloud Run instance that fails to start, or a network fault between Cloudflare and
      // Google. 502 is the honest answer -- the worker is fine, the thing behind it is not.
      console.error(`origin fetch failed for ${url.pathname}: ${error}`);
      return new Response('Upstream unavailable', { status: 502 });
    }
  },
};
