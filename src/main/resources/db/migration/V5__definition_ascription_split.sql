-- ============================================================
-- V5__definition_ascription_split.sql
-- Implements the Definition/Ascription conceptual split from GSM.
--
-- Definition = stable identity of a governed subject (new table).
-- Ascription = governed normative snapshot of a Definition.
--
-- Column renames across all 9 class tables:
--   id              → definition_id  (FK to definition table)
--   revision_id     → id             (Ascription PK)
--   revision_timestamp → timestamp
--   definition      → statement      (JSON payload)
--
-- Transition table renames:
--   gsm_type    → subject_type  (cast text → definition_subject_type enum)
--   revision_id → ascription_id
--
-- Source: gsm.puml (Definition/Ascription split)
-- ============================================================
begin;
-- ============================================================
-- 1. NEW ENUM + TABLE
-- ============================================================
create type definition_subject_type as enum (
  'ARCHETYPE',
  'STRUCTURE',
  'MECHANISM',
  'INTERFACE',
  'EFFECTOR',
  'RECEPTOR',
  'INTERACTION',
  'DIRECTIVE',
  'NORM'
);
create table definition (
  id uuid not null,
  subject_type definition_subject_type not null,
  constraint definition_pk primary key (id)
);
create index idx_definition_subject_type on definition (subject_type);
-- ============================================================
-- 2. BACKFILL definition (using old column names, before rename)
-- ============================================================
insert into definition (id, subject_type)
select distinct id,
  'ARCHETYPE'::definition_subject_type
from archetype on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'STRUCTURE'::definition_subject_type
from structure on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'MECHANISM'::definition_subject_type
from mechanism on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'INTERFACE'::definition_subject_type
from interface on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'EFFECTOR'::definition_subject_type
from effector on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'RECEPTOR'::definition_subject_type
from receptor on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'INTERACTION'::definition_subject_type
from interaction on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'DIRECTIVE'::definition_subject_type
from directive on conflict do nothing;
insert into definition (id, subject_type)
select distinct id,
  'NORM'::definition_subject_type
from norm on conflict do nothing;
-- ============================================================
-- 3. DROP VIEW (depends on old column names)
-- ============================================================
drop view if exists ascription_all;
-- ============================================================
-- 4. RENAME COLUMNS — 9 CLASS TABLES
--    Order: id → definition_id FIRST, then revision_id → id
--    to avoid name collision.
-- ============================================================
-- archetype
alter table archetype
  rename column id to definition_id;
alter table archetype
  rename column revision_id to id;
alter table archetype
  rename column revision_timestamp to "timestamp";
alter table archetype
  rename column definition to statement;
-- structure
alter table structure
  rename column id to definition_id;
alter table structure
  rename column revision_id to id;
alter table structure
  rename column revision_timestamp to "timestamp";
alter table structure
  rename column definition to statement;
-- mechanism
alter table mechanism
  rename column id to definition_id;
alter table mechanism
  rename column revision_id to id;
alter table mechanism
  rename column revision_timestamp to "timestamp";
alter table mechanism
  rename column definition to statement;
-- interface
alter table interface
  rename column id to definition_id;
alter table interface
  rename column revision_id to id;
alter table interface
  rename column revision_timestamp to "timestamp";
alter table interface
  rename column definition to statement;
-- effector
alter table effector
  rename column id to definition_id;
alter table effector
  rename column revision_id to id;
alter table effector
  rename column revision_timestamp to "timestamp";
alter table effector
  rename column definition to statement;
-- receptor
alter table receptor
  rename column id to definition_id;
alter table receptor
  rename column revision_id to id;
alter table receptor
  rename column revision_timestamp to "timestamp";
alter table receptor
  rename column definition to statement;
-- interaction
alter table interaction
  rename column id to definition_id;
alter table interaction
  rename column revision_id to id;
alter table interaction
  rename column revision_timestamp to "timestamp";
alter table interaction
  rename column definition to statement;
-- directive
alter table directive
  rename column id to definition_id;
alter table directive
  rename column revision_id to id;
alter table directive
  rename column revision_timestamp to "timestamp";
alter table directive
  rename column definition to statement;
-- norm
alter table norm
  rename column id to definition_id;
alter table norm
  rename column revision_id to id;
alter table norm
  rename column revision_timestamp to "timestamp";
