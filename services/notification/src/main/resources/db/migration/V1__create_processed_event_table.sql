-- One row per recognition result that has already produced (or is producing) an SMS.
-- Keyed by the ImageReceived event the result came from: that id survives redelivery, so a replayed
-- event hits the primary key and is skipped instead of texting the hunter twice.
create table processed_event (
    source_event_id uuid        primary key,
    processed_at    timestamptz not null default now()
);
