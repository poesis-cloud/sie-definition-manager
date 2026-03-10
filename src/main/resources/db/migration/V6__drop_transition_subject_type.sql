-- ============================================================
-- V6: Remove subject_type from ascription_status_transition
-- ============================================================
-- Rationale: subject_type is not part of the GSM AscriptionStatusTransition
-- model. It was a routing denormalization. With TABLE_PER_CLASS inheritance
-- and globally unique ascription IDs (UUIDv7), the owning table can be
-- derived at query time via the union of class tables, and triggers derive
-- the table name internally.
-- ============================================================
begin;
-- ============================================================
-- 1. DROP INDEXES that include subject_type
-- ============================================================
drop index if exists idx_ast_type_ascription;
-- (subject_type, ascription_id)
drop index if exists idx_ast_latest_lookup;
-- (subject_type, ascription_id, timestamp desc, id desc)
drop index if exists uq_ast_initial;
-- (subject_type, ascription_id) WHERE pre_status IS NULL
drop index if exists idx_ast_edge;
-- (subject_type, ascription_id, pre_status, post_status)
-- Recreate indexes without subject_type
create unique index uq_ast_initial on ascription_status_transition (ascription_id)
where pre_status is null;
create index idx_ast_latest_lookup on ascription_status_transition (ascription_id, "timestamp" desc, id desc);
create index idx_ast_edge on ascription_status_transition (ascription_id, pre_status, post_status);
-- ============================================================
-- 2. REPLACE tgf_assert_transition_owner_exists
--    Validates owner existence only (no subject_type check needed).
-- ============================================================
create or replace function tgf_assert_transition_owner_exists() returns trigger language plpgsql as $$
declare owner_count integer;
begin
select count(*) into owner_count
from (
        select 1
        from archetype
        where id = NEW.ascription_id
        union all
        select 1
        from structure
        where id = NEW.ascription_id
        union all
        select 1
        from mechanism
        where id = NEW.ascription_id
        union all
        select 1
        from interface
        where id = NEW.ascription_id
        union all
        select 1
        from effector
        where id = NEW.ascription_id
        union all
        select 1
        from receptor
        where id = NEW.ascription_id
        union all
        select 1
        from interaction
        where id = NEW.ascription_id
        union all
        select 1
        from directive
        where id = NEW.ascription_id
        union all
        select 1
        from norm
        where id = NEW.ascription_id
    ) owners;
if owner_count <> 1 then raise exception 'ascription_status_transition.ascription_id % must belong to exactly one owner row; found %',
NEW.ascription_id,
owner_count;
end if;
return NEW;
end;
$$;
-- ============================================================
-- 3. REPLACE tgf_sync_owner_status_from_transition
--    Derives the owner table name from the union query instead
--    of reading NEW.subject_type.
-- ============================================================
create or replace function tgf_sync_owner_status_from_transition() returns trigger language plpgsql as $$
declare owner_table text;
next_version integer;
begin perform set_config('sif.status_sync', 'on', true);
-- Derive which table owns this ascription_id
select tbl into owner_table
from (
        select 'archetype' as tbl
        from archetype
        where id = NEW.ascription_id
        union all
        select 'structure'
        from structure
        where id = NEW.ascription_id
        union all
        select 'mechanism'
        from mechanism
        where id = NEW.ascription_id
        union all
        select 'interface'
        from interface
        where id = NEW.ascription_id
        union all
        select 'effector'
        from effector
        where id = NEW.ascription_id
        union all
        select 'receptor'
        from receptor
        where id = NEW.ascription_id
        union all
        select 'interaction'
        from interaction
        where id = NEW.ascription_id
        union all
        select 'directive'
        from directive
        where id = NEW.ascription_id
        union all
        select 'norm'
        from norm
        where id = NEW.ascription_id
    ) owners;
if owner_table is null then raise exception 'No owner table found for ascription_id %',
NEW.ascription_id;
end if;
if NEW.post_status = 'APPROVED' then execute format(
    'select coalesce(max(o.version), 0) + 1
       from %I o
       where o.definition_id = (select definition_id from %I where id = $1)',
    owner_table,
    owner_table
) into next_version using NEW.ascription_id;
execute format(
    'update %I set status = $1, version = $2 where id = $3',
    owner_table
) using NEW.post_status,
next_version,
NEW.ascription_id;
else execute format(
    'update %I set status = $1 where id = $2',
    owner_table
) using NEW.post_status,
NEW.ascription_id;
end if;
perform set_config('sif.status_sync', 'off', true);
return NEW;
end;
$$;
-- ============================================================
-- 4. REPLACE tgf_assert_owner_status_matches_history
--    Filter transitions by ascription_id only (globally unique).
-- ============================================================
create or replace function tgf_assert_owner_status_matches_history() returns trigger language plpgsql as $$
declare latest_post_status ascription_status;
begin
select ast.post_status into latest_post_status
from ascription_status_transition ast
where ast.ascription_id = NEW.id
order by ast."timestamp" desc,
    ast.id desc
limit 1;
if latest_post_status is null then raise exception 'owner %.% must have at least one status transition',
TG_TABLE_NAME,
NEW.id;
end if;
if NEW.status is distinct
from latest_post_status then raise exception 'owner %.% status % does not match latest transition status %',
    TG_TABLE_NAME,
    NEW.id,
    NEW.status,
    latest_post_status;
end if;
return NEW;
end;
$$;
-- ============================================================
-- 5. REPLACE tgf_restrict_owner_delete_when_transitions_exist
--    Filter transitions by ascription_id only (globally unique).
-- ============================================================
create or replace function tgf_restrict_owner_delete_when_transitions_exist() returns trigger language plpgsql as $$ begin if exists (
        select 1
        from ascription_status_transition ast
        where ast.ascription_id = OLD.id
    ) then raise exception 'cannot delete %.% while status transitions exist',
    TG_TABLE_NAME,
    OLD.id;
end if;
return OLD;
end;
$$;
-- ============================================================
-- 6. DROP the column
-- ============================================================
alter table ascription_status_transition drop column subject_type;
commit;