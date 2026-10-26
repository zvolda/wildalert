-- One row per recognised animal: the hunter-facing detection history.
--
-- This file is read TWICE, which constrains how it is written:
--   1. Flyway runs it against Postgres at startup.
--   2. jOOQ's code generator (DDLDatabase) replays it into an in-memory H2 database to learn the
--      schema, so no live database is needed to build. See build.gradle.kts.
-- So every type here has to be spelled in a way BOTH understand: `timestamp with time zone`
-- rather than Postgres's `timestamptz` shorthand, and `varchar(n)` rather than `text`. Postgres
-- accepts the portable spellings; H2 does not accept the shorthands.

-- Flyway also creates this (flyway.schemas + create-schemas), but declaring it here is what lets
-- the script stand alone when H2 replays it, and keeps the table references below qualified.
create schema if not exists detection_history;

create table detection_history.detection (
    -- The AnimalRecognized event that produced this row. Delivery is at-least-once, so the same
    -- event can arrive twice; this primary key is the whole idempotency mechanism. Unlike
    -- notification -- which sends an SMS, an effect outside the database, and so needs a separate
    -- claim/release table -- here the write IS the effect, so `on conflict do nothing` is enough.
    source_event_id uuid         primary key,

    -- Who it belongs to: the hunter's inbound address, carried on the event from email-ingestion
    -- through recognition untouched, and stored lower-cased (the same normalisation
    -- user-account's InboundAddresses applies) so a read always matches a write. Stored rather
    -- than resolved to a hunter id on purpose, so recording a detection needs no synchronous call
    -- to user-account -- a cold-starting user-account can delay an SMS, but it must never be able
    -- to lose history.
    recipient       varchar(320),

    -- Only set on events published before the recipient switch (or replayed from a dead-letter
    -- topic); null on new ones. Kept so such an event is still stored instead of dead-lettered,
    -- even though the read API is keyed on recipient and will not show it -- preserving the row
    -- costs nothing, and resolving it back to an address would need the user-account call this
    -- service is deliberately built without.
    hunter_id       uuid,

    -- Where the photo lives in object storage (R2). Not a URL: the bucket is private, so the read
    -- side will hand out presigned URLs later.
    storage_key     varchar(512) not null,
    species         varchar(128) not null,
    confidence      double precision not null,

    -- True when the classifier was below the confidence threshold. The row is still kept: "we saw
    -- something and were unsure" is history worth showing, and it is what the threshold gets
    -- tuned against (Phase 7).
    low_confidence  boolean      not null,

    -- When the animal was photographed (from the event), vs. when we stored it. These differ by
    -- the pipeline's latency, and by much more for a replay -- so reporting must order by
    -- occurred_at, never by recorded_at.
    occurred_at     timestamp with time zone not null,
    recorded_at     timestamp with time zone not null default now(),

    -- Both owner columns are nullable on their own, but a row that belongs to nobody is a bug we
    -- would rather not be able to store. The database is the only place this can be guaranteed
    -- for every writer, now and later.
    constraint detection_owner_present check (recipient is not null or hunter_id is not null)
);

-- The dashboard's main query: one hunter's detections, newest first.
create index detection_recipient_occurred_at_idx
    on detection_history.detection (recipient, occurred_at desc);
