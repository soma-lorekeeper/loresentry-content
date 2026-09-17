create table image (
    id            uuid        primary key,
    project_id    uuid        not null,
    file_name     text        not null,
    s3_key        text        not null unique,
    content_type  text        not null,
    size_bytes    bigint      not null check (size_bytes > 0),
    status        text        not null check (status in ('PENDING', 'COMMITTED')),
    created_at    timestamptz not null default now(),
    committed_at  timestamptz
);

create index image_project_id_idx on image (project_id, created_at desc);
