create table hunter (
    id         uuid         primary key,
    email      varchar(255) not null unique,
    phone      varchar(32)  not null,
    plan       varchar(32)  not null,
    active     boolean      not null default true,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);
