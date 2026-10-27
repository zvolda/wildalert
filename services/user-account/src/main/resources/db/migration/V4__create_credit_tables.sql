-- Prepaid credit. Replaces the FREE/BASIC/PRO plan enum dropped in V3: a hunter tops up, each
-- photo processed debits 1.00 CZK and each alert sent a further 1.50, and at zero we stop.
--
-- Money is stored in CZK minor units (haléře) rather than message counts, because the per-SMS
-- price moves (1.20 CZK on the GoSMS trial channel today against a 0.26 CZK headline on a
-- production one), a Czech-language body can cost two parts, and what we charge is deliberately
-- not what we pay -- the difference is the margin.
--
-- Nothing spends credit yet. This migration and the code on top of it only create the balance
-- and grant it; the debits arrive in later slices.

-- The current balance: one row per hunter, and the only mutable row in this feature.
create table hunter_credit (
    hunter_id     uuid        primary key references hunter (id),
    balance_minor bigint      not null default 0,
    currency      varchar(3)  not null default 'CZK',

    -- 0 = nothing said, 1 = low-balance warning appended to an alert, 2 = out-of-credit SMS sent.
    -- Raised inside the charge transaction and reset to 0 by any top-up, so a hunter who runs dry
    -- is told once per depletion rather than once per photo.
    warning_level smallint    not null default 0,
    updated_at    timestamptz not null default now(),

    -- A debit is guarded (`... where balance_minor >= :cost`), so the balance can never go below
    -- zero through the normal path. This turns any future code that forgets the guard into a
    -- failed transaction instead of silent debt. A reversal that would go negative therefore
    -- fails loudly -- deliberate.
    constraint hunter_credit_not_negative check (balance_minor >= 0),
    constraint hunter_credit_warning_level check (warning_level between 0 and 2)
);

-- Append-only history. Every movement of credit, and the source of truth the balance is checked
-- against. Nothing in this table is ever updated or deleted.
create table credit_ledger (
    id                  uuid        primary key,
    hunter_id           uuid        not null references hunter (id),

    -- Positive = credit added (grant, top-up), negative = credit spent.
    amount_minor        bigint      not null,
    currency            varchar(3)  not null default 'CZK',
    entry_type          varchar(32) not null,

    -- What caused it, and the whole idempotency mechanism: the AnimalRecognized `sourceEventId`
    -- for a debit (it survives redelivery; `eventId` does not), the Stripe payment-intent id for
    -- a TOPUP. Null for a SIGNUP_GRANT or a manual ADJUSTMENT.
    external_ref        varchar(255),

    -- Denormalised so a statement reads without a window function, and so drift between this
    -- table and hunter_credit is one comparison rather than a full re-sum.
    balance_after_minor bigint      not null,
    created_at          timestamptz not null default now(),

    constraint credit_ledger_type_known check (
        entry_type in ('SIGNUP_GRANT', 'TOPUP', 'SMS_DEBIT', 'RECOGNITION_DEBIT', 'ADJUSTMENT', 'REVERSAL')
    ),
    -- A debit amount will arrive over HTTP from another service, so it is input, not truth. One
    -- alert cannot plausibly cost more than 100 CZK; a top-up of more than 100 000 CZK is a bug.
    constraint credit_ledger_amount_sane check (amount_minor between -10000 and 10000000)
);

-- The same cause can never be applied twice: a redelivered AnimalRecognized, a retried Stripe
-- webhook, or a success page and a webhook racing each other all collapse to one row.
-- Partial, because SIGNUP_GRANT and ADJUSTMENT have no external_ref and several nulls must coexist.
create unique index credit_ledger_external_ref_uk
    on credit_ledger (entry_type, external_ref)
    where external_ref is not null;

create index credit_ledger_hunter_created_at_idx
    on credit_ledger (hunter_id, created_at desc);

-- Existing hunters get a credit row and the same signup grant a new registration gets.
-- 2500 haléře is ten alerts with their recognition (100 + 150 each). In practice it buys fewer,
-- because every photo pays the 100 whether or not it produces an alert. Matches
-- SIGNUP_GRANT_MINOR's default; if that is changed before this runs, change it here too.
insert into hunter_credit (hunter_id, balance_minor)
select id, 2500 from hunter;

insert into credit_ledger (id, hunter_id, amount_minor, entry_type, external_ref, balance_after_minor)
select gen_random_uuid(), id, 2500, 'SIGNUP_GRANT', null, 2500 from hunter;
