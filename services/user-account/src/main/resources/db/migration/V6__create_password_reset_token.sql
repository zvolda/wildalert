-- A hunter who forgets their password can get back in. Until this, the only route was SQL
-- against production -- and two traps in doing it by hand are written down in
-- docs/password-reset.md, because operators will still do it occasionally.
--
-- The channel is **email, not SMS**, and that decision is the reason this table exists rather
-- than a code column on hunter. A reset endpoint has to be unauthenticated -- being locked out is
-- the point -- so an SMS flow would let anyone on the internet spend prepaid GoSMS credit keyed
-- on someone else's address, which is the silent outage the credit guard exists to prevent.
-- See docs/password-reset.md for the full argument.

create table password_reset_token (
    -- The SHA-256 hex of the token, never the token itself -- exactly as hunter_session does it,
    -- and for the same reasons: a leak of this table must hand over no usable resets, the lookup
    -- is *by* the token so the digest has to be deterministic and unsalted, and there is nothing
    -- to brute-force because the input is 256 bits of randomness rather than something a human
    -- chose. varchar(64) not char(64), again as in V5: Postgres reports char as `bpchar`, which
    -- Hibernate's ddl-auto=validate rejects against a String mapping, and char blank-pads.
    token_hash varchar(64) primary key,

    hunter_id  uuid        not null references hunter (id),
    created_at timestamptz not null default now(),

    -- Short, unlike a session's 30 days. A reset link sits in an inbox forever; an hour is long
    -- enough to go and find the mail, short enough that a mailbox compromised next month is not
    -- a standing key to the account.
    expires_at timestamptz not null,

    -- Null means unused. Single use is enforced on this column rather than by deleting the row,
    -- so a replayed link is a *recognisably* spent token rather than an unknown one. Both answer
    -- the same generic 400 to the caller, but the difference is visible to us when a hunter says
    -- the link did not work.
    used_at    timestamptz
);

-- Supports both of the per-hunter operations: the throttle check ("has this hunter asked in the
-- last few minutes?") and invalidating every outstanding token when one is used.
create index password_reset_token_hunter_idx on password_reset_token (hunter_id);

-- Supports the expired-row sweep, which is pure waste without it. Same bargain as
-- hunter_session_expires_at_idx.
create index password_reset_token_expires_at_idx on password_reset_token (expires_at);
