-- Personal inbound email address per hunter: <inbound_token>@<inbound domain>.
-- Only the token is stored; the domain is config (differs per environment), see InboundAddresses.
alter table hunter add column inbound_token varchar(32);

-- Give hunters that already exist a token too (random, 12 lowercase hex chars).
update hunter
set inbound_token = substr(md5(random()::text || id::text), 1, 12)
where inbound_token is null;

alter table hunter alter column inbound_token set not null;
alter table hunter add constraint uk_hunter_inbound_token unique (inbound_token);
