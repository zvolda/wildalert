-- A hunter can now log in. Until this, the only thing identifying a hunter was their inbound
-- address -- and that is a routing token, printed on a web page and typed into a camera, not a
-- credential. Logging in is what makes a *returning* hunter possible: before this, the personal
-- address was shown exactly once and the credit balance could not be seen at all.

-- Nullable on purpose. Real hunter rows already exist in production Cloud SQL and have no
-- password, and a not-null column would have to invent one for them. A null hash means "cannot
-- log in" and must answer the *same* generic 401 as a wrong password -- anything more specific
-- turns this column into an account-existence oracle.
--
-- varchar(100), not 60. BCrypt's output is exactly 60 characters, but Spring Security's
-- DelegatingPasswordEncoder prefixes the algorithm ({bcrypt} -> 68), and Argon2id's encoded form
-- runs to ~96. A column sized to today's algorithm turns tomorrow's upgrade into a migration
-- done under pressure, which is the wrong time to be altering a table.
alter table hunter add column password_hash varchar(100);

-- Server-side sessions. Not an in-memory HttpSession: user-account runs on Cloud Run, where a
-- second instance can appear at any moment, and a session held in one instance's heap would
-- simply not exist on the other -- a hunter would be randomly logged out.
create table hunter_session (
    -- The SHA-256 hex of the token, never the token itself. A leak of this table must hand over
    -- no usable sessions. Unlike a password this is hashed *without* a salt and with a fast
    -- digest, deliberately: we have to look a session up by its token, so the hash has to be
    -- deterministic, and there is nothing to brute-force because the input is 256 bits of
    -- randomness rather than something a human chose.
    --
    -- varchar(64), not char(64), for two reasons that bit in that order: Postgres reports char as
    -- `bpchar`, which Hibernate's ddl-auto=validate rejects against a String mapping, and char
    -- blank-pads its values, so a 64-character hash compared against a padded column is a class
    -- of bug nobody should have to find. The length is exact either way.
    token_hash varchar(64) primary key,
    hunter_id  uuid        not null references hunter (id),
    created_at timestamptz not null default now(),

    -- Absolute expiry, set once at login. Sliding expiry would mean a write on every single
    -- authenticated request to buy a convenience nobody has asked for.
    expires_at timestamptz not null
);

-- Supports "log this hunter out everywhere" (and the cascade if a hunter is ever deleted).
create index hunter_session_hunter_idx on hunter_session (hunter_id);

-- Supports the expired-row sweep. Cheap to maintain and the sweep is pure waste without it.
create index hunter_session_expires_at_idx on hunter_session (expires_at);
