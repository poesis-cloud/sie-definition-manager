begin;
create table if not exists dm_service_heartbeat (
  id bigserial primary key,
  created_at timestamptz not null default now(),
  service_name text not null,
  service_version text not null
);
commit;