alter table norm
  rename column definition to statement;
-- ============================================================
-- 5. ADD FK CONSTRAINTS: definition_id → definition.id
-- ============================================================
alter table archetype
add constraint archetype_definition_fk foreign key (definition_id) references definition (id);
alter table structure
add constraint structure_definition_fk foreign key (definition_id) references definition (id);
alter table mechanism
add constraint mechanism_definition_fk foreign key (definition_id) references definition (id);
alter table interface
add constraint interface_definition_fk foreign key (definition_id) references definition (id);
alter table effector
add constraint effector_definition_fk foreign key (definition_id) references definition (id);
alter table receptor
add constraint receptor_definition_fk foreign key (definition_id) references definition (id);
alter table interaction
add constraint interaction_definition_fk foreign key (definition_id) references definition (id);
alter table directive
add constraint directive_definition_fk foreign key (definition_id) references definition (id);
alter table norm
add constraint norm_definition_fk foreign key (definition_id) references definition (id);
-- ============================================================
-- 6. TRANSITION TABLE: rename columns + cast to enum
-- ============================================================
alter table ascription_status_transition drop constraint ast_gsm_type_check;
alter table ascription_status_transition
  rename column gsm_type to subject_type;
alter table ascription_status_transition
alter column subject_type type definition_subject_type using upper(subject_type)::definition_subject_type;
alter table ascription_status_transition
  rename column revision_id to ascription_id;
-- ============================================================
-- 7. REPLACE TRIGGER FUNCTIONS (new column names)
-- ============================================================
-- 7a. tgf_assign_ids: generate PK (id) if null; always set timestamp.
--     definition_id (FK) must be provided — not auto-generated.
create or replace function tgf_assign_ids() returns trigger language plpgsql as $$ begin -- id (PK): generate only when NULL (JPA supplies one; raw SQL may not)
  if NEW.id is null then NEW.id := uuid_v7();
end if;
-- timestamp: ALWAYS set by DB (authoritative)
NEW."timestamp" := clock_timestamp();
return NEW;
end;
$$;
-- 7b. tgf_assert_transition_owner_exists: validate transition owner
create or replace function tgf_assert_transition_owner_exists() returns trigger language plpgsql as $$
declare owner_count integer;
type_matches boolean;
begin
select count(*) into owner_count
from (
    select 'ARCHETYPE'::definition_subject_type as subject_type
    from archetype
    where id = NEW.ascription_id
    union all
    select 'STRUCTURE'
    from structure
    where id = NEW.ascription_id
    union all
    select 'MECHANISM'
    from mechanism
    where id = NEW.ascription_id
    union all
    select 'INTERFACE'
    from interface
    where id = NEW.ascription_id
    union all
    select 'EFFECTOR'
    from effector
    where id = NEW.ascription_id
    union all
    select 'RECEPTOR'
    from receptor
    where id = NEW.ascription_id
    union all
    select 'INTERACTION'
    from interaction
    where id = NEW.ascription_id
    union all
    select 'DIRECTIVE'
    from directive
    where id = NEW.ascription_id
    union all
    select 'NORM'
    from norm
    where id = NEW.ascription_id
  ) owners;
if owner_count <> 1 then raise exception 'ascription_status_transition.ascription_id % must belong to exactly one owner row; found %',
NEW.ascription_id,
owner_count;
end if;
select exists (
    select 1
    from (
        select 'ARCHETYPE'::definition_subject_type as subject_type
        from archetype
        where id = NEW.ascription_id
        union all
        select 'STRUCTURE'
        from structure
        where id = NEW.ascription_id
        union all
        select 'MECHANISM'
        from mechanism
        where id = NEW.ascription_id
        union all
        select 'INTERFACE'
        from interface
        where id = NEW.ascription_id
        union all
        select 'EFFECTOR'
        from effector
        where id = NEW.ascription_id
        union all
        select 'RECEPTOR'
        from receptor
        where id = NEW.ascription_id
        union all
        select 'INTERACTION'
        from interaction
        where id = NEW.ascription_id
        union all
        select 'DIRECTIVE'
        from directive
        where id = NEW.ascription_id
        union all
        select 'NORM'
        from norm
        where id = NEW.ascription_id
      ) owners
    where owners.subject_type = NEW.subject_type
  ) into type_matches;
