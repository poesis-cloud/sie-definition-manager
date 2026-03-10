-- V8: Interface owns exposure relationship via join tables.
-- Replaces effector.interface_id and receptor.interface_id with
-- interface_effector and interface_receptor join tables.

begin;

-- Create join tables
create table if not exists interface_effector (
    interface_id uuid not null references interface (id),
    effector_id  uuid not null references effector (id),
    primary key (interface_id, effector_id)
);

create table if not exists interface_receptor (
    interface_id uuid not null references interface (id),
    receptor_id  uuid not null references receptor (id),
    primary key (interface_id, receptor_id)
);

-- Migrate existing data
insert into interface_effector (interface_id, effector_id)
select interface_id, id
from effector
where interface_id is not null
on conflict do nothing;

insert into interface_receptor (interface_id, receptor_id)
select interface_id, id
from receptor
where interface_id is not null
on conflict do nothing;

-- Drop old partial indexes
drop index if exists idx_effector_interface;
drop index if exists idx_receptor_interface;

-- Drop old FK columns
alter table effector drop column if exists interface_id;
alter table receptor drop column if exists interface_id;

commit;
