# Password reset — design and plan

_Written 2026-10-30, before implementation, so the design survives independently of the
conversation that produced it. Companion to [`ROADMAP.md`](../ROADMAP.md) (Phase 9,
"Authentication/authorization") and [`STATUS.md`](../STATUS.md)._

## The gap this closes

A hunter can register and log in (Phase 8 slice 3, 2026-10-30), and **nothing in the product can
change a password**. Not only is there no "forgot password" — there is no change-password-while-
signed-in either. Verified in the code rather than the notes:

- `UpdateHunterRequest` (`hunter/HunterDtos.kt`) carries `phone` and `active`, nothing else.
- `Hunter.setPassword()` is called from exactly one place, `HunterService.register()`.
- There is no admin endpoint.

So today a reset means SQL against production Cloud SQL. Two traps there, both worth knowing even
after this feature ships, because operators will still do it occasionally:

- The encoder is `PasswordEncoderFactories.createDelegatingPasswordEncoder()`, so the stored value
  **must carry the `{bcrypt}` prefix**. A bare `$2a$…` from an online generator never matches.
- `password_hash = NULL` does **not** clear the password open. Null means "cannot log in" and
  answers the same generic 401 (see `V5__add_hunter_password_and_session.sql`). It locks the
  account.

```bash
htpasswd -bnBC 10 "" 'new-password' | tr -d ':\n' | sed 's/^/{bcrypt}/'
```
```sql
UPDATE hunter SET password_hash = '{bcrypt}$2y$10$…', updated_at = now() WHERE email = '…';
DELETE FROM hunter_session WHERE hunter_id = (SELECT id FROM hunter WHERE email = '…');
```
The `DELETE` matters: sessions are independent rows, so changing a hash does not end existing logins.

## Channel decision: email for reset, SMS for phone verification

STATUS.md said reset was "blocked on outbound email, which this project has never had". **That
premise was wrong twice over**, and both corrections are the reason this document exists.

**1. Outbound email is not blocked.** Cloudflare Email Routing is inbound-only, true, but the apex
MX governs *inbound* mail only. Sending from `wildalert.app` through any provider needs a DNS edit,
not a re-architecture. Measured 2026-10-30:

- SPF today is `v=spf1 include:_spf.mx.cloudflare.net ~all` — only Email Routing's forwarding
  include. A sending provider's include goes alongside it; no MX conflict.
- There is **no DMARC record at all** (`_dmarc.wildalert.app` does not resolve). One should be
  added with the sending setup regardless of provider.

**2. SMS looked attractive and is the wrong choice for reset.** The first instinct was to reuse
GoSMS — no new vendor, the phone is already on file, and it is the channel the product already
delivers on. That was outweighed:

- **A reset endpoint must be unauthenticated** — being locked out is the point — so anyone on the
  internet can make it spend money keyed on *someone else's* email address. `alert-price-minor` is
  250 haléře end to end, so the SMS component is on the order of a koruna; a few thousand scripted
  requests drain a **prepaid** balance whose depletion is a silent outage for *paying* hunters.
  That is the exact failure mode the GoSMS credit guard was built for (2026-10-30), approached from
  a direction the guard does not cover: the guard refuses an alert it cannot pay for, but by then
  the credit is already gone. An email flood is a nuisance; an SMS flood is a bill and an outage.
- **SMS reset is a documented account-takeover path** (SIM swap). NIST classes SMS as a
  *restricted* authenticator channel. Password reset is the highest-value flow in the app and so
  the wrong place for the weakest channel.
- **Email is the login handle.** Hunters authenticate with an email address; reset belongs on the
  identity you authenticate with.

What survives of the SMS argument, honestly: no new vendor (which here also means no extra
licence/policy review), and **deliverability** — a weeks-old `.app` domain with no sending
reputation will put a share of its transactional mail in spam, while SMS arrives. That is a reason
to pick a provider with real reputation handling and to set DKIM and DMARC up properly, not a
reason to spend prepaid credit on resets.

**The split, therefore:** each channel proves the thing it is actually evidence of.

| Flow | Channel | Why |
|---|---|---|
| Password reset | **Email** | It is the login identity; near-zero marginal cost under flood |
| Phone verification (separate, open Phase 8 task) | **SMS** | The phone *is* what is being proven; spend is bounded behind registration + Turnstile |

## Scope

Three slices. A is independent and ships first; B depends on a provider decision; C is UI.

### Slice A — change password while signed in (no new infrastructure)

`POST /api/me/password`, session-authenticated, body `{currentPassword, newPassword}`.

- Re-verifies `currentPassword` even though a valid session is present. A session may be an
  unattended browser, and this endpoint hands over permanent control of the account.
- `newPassword` validated against the existing `PASSWORD_MIN_LENGTH`/`PASSWORD_MAX_LENGTH` (10/72).
- On success: write the new hash, then **`deleteByHunterId`** — that repository method already
  exists for "log out everywhere" — and issue a fresh cookie for the caller, so changing a password
  evicts every other browser without logging out the person who just did it.
- Wrong `currentPassword` → 401 via the existing `InvalidCredentialsException`, which already
  carries no detail by design.

This is worth doing first on its own merits: zero new infrastructure, no channel decision, and a
hunter who merely *suspects* a leak can act today.

### Slice B — forgot password, by email

Two endpoints, deliberately not one:

1. `POST /api/password-resets` body `{email}` → **always `202`**, whether or not the address is a
   hunter. Anything else is an account-existence oracle, and the generic-401 reasoning in
   `HunterExceptions.kt` applies with more force here because this endpoint is public.
