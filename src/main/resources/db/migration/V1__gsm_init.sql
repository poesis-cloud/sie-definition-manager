-- ============================================================
-- V1__gsm_init.sql  –  GSM canonical schema (merged V1-V10)
--
-- Fixes applied during merge:
--   1. Column name kept as 'statement' (entity truth; V10's
--      rename to 'compilation' was stale).
--   2. norm.qualifier_id added (NormEntity FK, was missing).
--   3. archetype.schema_uri removed (entity dropped it).
--   4. Version CHECK fixed: >= 0 (V5 set DEFAULT 0 but kept > 0).
--   5. subject_type dropped from transition table (V6 change).
--   6. Effector/Receptor columns: output/input_archetype_id (V9).
--   7. interface_effector/interface_receptor join tables (V8).
--   8. directive.purpose_id NOT NULL (V7).
-- ============================================================
begin;
-- ============================================================
-- §1  Extensions + UUIDv7
-- ============================================================
create extension if not exists pgcrypto;
create or replace function uuid_v7() returns uuid as $$
declare v_time bigint := (
    extract(
      epoch
      from clock_timestamp()
    ) * 1000
  )::bigint;
v_bytes bytea;
begin -- 48 bits timestamp (ms) + 80 bits random = 128 bits
v_bytes := substring(
  int8send(v_time)
  from 3 for 6
) || gen_random_bytes(10);
-- Version 7 (bits 48-51 = 0111): byte index 6, high nibble
v_bytes := set_byte(v_bytes, 6, (get_byte(v_bytes, 6) & 15) | 112);
-- Variant 10 (bits 64-65): byte index 8, high 2 bits
v_bytes := set_byte(v_bytes, 8, (get_byte(v_bytes, 8) & 63) | 128);
return encode(v_bytes, 'hex')::uuid;
end;
$$ language plpgsql volatile;
-- ============================================================
-- §2  PostgreSQL enum types
-- ============================================================
do $$ begin create type ascription_status as enum (
  'DRAFT',
  'PROPOSED',
  'APPROVED',
  'ACTIVE',
  'SUSPENDED',
  'DEPRECATED',
  'RETIRED',
  'ABANDONED',
  'REJECTED'
);
exception
when duplicate_object then null;
end $$;
do $$ begin create type definition_subject_type as enum (
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
exception
when duplicate_object then null;
end $$;
-- ============================================================
-- §3  Tables
-- ============================================================
-- 3a. definition --------------------------------------------------
create table if not exists definition (
  id uuid not null default uuid_v7(),
  subject_type definition_subject_type not null,
  constraint definition_pk primary key (id)
);
-- 3b. archetype (self-ref FK: archetype_id → own table) ----------
create table if not exists archetype (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null,
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  constraint archetype_pk primary key (id),
  constraint archetype_version_check check (version >= 0),
  constraint archetype_typed_by_fk foreign key (archetype_id) references archetype(id)
);
-- 3c. structure ---------------------------------------------------
create table if not exists structure (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  constraint structure_pk primary key (id),
  constraint structure_version_check check (version >= 0)
);
-- 3d. mechanism ---------------------------------------------------
create table if not exists mechanism (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  structure_id uuid not null references structure(id),
  constraint mechanism_pk primary key (id),
  constraint mechanism_version_check check (version >= 0)
);
-- 3e. interface ---------------------------------------------------
create table if not exists interface (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  structure_id uuid not null references structure(id),
  constraint interface_pk primary key (id),
  constraint interface_version_check check (version >= 0)
);
-- 3f. effector ----------------------------------------------------
create table if not exists effector (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  mechanism_id uuid not null references mechanism(id),
  output_archetype_id uuid not null references archetype(id),
  constraint effector_pk primary key (id),
  constraint effector_version_check check (version >= 0)
);
-- 3g. receptor ----------------------------------------------------
create table if not exists receptor (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  mechanism_id uuid not null references mechanism(id),
  input_archetype_id uuid not null references archetype(id),
  constraint receptor_pk primary key (id),
  constraint receptor_version_check check (version >= 0)
);
-- 3h. interaction -------------------------------------------------
create table if not exists interaction (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  effector_id uuid not null references effector(id),
  receptor_id uuid not null references receptor(id),
  constraint interaction_pk primary key (id),
  constraint interaction_version_check check (version >= 0)
);
-- 3i. directive ---------------------------------------------------
create table if not exists directive (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  structure_id uuid not null references structure(id),
  qualifier_id uuid not null references archetype(id),
  purpose_id uuid not null references structure(id),
  constraint directive_pk primary key (id),
  constraint directive_version_check check (version >= 0)
);
-- 3j. norm --------------------------------------------------------
create table if not exists norm (
  id uuid not null default uuid_v7(),
  definition_id uuid not null references definition(id),
  archetype_id uuid not null references archetype(id),
  statement jsonb not null default '{}'::jsonb,
  "timestamp" timestamptz not null default clock_timestamp(),
  status ascription_status not null default 'DRAFT',
  version integer not null default 0,
  structure_id uuid not null references structure(id),
  qualifier_id uuid not null references archetype(id),
  constraint norm_pk primary key (id),
  constraint norm_version_check check (version >= 0)
);
-- 3k. ascription_status_transition --------------------------------
create table if not exists ascription_status_transition (
  id uuid not null default uuid_v7(),
  ascription_id uuid not null,
  pre_status ascription_status,
  post_status ascription_status not null,
  "timestamp" timestamptz not null default clock_timestamp(),
  constraint ast_pk primary key (id),
  constraint ast_no_noop_transition check (
    pre_status is distinct
    from post_status
  )
);
-- 3l. interface_effector (join) -----------------------------------
create table if not exists interface_effector (
  interface_id uuid not null references interface(id),
  effector_id uuid not null references effector(id),
  constraint interface_effector_pk primary key (interface_id, effector_id)
);
-- 3m. interface_receptor (join) -----------------------------------
create table if not exists interface_receptor (
  interface_id uuid not null references interface(id),
  receptor_id uuid not null references receptor(id),
  constraint interface_receptor_pk primary key (interface_id, receptor_id)
);
-- ============================================================
-- §4  Indexes
-- ============================================================
-- 4a. definition --------------------------------------------------
create index if not exists idx_definition_subject_type on definition (subject_type);
-- 4b. Per-class-table common indexes (definition_id, status, GIN) -
-- archetype
create index if not exists idx_archetype_definition on archetype (definition_id);
create index if not exists idx_archetype_status on archetype (status);
create index if not exists gin_archetype_stmt on archetype using gin (statement);
-- structure
create index if not exists idx_structure_definition on structure (definition_id);
create index if not exists idx_structure_status on structure (status);
create index if not exists gin_structure_stmt on structure using gin (statement);
-- mechanism
create index if not exists idx_mechanism_definition on mechanism (definition_id);
create index if not exists idx_mechanism_status on mechanism (status);
create index if not exists idx_mechanism_structure on mechanism (structure_id);
create index if not exists gin_mechanism_stmt on mechanism using gin (statement);
-- interface
create index if not exists idx_interface_definition on interface (definition_id);
create index if not exists idx_interface_status on interface (status);
create index if not exists idx_interface_structure on interface (structure_id);
create index if not exists gin_interface_stmt on interface using gin (statement);
-- effector
create index if not exists idx_effector_definition on effector (definition_id);
create index if not exists idx_effector_status on effector (status);
create index if not exists idx_effector_mechanism on effector (mechanism_id);
create index if not exists idx_effector_output_arch on effector (output_archetype_id);
create index if not exists gin_effector_stmt on effector using gin (statement);
-- receptor
create index if not exists idx_receptor_definition on receptor (definition_id);
create index if not exists idx_receptor_status on receptor (status);
create index if not exists idx_receptor_mechanism on receptor (mechanism_id);
create index if not exists idx_receptor_input_arch on receptor (input_archetype_id);
create index if not exists gin_receptor_stmt on receptor using gin (statement);
-- interaction
create index if not exists idx_interaction_definition on interaction (definition_id);
create index if not exists idx_interaction_status on interaction (status);
create index if not exists idx_interaction_effector on interaction (effector_id);
create index if not exists idx_interaction_receptor on interaction (receptor_id);
create index if not exists gin_interaction_stmt on interaction using gin (statement);
-- directive
create index if not exists idx_directive_definition on directive (definition_id);
create index if not exists idx_directive_status on directive (status);
create index if not exists idx_directive_structure on directive (structure_id);
create index if not exists idx_directive_qualifier on directive (qualifier_id);
create index if not exists idx_directive_purpose on directive (purpose_id);
create index if not exists gin_directive_stmt on directive using gin (statement);
-- norm
create index if not exists idx_norm_definition on norm (definition_id);
create index if not exists idx_norm_status on norm (status);
create index if not exists idx_norm_structure on norm (structure_id);
create index if not exists idx_norm_qualifier on norm (qualifier_id);
create index if not exists gin_norm_stmt on norm using gin (statement);
-- 4c. Transition table indexes ------------------------------------
create index if not exists idx_ast_ascription on ascription_status_transition (ascription_id);
create unique index if not exists uq_ast_initial on ascription_status_transition (ascription_id)
where pre_status is null;
create index if not exists idx_ast_latest_lookup on ascription_status_transition (ascription_id, "timestamp" desc, id desc);
create index if not exists idx_ast_edge on ascription_status_transition (ascription_id, pre_status, post_status);
-- 4d. Lifecycle uniqueness (at most one ACTIVE / APPROVED per def) -
create unique index if not exists uq_archetype_active on archetype (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_archetype_approved on archetype (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_structure_active on structure (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_structure_approved on structure (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_mechanism_active on mechanism (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_mechanism_approved on mechanism (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_interface_active on interface (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_interface_approved on interface (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_effector_active on effector (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_effector_approved on effector (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_receptor_active on receptor (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_receptor_approved on receptor (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_interaction_active on interaction (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_interaction_approved on interaction (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_directive_active on directive (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_directive_approved on directive (definition_id)
where status = 'APPROVED';
create unique index if not exists uq_norm_active on norm (definition_id)
where status = 'ACTIVE';
create unique index if not exists uq_norm_approved on norm (definition_id)
where status = 'APPROVED';
-- 4e. Expression indexes — GSM §9 identity uniqueness -------------
-- Structure.purpose globally unique among in-effect
create unique index if not exists uq_structure_purpose on structure ((statement->>'purpose'))
where status in ('ACTIVE', 'DEPRECATED');
-- Mechanism.function unique within owning Structure among in-effect
create unique index if not exists uq_mechanism_function on mechanism (structure_id, (statement->>'function'))
where status in ('ACTIVE', 'DEPRECATED');
-- Archetype.title globally unique among in-effect
create unique index if not exists uq_archetype_title on archetype ((statement->>'title'))
where status in ('ACTIVE', 'DEPRECATED');
-- ============================================================
-- §5  Seed data — GSM base archetypes
--     Now loaded at application startup by ArchetypeSeedRunner
--     from classpath:schemas/gsm-archetypes/*.schema.json.
--     Single source of truth: definition/schemas/gsm-archetypes/
-- ============================================================
-- ============================================================
-- §6  Trigger functions
-- ============================================================
-- 6a. Assign PK id if null ----------------------------------------
create or replace function tgf_assign_id() returns trigger as $$ begin if NEW.id is null then NEW.id := uuid_v7();
end if;
return NEW;
end;
$$ language plpgsql;
-- 6b. Assign authoritative creation timestamp ---------------------
create or replace function tgf_assign_timestamp() returns trigger as $$ begin NEW."timestamp" := clock_timestamp();
return NEW;
end;
$$ language plpgsql;
-- 6c. Helper: resolve ascription class table from id --------------
create or replace function gsm_find_ascription_table(p_id uuid) returns text as $$
declare tbl text;
begin
select sub.tbl into tbl
from (
    select 'archetype'::text as tbl
    from archetype
    where id = p_id
    union all
    select 'structure'
    from structure
    where id = p_id
    union all
    select 'mechanism'
    from mechanism
    where id = p_id
    union all
    select 'interface'
    from interface
    where id = p_id
    union all
    select 'effector'
    from effector
    where id = p_id
    union all
    select 'receptor'
    from receptor
    where id = p_id
    union all
    select 'interaction'
    from interaction
    where id = p_id
    union all
    select 'directive'
    from directive
    where id = p_id
    union all
    select 'norm'
    from norm
    where id = p_id
  ) sub
limit 1;
return tbl;
end;
$$ language plpgsql stable;
-- 6d. Validate transition.ascription_id references an ascription --
create or replace function tgf_assert_transition_ascription_exists() returns trigger as $$ begin if gsm_find_ascription_table(NEW.ascription_id) is null then raise exception 'ascription_status_transition.ascription_id = % references no ascription row',
  NEW.ascription_id using errcode = 'foreign_key_violation';
end if;
return NEW;
end;
$$ language plpgsql;
-- 6e. Sync ascription status from transition ----------------------
create or replace function tgf_sync_ascription_status() returns trigger as $$
declare tbl text;
begin tbl := gsm_find_ascription_table(NEW.ascription_id);
if tbl is null then raise exception 'tgf_sync_ascription_status: no ascription row for id = %',
NEW.ascription_id using errcode = 'foreign_key_violation';
end if;
execute format('update %I set status = $1 where id = $2', tbl) using NEW.post_status::ascription_status,
NEW.ascription_id;
return NEW;
end;
$$ language plpgsql;
-- 6f. Assign governance version on APPROVED transition ------------
create or replace function tgf_assign_ascription_version() returns trigger as $$
declare tbl text;
begin if NEW.post_status <> 'APPROVED' then return NEW;
end if;
tbl := gsm_find_ascription_table(NEW.ascription_id);
if tbl is null then raise exception 'tgf_assign_ascription_version: no ascription row for id = %',
NEW.ascription_id using errcode = 'foreign_key_violation';
end if;
execute format(
  'update %I set version = version + 1 where id = $1',
  tbl
) using NEW.ascription_id;
return NEW;
end;
$$ language plpgsql;
-- 6g. Verify ascription status matches latest transition ----------
-- Re-reads current row status instead of relying on NEW, so that
-- deferred evaluation after multiple cascaded updates (from split
-- status-sync and version-assign triggers) sees the final state.
create or replace function tgf_assert_ascription_status_matches_history() returns trigger as $$
declare current_status ascription_status;
expected ascription_status;
begin execute format(
  'select status from %I where id = $1',
  TG_TABLE_NAME
) into current_status using NEW.id;
if current_status is null then return NEW;
end if;
select t.post_status into expected
from ascription_status_transition t
where t.ascription_id = NEW.id
order by t."timestamp" desc,
  t.id desc
limit 1;
if expected is null then if current_status <> 'DRAFT' then raise exception '% id=%: status=% but no transition history (expected DRAFT)',
TG_TABLE_NAME,
NEW.id,
current_status using errcode = 'check_violation';
end if;
elsif current_status <> expected then raise exception '% id=%: status=% but latest transition says %',
TG_TABLE_NAME,
NEW.id,
current_status,
expected using errcode = 'check_violation';
end if;
return NEW;
end;
$$ language plpgsql;
-- 6h. Transition rows are immutable -------------------------------
create or replace function tgf_reject_transition_mutation() returns trigger as $$ begin raise exception 'ascription_status_transition rows are immutable (attempted %)',
  TG_OP using errcode = 'restrict_violation';
end;
$$ language plpgsql;
-- 6i. Block direct status column updates --------------------------
create or replace function tgf_reject_status_update() returns trigger as $$ begin -- Allow trigger-cascaded updates (sync trigger operates at depth >= 2)
  if pg_trigger_depth() >= 2 then return NEW;
end if;
if OLD.status is distinct
from NEW.status then raise exception '% id=%: direct status update forbidden; insert into ascription_status_transition instead',
  TG_TABLE_NAME,
  NEW.id using errcode = 'restrict_violation';
end if;
return NEW;
end;
$$ language plpgsql;
-- 6j. Block PK (id) changes --------------------------------------
create or replace function tgf_reject_id_update() returns trigger as $$ begin if OLD.id is distinct
from NEW.id then raise exception '% id=%: primary key (id) is immutable',
  TG_TABLE_NAME,
  OLD.id using errcode = 'restrict_violation';
end if;
return NEW;
end;
$$ language plpgsql;
-- 6k. Prevent ascription delete when transitions exist ------------
create or replace function tgf_restrict_ascription_delete_when_transitions_exist() returns trigger as $$
declare cnt bigint;
begin
select count(*) into cnt
from ascription_status_transition
where ascription_id = OLD.id;
if cnt > 0 then raise exception '% id=%: cannot delete; % transition(s) exist',
TG_TABLE_NAME,
OLD.id,
cnt using errcode = 'restrict_violation';
end if;
return OLD;
end;
$$ language plpgsql;
-- ============================================================
-- §7  Trigger attachments
-- ============================================================
-- Attach the standard 6-trigger set to each ascription class table.
-- PostgreSQL requires one CREATE TRIGGER per trigger, so we
-- enumerate all 9 tables × 6 triggers.
-- ---- archetype ----
create trigger trg_archetype_assign_id before
insert on archetype for each row execute function tgf_assign_id();
create trigger trg_archetype_assign_timestamp before
insert on archetype for each row execute function tgf_assign_timestamp();
create trigger trg_archetype_reject_status_update before
update of status on archetype for each row execute function tgf_reject_status_update();
create trigger trg_archetype_reject_id_update before
update of id on archetype for each row execute function tgf_reject_id_update();
create trigger trg_archetype_restrict_delete before delete on archetype for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_archetype_status_matches_history
after
insert
  or
update on archetype deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- structure ----
create trigger trg_structure_assign_id before
insert on structure for each row execute function tgf_assign_id();
create trigger trg_structure_assign_timestamp before
insert on structure for each row execute function tgf_assign_timestamp();
create trigger trg_structure_reject_status_update before
update of status on structure for each row execute function tgf_reject_status_update();
create trigger trg_structure_reject_id_update before
update of id on structure for each row execute function tgf_reject_id_update();
create trigger trg_structure_restrict_delete before delete on structure for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_structure_status_matches_history
after
insert
  or
update on structure deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- mechanism ----
create trigger trg_mechanism_assign_id before
insert on mechanism for each row execute function tgf_assign_id();
create trigger trg_mechanism_assign_timestamp before
insert on mechanism for each row execute function tgf_assign_timestamp();
create trigger trg_mechanism_reject_status_update before
update of status on mechanism for each row execute function tgf_reject_status_update();
create trigger trg_mechanism_reject_id_update before
update of id on mechanism for each row execute function tgf_reject_id_update();
create trigger trg_mechanism_restrict_delete before delete on mechanism for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_mechanism_status_matches_history
after
insert
  or
update on mechanism deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- interface ----
create trigger trg_interface_assign_id before
insert on interface for each row execute function tgf_assign_id();
create trigger trg_interface_assign_timestamp before
insert on interface for each row execute function tgf_assign_timestamp();
create trigger trg_interface_reject_status_update before
update of status on interface for each row execute function tgf_reject_status_update();
create trigger trg_interface_reject_id_update before
update of id on interface for each row execute function tgf_reject_id_update();
create trigger trg_interface_restrict_delete before delete on interface for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_interface_status_matches_history
after
insert
  or
update on interface deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- effector ----
create trigger trg_effector_assign_id before
insert on effector for each row execute function tgf_assign_id();
create trigger trg_effector_assign_timestamp before
insert on effector for each row execute function tgf_assign_timestamp();
create trigger trg_effector_reject_status_update before
update of status on effector for each row execute function tgf_reject_status_update();
create trigger trg_effector_reject_id_update before
update of id on effector for each row execute function tgf_reject_id_update();
create trigger trg_effector_restrict_delete before delete on effector for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_effector_status_matches_history
after
insert
  or
update on effector deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- receptor ----
create trigger trg_receptor_assign_id before
insert on receptor for each row execute function tgf_assign_id();
create trigger trg_receptor_assign_timestamp before
insert on receptor for each row execute function tgf_assign_timestamp();
create trigger trg_receptor_reject_status_update before
update of status on receptor for each row execute function tgf_reject_status_update();
create trigger trg_receptor_reject_id_update before
update of id on receptor for each row execute function tgf_reject_id_update();
create trigger trg_receptor_restrict_delete before delete on receptor for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_receptor_status_matches_history
after
insert
  or
update on receptor deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- interaction ----
create trigger trg_interaction_assign_id before
insert on interaction for each row execute function tgf_assign_id();
create trigger trg_interaction_assign_timestamp before
insert on interaction for each row execute function tgf_assign_timestamp();
create trigger trg_interaction_reject_status_update before
update of status on interaction for each row execute function tgf_reject_status_update();
create trigger trg_interaction_reject_id_update before
update of id on interaction for each row execute function tgf_reject_id_update();
create trigger trg_interaction_restrict_delete before delete on interaction for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_interaction_status_matches_history
after
insert
  or
update on interaction deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- directive ----
create trigger trg_directive_assign_id before
insert on directive for each row execute function tgf_assign_id();
create trigger trg_directive_assign_timestamp before
insert on directive for each row execute function tgf_assign_timestamp();
create trigger trg_directive_reject_status_update before
update of status on directive for each row execute function tgf_reject_status_update();
create trigger trg_directive_reject_id_update before
update of id on directive for each row execute function tgf_reject_id_update();
create trigger trg_directive_restrict_delete before delete on directive for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_directive_status_matches_history
after
insert
  or
update on directive deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- norm ----
create trigger trg_norm_assign_id before
insert on norm for each row execute function tgf_assign_id();
create trigger trg_norm_assign_timestamp before
insert on norm for each row execute function tgf_assign_timestamp();
create trigger trg_norm_reject_status_update before
update of status on norm for each row execute function tgf_reject_status_update();
create trigger trg_norm_reject_id_update before
update of id on norm for each row execute function tgf_reject_id_update();
create trigger trg_norm_restrict_delete before delete on norm for each row execute function tgf_restrict_ascription_delete_when_transitions_exist();
create constraint trigger trg_norm_status_matches_history
after
insert
  or
update on norm deferrable initially deferred for each row execute function tgf_assert_ascription_status_matches_history();
-- ---- ascription_status_transition ----
create trigger trg_ast_assert_ascription_exists before
insert on ascription_status_transition for each row execute function tgf_assert_transition_ascription_exists();
create trigger trg_ast_assign_ascription_version
after
insert on ascription_status_transition for each row execute function tgf_assign_ascription_version();
create trigger trg_ast_sync_ascription_status
after
insert on ascription_status_transition for each row execute function tgf_sync_ascription_status();
create trigger trg_ast_reject_mutation before
update
  or delete on ascription_status_transition for each row execute function tgf_reject_transition_mutation();
-- ============================================================
-- §8  View — cross-type query convenience
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
commit;