if not type_matches then raise exception 'ascription_status_transition.subject_type % does not match owner type for ascription_id %',
NEW.subject_type,
NEW.ascription_id;
end if;
return NEW;
end;
$$;
-- 7c. tgf_sync_owner_status_from_transition
create or replace function tgf_sync_owner_status_from_transition() returns trigger language plpgsql as $$ begin perform set_config('sif.status_sync', 'on', true);
execute format(
  'update %I set status = $1 where id = $2',
  lower(NEW.subject_type::text)
) using NEW.post_status,
NEW.ascription_id;
perform set_config('sif.status_sync', 'off', true);
return NEW;
end;
$$;
-- 7d. tgf_assert_owner_status_matches_history
create or replace function tgf_assert_owner_status_matches_history() returns trigger language plpgsql as $$
declare latest_post_status ascription_status;
begin
select ast.post_status into latest_post_status
from ascription_status_transition ast
where ast.subject_type = upper(TG_TABLE_NAME)::definition_subject_type
  and ast.ascription_id = NEW.id
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
-- 7e. tgf_reject_revision_id_update (now protects id PK)
create or replace function tgf_reject_revision_id_update() returns trigger language plpgsql as $$ begin if NEW.id is distinct
from OLD.id then raise exception 'id (PK) is immutable on table %: old %, new %',
  TG_TABLE_NAME,
  OLD.id,
  NEW.id;
end if;
return NEW;
end;
$$;
-- 7f. tgf_restrict_owner_delete_when_transitions_exist
create or replace function tgf_restrict_owner_delete_when_transitions_exist() returns trigger language plpgsql as $$ begin if exists (
    select 1
    from ascription_status_transition ast
    where ast.subject_type = upper(TG_TABLE_NAME)::definition_subject_type
      and ast.ascription_id = OLD.id
  ) then raise exception 'cannot delete %.% while status transitions exist',
  TG_TABLE_NAME,
  OLD.id;
end if;
return OLD;
end;
$$;
-- Note: tgf_reject_transition_mutation() and tgf_reject_status_update()
-- do not reference renamed columns — no changes needed.
-- ============================================================
-- 8. RECREATE VIEW
-- ============================================================
create or replace view ascription_all as
select 'ARCHETYPE'::definition_subject_type as subject_type,
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from archetype
union all
select 'STRUCTURE',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from structure
union all
select 'MECHANISM',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from mechanism
union all
select 'EFFECTOR',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from effector
union all
select 'RECEPTOR',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from receptor
union all
select 'INTERACTION',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from interaction
union all
select 'INTERFACE',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from interface
union all
select 'DIRECTIVE',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from directive
union all
select 'NORM',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  statement,
  version,
  status
from norm;
comment on view ascription_all is 'Union of all GSM class tables for cross-type queries.';
-- ============================================================
-- 9. RENAME INDEXES
-- ============================================================
-- Class tables: idx_T_id (was on old `id`, now `definition_id`)
alter index idx_archetype_id
rename to idx_archetype_definition;
alter index idx_structure_id
rename to idx_structure_definition;
alter index idx_mechanism_id
rename to idx_mechanism_definition;
alter index idx_interface_id
rename to idx_interface_definition;
alter index idx_effector_id
rename to idx_effector_definition;
alter index idx_receptor_id
rename to idx_receptor_definition;
alter index idx_interaction_id
rename to idx_interaction_definition;
alter index idx_directive_id
rename to idx_directive_definition;
alter index idx_norm_id
rename to idx_norm_definition;
-- Class tables: idx_T_def (was on `definition`, now `statement`)
alter index idx_archetype_def
rename to idx_archetype_stmt;
alter index idx_structure_def
rename to idx_structure_stmt;
alter index idx_mechanism_def
rename to idx_mechanism_stmt;
alter index idx_interface_def
rename to idx_interface_stmt;
alter index idx_effector_def
rename to idx_effector_stmt;
alter index idx_receptor_def
rename to idx_receptor_stmt;
alter index idx_interaction_def
rename to idx_interaction_stmt;
alter index idx_directive_def
rename to idx_directive_stmt;
alter index idx_norm_def
rename to idx_norm_stmt;
-- Transition table: revision_id-based → ascription_id-based
alter index idx_ast_revision
rename to idx_ast_ascription;
alter index idx_ast_type_revision
rename to idx_ast_type_ascription;
commit;