2. `POST /api/password-resets/confirm` body `{token, newPassword}` → `204`, then the hunter logs
   in normally. Invalid, expired, used or unknown token → one generic `400`.

**Token design mirrors `hunter_session`**, which is the pattern already proven in this codebase:
32 bytes from `SecureRandom`, base64url (43 chars), and only its **SHA-256 hex** is stored.
Unsalted fast digest is correct here for the same reason as sessions — lookup is *by* the token and
the input is 256 bits of randomness, not something a human chose. Reuse `SessionService.newToken()`
and `hash()` rather than writing a second copy.

Flyway `V6__create_password_reset_token.sql`:

```
token_hash  varchar(64) primary key
hunter_id   uuid        not null references hunter (id)
created_at  timestamptz not null default now()
expires_at  timestamptz not null
used_at     timestamptz             -- null = unused; single use is enforced on this
```
plus an index on `hunter_id` (throttle lookups and "invalidate all on use") and one on `expires_at`
(the sweep). Avoid `text`/`timestamp` shorthands only where jOOQ's `DDLDatabase` reads them —
that is detection-history, not here — but match the existing style anyway.

**Rules that make it safe:**

- **1 hour expiry.** Long enough to find the mail, short enough that a leaked inbox later is not a
  standing key.
- **Single use**, enforced by `used_at`, and on use **every other token for that hunter is deleted**
  and **`deleteByHunterId` ends every session** — a reset is exactly the moment to evict whoever
  may already be in the account.
- **Per-email throttle** (one token per ~5 minutes), a cheap `created_at` check on the existing
  `hunter_id` index. Protects a real hunter's inbox from being used as a spam relay. It is *not* a
  substitute for Turnstile, which is a separate open item and belongs in front of this form too.
- **Timing:** the 202 path must not be obviously faster for a non-existent address. Not a
  cryptographic guarantee here, just don't make it trivially observable.
- Reset is refused for a hunter with `active = false`, and (as today) a null `password_hash` is not
  a special case worth revealing.

**`EmailSender` follows the house swappable-interface rule** (AGENTS.md "Core conventions"):
interface + `LoggingEmailSender` as the **default** (prints the link, which is also how local dev
and tests run with zero infrastructure) + one real implementation chosen by config
(`EMAIL_PROVIDER`). Mirrors `SmsSender`/`ImageStore`/`EventPublisher` exactly. Money's "no fake, no
off switch" exception does **not** apply — nothing here moves credit.

The link in the mail is `https://wildalert.app/reset?token=…`, built from a configured base URL
(`PUBLIC_BASE_URL`), never from the inbound `Host` header — a header-derived link is a
password-reset-poisoning vector.

### Slice C — the two screens

`web/`: a "Forgot password?" link on the sign-in form → request screen (always shows the same
"check your inbox" confirmation); a `/reset?token=…` screen → new-password form → on success, send
them to sign-in. Plus a change-password form on the dashboard for slice A. The gateway's route
allowlist needs the new paths — it is deliberately tiny, so each one is an explicit line.

## Left to do outside the code

- **Pick a sending provider.** Resend or Postmark (simplest API, good reputation), AWS SES
  (cheapest at volume, more setup), or **Cloudflare Email Sending** — no new vendor relationship,
  which matters here, but it sends from a Worker binding, so Kotlin would call a small Worker
  endpoint rather than the provider directly. A new vendor needs the usual licence/policy check.
- **DNS:** the provider's SPF include added alongside Cloudflare's, DKIM CNAMEs, and a `_dmarc`
  TXT record (there is none today). Dashboard work — `wrangler`'s token is `zone:read`.
- **Secret:** the provider API key in Secret Manager with a **per-secret** binding to
  user-account's service account, not the project-level `secretAccessor` it currently holds (that
  over-grant is already a tracked Phase 7 item; do not widen it further here).
- **Env on the revision:** `EMAIL_PROVIDER`, `PUBLIC_BASE_URL=https://wildalert.app`, the sender
  address. Use `--update-env-vars`, never `--set-env-vars`, which drops `DATABASE_URL`,
  `POSTGRES_USER`, `INBOUND_EMAIL_DOMAIN` and the password secret reference.
- **Deploy order:** slices A and B are purely additive to the API, so user-account may ship before
  the UI. That is the opposite of the hunter-login deploy, where `password` became a *required*
  field and the old client broke for six minutes — the rule being: when a field becomes required,
  client and server ship together or the client ships first.

## Out of scope, on purpose

- **Phone verification** — SMS, its own slice, the other half of the channel split above.
- **Turnstile / brute-force protection** — a separate slice that belongs *in front of* this one;
  the reset form is one more public form that wants it. Note the login form still has none.
- **Email change** — a second verified-channel flow; resetting a password does not need it.
- **2FA.**

## Test plan

Unit/web-layer, no infrastructure (the house standard):

- change password: happy path; wrong current password → 401; too-short new password → 400;
  no session → 401; other sessions evicted, caller's own still valid.
- request reset: unknown email → 202 and **no** token row; known email → 202 and one row, hashed,
  never the raw token; throttle → second immediate request creates no second row.
- confirm: happy path → hash changed, `used_at` set, all sessions gone; replay the same token →
  400; expired token → 400; unknown token → 400; too-short password → 400.
- `LoggingEmailSender` never logs the raw token at a level that would ship it to Cloud Logging in
  production — the link is the credential.
