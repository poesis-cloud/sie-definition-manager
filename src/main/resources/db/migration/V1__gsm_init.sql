-- ============================================================
-- V1__gsm_init.sql
-- GSM (Generative System Model) — core schema
--
-- Design: 9 autonomous class tables, each self-contained.
-- No shared base table. FKs target revision_id (PK) of the
-- referenced class table — definitions reference definitions.
-- Non-FK attributes live in definition JSONB.
-- id is a non-unique grouper for revision lineage, lifecycle
-- enforcement, and human/API addressing.
-- Per-entity uniqueness: at most 1 ACTIVE and at most 1 APPROVED
-- revision per id. DEPRECATED/SUSPENDED/etc. are unbounded.
--
-- Source: gsm.puml, gsm-ascription-lifecycle-v1.puml
-- ============================================================
begin;
-- ============================================================
-- UUIDv7 FUNCTION
-- ============================================================
create extension if not exists pgcrypto;
create or replace function uuid_v7() returns uuid language plpgsql volatile as $$
declare unix_ts_ms bigint;
ts_hex text;
rand_bytes bytea;
rand_hex text;
variant_hex text;
begin unix_ts_ms := floor(
    extract(
        epoch
        from clock_timestamp()
    ) * 1000
)::bigint;
ts_hex := lpad(to_hex(unix_ts_ms), 12, '0');
rand_bytes := gen_random_bytes(10);
rand_hex := encode(rand_bytes, 'hex');
variant_hex := substr('89ab', (get_byte(rand_bytes, 9) % 4) + 1, 1);
return (
    substr(ts_hex, 1, 8) || '-' || substr(ts_hex, 9, 4) || '-' || '7' || substr(rand_hex, 1, 3) || '-' || variant_hex || substr(rand_hex, 4, 3) || '-' || substr(rand_hex, 7, 12)
)::uuid;
end;
$$;
-- ============================================================
-- SHARED ENUM
-- ============================================================
create type ascription_status as enum (
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
-- ============================================================
-- ARCHETYPE
-- ============================================================
create table archetype (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null,
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    constraint archetype_pk primary key (revision_id),
    constraint archetype_typed_by_fk foreign key (archetype_id) references archetype (revision_id),
    constraint archetype_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_archetype_id on archetype (id);
create index idx_archetype_status on archetype (status);
create index idx_archetype_def on archetype using gin (definition jsonb_path_ops);
create unique index uq_archetype_active on archetype (id)
where status = 'ACTIVE';
create unique index uq_archetype_approved on archetype (id)
where status = 'APPROVED';
-- ============================================================
-- STRUCTURE
-- ============================================================
create table structure (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    constraint structure_pk primary key (revision_id),
    constraint structure_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_structure_id on structure (id);
create index idx_structure_status on structure (status);
create index idx_structure_def on structure using gin (definition jsonb_path_ops);
create unique index uq_structure_active on structure (id)
where status = 'ACTIVE';
create unique index uq_structure_approved on structure (id)
where status = 'APPROVED';
-- ============================================================
-- MECHANISM
-- ============================================================
create table mechanism (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    -- Structural FK: owning Structure definition
    structure_id uuid not null references structure (revision_id),
    constraint mechanism_pk primary key (revision_id),
    constraint mechanism_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_mechanism_id on mechanism (id);
create index idx_mechanism_status on mechanism (status);
create index idx_mechanism_structure on mechanism (structure_id);
create index idx_mechanism_def on mechanism using gin (definition jsonb_path_ops);
create unique index uq_mechanism_active on mechanism (id)
where status = 'ACTIVE';
create unique index uq_mechanism_approved on mechanism (id)
where status = 'APPROVED';
-- ============================================================
-- INTERFACE
-- ============================================================
create table interface (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    -- Structural FK: owning Structure definition
    structure_id uuid not null references structure (revision_id),
    constraint interface_pk primary key (revision_id),
    constraint interface_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_interface_id on interface (id);
create index idx_interface_status on interface (status);
create index idx_interface_structure on interface (structure_id);
create index idx_interface_def on interface using gin (definition jsonb_path_ops);
create unique index uq_interface_active on interface (id)
where status = 'ACTIVE';
create unique index uq_interface_approved on interface (id)
where status = 'APPROVED';
-- ============================================================
-- EFFECTOR
-- ============================================================
create table effector (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    -- Structural FKs
    mechanism_id uuid not null references mechanism (revision_id),
    port_archetype_id uuid not null references archetype (revision_id),
    interface_id uuid references interface (revision_id),
    constraint effector_pk primary key (revision_id),
    constraint effector_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_effector_id on effector (id);
create index idx_effector_status on effector (status);
create index idx_effector_mechanism on effector (mechanism_id);
create index idx_effector_port_arch on effector (port_archetype_id);
create index idx_effector_interface on effector (interface_id)
where interface_id is not null;
create index idx_effector_def on effector using gin (definition jsonb_path_ops);
create unique index uq_effector_active on effector (id)
where status = 'ACTIVE';
create unique index uq_effector_approved on effector (id)
where status = 'APPROVED';
-- ============================================================
-- RECEPTOR
-- ============================================================
create table receptor (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    -- Structural FKs
    mechanism_id uuid not null references mechanism (revision_id),
    port_archetype_id uuid not null references archetype (revision_id),
    interface_id uuid references interface (revision_id),
    constraint receptor_pk primary key (revision_id),
    constraint receptor_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_receptor_id on receptor (id);
create index idx_receptor_status on receptor (status);
create index idx_receptor_mechanism on receptor (mechanism_id);
create index idx_receptor_port_arch on receptor (port_archetype_id);
create index idx_receptor_interface on receptor (interface_id)
where interface_id is not null;
create index idx_receptor_def on receptor using gin (definition jsonb_path_ops);
create unique index uq_receptor_active on receptor (id)
where status = 'ACTIVE';
create unique index uq_receptor_approved on receptor (id)
where status = 'APPROVED';
-- ============================================================
-- INTERACTION
-- ============================================================
create table interaction (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    -- Structural FKs: causal coupling between definitions
    effector_id uuid not null references effector (revision_id),
    receptor_id uuid not null references receptor (revision_id),
    constraint interaction_pk primary key (revision_id),
    constraint interaction_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_interaction_id on interaction (id);
create index idx_interaction_status on interaction (status);
create index idx_interaction_effector on interaction (effector_id);
create index idx_interaction_receptor on interaction (receptor_id);
create index idx_interaction_def on interaction using gin (definition jsonb_path_ops);
create unique index uq_interaction_active on interaction (id)
where status = 'ACTIVE';
create unique index uq_interaction_approved on interaction (id)
where status = 'APPROVED';
-- ============================================================
-- DIRECTIVE
-- ============================================================
create table directive (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    -- Structural FKs
    structure_id uuid not null references structure (revision_id),
    qualifier_id uuid not null references archetype (revision_id),
    purpose_id uuid references structure (revision_id),
    constraint directive_pk primary key (revision_id),
    constraint directive_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_directive_id on directive (id);
create index idx_directive_status on directive (status);
create index idx_directive_structure on directive (structure_id);
create index idx_directive_qualifier on directive (qualifier_id);
create index idx_directive_purpose on directive (purpose_id)
where purpose_id is not null;
create index idx_directive_def on directive using gin (definition jsonb_path_ops);
create unique index uq_directive_active on directive (id)
where status = 'ACTIVE';
create unique index uq_directive_approved on directive (id)
where status = 'APPROVED';
-- ============================================================
-- NORM
-- ============================================================
create table norm (
    id uuid not null,
    revision_id uuid not null,
    revision_timestamp timestamptz not null default clock_timestamp(),
    archetype_id uuid not null references archetype (revision_id),
    definition jsonb not null default '{}'::jsonb,
    version integer,
    status ascription_status not null default 'DRAFT',
    -- Structural FK: authoring Structure definition
    structure_id uuid not null references structure (revision_id),
    constraint norm_pk primary key (revision_id),
    constraint norm_version_positive check (
        version is null
        or version > 0
    )
);
create index idx_norm_id on norm (id);
create index idx_norm_status on norm (status);
create index idx_norm_structure on norm (structure_id);
create index idx_norm_def on norm using gin (definition jsonb_path_ops);
create unique index uq_norm_active on norm (id)
where status = 'ACTIVE';
create unique index uq_norm_approved on norm (id)
where status = 'APPROVED';
-- ============================================================
-- STATUS TRANSITION TABLE (shared, no FK — application-enforced integrity)
--
-- GSM §AscriptionStatusTransition: immutable audit record of lifecycle
-- state changes.  One table for all 9 class tables; gsm_type discriminator
-- identifies the owning class table.  Referential integrity between
-- revision_id and the owning class table is enforced by the application
-- (definition-manager) within the same transaction.
-- ============================================================
create table if not exists ascription_status_transition (
    id uuid not null default uuid_v7(),
    gsm_type text not null,
    revision_id uuid not null,
    pre_status ascription_status,
    -- null for initial DRAFT
    post_status ascription_status not null,
    timestamp timestamptz not null default clock_timestamp(),
    constraint ast_pk primary key (id),
    constraint ast_pre_post_distinct check (
        pre_status is distinct
        from post_status
    ),
    constraint ast_gsm_type_check check (
        gsm_type in (
            'archetype',
            'structure',
            'mechanism',
            'interface',
            'effector',
            'receptor',
            'interaction',
            'directive',
            'norm'
        )
    )
);
create index idx_ast_revision on ascription_status_transition (revision_id);
create index idx_ast_type_revision on ascription_status_transition (gsm_type, revision_id);
create index idx_ast_latest_lookup on ascription_status_transition (gsm_type, revision_id, timestamp desc, id desc);
create unique index uq_ast_initial on ascription_status_transition (gsm_type, revision_id)
where pre_status is null;
create index idx_ast_edge on ascription_status_transition (
    gsm_type,
    revision_id,
    pre_status,
    post_status
);
-- ============================================================
-- CONVENIENCE VIEW: all ascriptions (cross-type queries)
-- ============================================================
create or replace view ascription_all as
select 'archetype'::text as gsm_type,
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from archetype
union all
select 'structure',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from structure
union all
select 'mechanism',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from mechanism
union all
select 'effector',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from effector
union all
select 'receptor',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from receptor
union all
select 'interaction',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from interaction
union all
select 'interface',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from interface
union all
select 'directive',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from directive
union all
select 'norm',
    id,
    revision_id,
    revision_timestamp,
    archetype_id,
    definition,
    version,
    status
from norm;
comment on view ascription_all is 'Union of all GSM class tables for cross-type queries (e.g. approval inbox, global search). Not a base table.';
-- ============================================================
-- SEED: GSM base archetypes
--
-- 9 bootstrap archetypes: the seed Archetype (self-typing root)
-- plus one base archetype per GSM structural and governance
-- class. All stored in the archetype table.
--
-- Bootstrap UUIDs are generated at population time via uuid_v7().
-- Deterministic reference to base archetypes is by schemaUri.
-- FKs reference revision_id directly: child archetypes point to
-- the seed Archetype's revision_id.
-- ============================================================
do $$
declare seed_schema_uri constant text := 'urn:sie:gsm:v1:Archetype.schema.json';
seed_id uuid;
seed_rev uuid;
curr_rev uuid;
begin -- 1. Seed Archetype (self-typing: archetype_id = own revision_id)
select a.id,
    a.revision_id into seed_id,
    seed_rev
from archetype a
where a.definition->>'schemaUri' = seed_schema_uri
order by a.version desc nulls last,
    a.revision_id desc
limit 1;
if seed_rev is null then seed_id := uuid_v7();
seed_rev := uuid_v7();
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        seed_id,
        seed_rev,
        seed_rev,
        jsonb_build_object('schemaUri', seed_schema_uri),
        1,
        'ACTIVE'
    );
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', seed_rev, null, 'DRAFT'),
    ('archetype', seed_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', seed_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', seed_rev, 'APPROVED', 'ACTIVE');
end if;
-- 2. StructureArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Structure.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Structure.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
-- 3. MechanismArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Mechanism.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Mechanism.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
-- 4. EffectorArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Effector.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Effector.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
-- 5. ReceptorArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Receptor.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Receptor.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
-- 6. InteractionArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Interaction.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Interaction.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
-- 7. InterfaceArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Interface.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Interface.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
-- 8. DirectiveArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Directive.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Directive.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
-- 9. NormArchetype
if not exists (
    select 1
    from archetype
    where definition->>'schemaUri' = 'urn:sie:gsm:v1:Norm.schema.json'
) then
insert into archetype (
        id,
        revision_id,
        archetype_id,
        definition,
        version,
        status
    )
values (
        uuid_v7(),
        uuid_v7(),
        seed_rev,
        jsonb_build_object(
            'schemaUri',
            'urn:sie:gsm:v1:Norm.schema.json'
        ),
        1,
        'ACTIVE'
    )
returning revision_id into curr_rev;
insert into ascription_status_transition (
        gsm_type,
        revision_id,
        pre_status,
        post_status
    )
values ('archetype', curr_rev, null, 'DRAFT'),
    ('archetype', curr_rev, 'DRAFT', 'PROPOSED'),
    ('archetype', curr_rev, 'PROPOSED', 'APPROVED'),
    ('archetype', curr_rev, 'APPROVED', 'ACTIVE');
end if;
end;
$$;
-- ============================================================
-- TRIGGER: DB-enforced ID generation
--
-- revision_id: ALWAYS overwritten — the DB is the sole authority.
--   The caller must never control revision identity.
-- id: generated only when NULL (new entity). When provided
--   (new revision of existing entity), the caller's value is
--   kept to preserve lineage grouping.
--
-- Placed after seed block: seed inserts run without triggers
-- (infrastructure bootstrap may legitimately set both columns).
-- NOT NULL constraints are evaluated after the trigger fires,
-- so the trigger populates values in time.
-- ============================================================
create or replace function tgf_assign_ids() returns trigger language plpgsql as $$ begin if NEW.id is null then NEW.id := uuid_v7();
end if;
NEW.revision_id := uuid_v7();
NEW.revision_timestamp := clock_timestamp();
return NEW;
end;
$$;
create trigger trg_archetype_assign_ids before
insert on archetype for each row execute function tgf_assign_ids();
create trigger trg_structure_assign_ids before
insert on structure for each row execute function tgf_assign_ids();
create trigger trg_mechanism_assign_ids before
insert on mechanism for each row execute function tgf_assign_ids();
create trigger trg_interface_assign_ids before
insert on interface for each row execute function tgf_assign_ids();
create trigger trg_effector_assign_ids before
insert on effector for each row execute function tgf_assign_ids();
create trigger trg_receptor_assign_ids before
insert on receptor for each row execute function tgf_assign_ids();
create trigger trg_interaction_assign_ids before
insert on interaction for each row execute function tgf_assign_ids();
create trigger trg_directive_assign_ids before
insert on directive for each row execute function tgf_assign_ids();
create trigger trg_norm_assign_ids before
insert on norm for each row execute function tgf_assign_ids();
-- ============================================================
-- TRIGGERS: transition-table integrity for autonomous class tables
--
-- This is the strongest DB-side approximation of envelope-table RI
-- available without introducing a shared parent table:
-- - each transition must belong to exactly one existing owner row
-- - gsm_type must match that owner table
-- - owner status is synchronized from the latest transition
-- - owner rows must have at least one transition whose latest post_status
--   matches the denormalized owner status
-- - transition rows are immutable once written
-- - owner rows cannot be deleted while transitions exist
-- - revision_id is immutable on owner rows
--
-- The transition table's `timestamp` column provides authoritative ordering
-- for transitions. UUIDv7 embeds millisecond time but uses random bits
-- for the sub-millisecond part, so `id` alone is insufficient for ordering
-- within the same millisecond. "Latest" status is derived by ordering on
-- `timestamp` desc, `id` desc.
-- ============================================================
create or replace function tgf_assert_transition_owner_exists() returns trigger language plpgsql as $$
declare owner_count integer;
type_matches boolean;
begin
select count(*) into owner_count
from (
        select 'archetype'::text as gsm_type
        from archetype
        where revision_id = NEW.revision_id
        union all
        select 'structure'::text
        from structure
        where revision_id = NEW.revision_id
        union all
        select 'mechanism'::text
        from mechanism
        where revision_id = NEW.revision_id
        union all
        select 'interface'::text
        from interface
        where revision_id = NEW.revision_id
        union all
        select 'effector'::text
        from effector
        where revision_id = NEW.revision_id
        union all
        select 'receptor'::text
        from receptor
        where revision_id = NEW.revision_id
        union all
        select 'interaction'::text
        from interaction
        where revision_id = NEW.revision_id
        union all
        select 'directive'::text
        from directive
        where revision_id = NEW.revision_id
        union all
        select 'norm'::text
        from norm
        where revision_id = NEW.revision_id
    ) owners;
if owner_count <> 1 then raise exception 'ascription_status_transition.revision_id % must belong to exactly one owner row across GSM class tables; found %',
NEW.revision_id,
owner_count;
end if;
select exists (
        select 1
        from (
                select 'archetype'::text as gsm_type
                from archetype
                where revision_id = NEW.revision_id
                union all
                select 'structure'::text
                from structure
                where revision_id = NEW.revision_id
                union all
                select 'mechanism'::text
                from mechanism
                where revision_id = NEW.revision_id
                union all
                select 'interface'::text
                from interface
                where revision_id = NEW.revision_id
                union all
                select 'effector'::text
                from effector
                where revision_id = NEW.revision_id
                union all
                select 'receptor'::text
                from receptor
                where revision_id = NEW.revision_id
                union all
                select 'interaction'::text
                from interaction
                where revision_id = NEW.revision_id
                union all
                select 'directive'::text
                from directive
                where revision_id = NEW.revision_id
                union all
                select 'norm'::text
                from norm
                where revision_id = NEW.revision_id
            ) owners
        where owners.gsm_type = NEW.gsm_type
    ) into type_matches;
if not type_matches then raise exception 'ascription_status_transition.gsm_type % does not match owner type for revision_id %',
NEW.gsm_type,
NEW.revision_id;
end if;
return NEW;
end;
$$;
create constraint trigger trg_ast_owner_exists
after
insert
    or
update on ascription_status_transition deferrable initially deferred for each row execute function tgf_assert_transition_owner_exists();
create or replace function tgf_sync_owner_status_from_transition() returns trigger language plpgsql as $$ begin perform set_config('sif.status_sync', 'on', true);
execute format(
    'update %I set status = $1 where revision_id = $2',
    NEW.gsm_type
) using NEW.post_status,
NEW.revision_id;
perform set_config('sif.status_sync', 'off', true);
return NEW;
end;
$$;
create trigger trg_ast_sync_owner_status
after
insert on ascription_status_transition for each row execute function tgf_sync_owner_status_from_transition();
create or replace function tgf_assert_owner_status_matches_history() returns trigger language plpgsql as $$
declare latest_post_status ascription_status;
begin
select ast.post_status into latest_post_status
from ascription_status_transition ast
where ast.gsm_type = TG_TABLE_NAME
    and ast.revision_id = NEW.revision_id
order by ast.timestamp desc,
    ast.id desc
limit 1;
if latest_post_status is null then raise exception 'owner %.% must have at least one status transition',
TG_TABLE_NAME,
NEW.revision_id;
end if;
if NEW.status is distinct
from latest_post_status then raise exception 'owner %.% status % does not match latest transition status %',
    TG_TABLE_NAME,
    NEW.revision_id,
    NEW.status,
    latest_post_status;
end if;
return NEW;
end;
$$;
create constraint trigger trg_archetype_status_matches_history
after
insert
    or
update of status on archetype deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_structure_status_matches_history
after
insert
    or
update of status on structure deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_mechanism_status_matches_history
after
insert
    or
update of status on mechanism deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_interface_status_matches_history
after
insert
    or
update of status on interface deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_effector_status_matches_history
after
insert
    or
update of status on effector deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_receptor_status_matches_history
after
insert
    or
update of status on receptor deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_interaction_status_matches_history
after
insert
    or
update of status on interaction deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_directive_status_matches_history
after
insert
    or
update of status on directive deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create constraint trigger trg_norm_status_matches_history
after
insert
    or
update of status on norm deferrable initially deferred for each row execute function tgf_assert_owner_status_matches_history();
create or replace function tgf_reject_transition_mutation() returns trigger language plpgsql as $$ begin raise exception 'ascription_status_transition rows are immutable; % is not allowed for id %',
    TG_OP,
    coalesce(OLD.id, NEW.id);
end;
$$;
create trigger trg_ast_no_update before
update on ascription_status_transition for each row execute function tgf_reject_transition_mutation();
create trigger trg_ast_no_delete before delete on ascription_status_transition for each row execute function tgf_reject_transition_mutation();
create or replace function tgf_reject_status_update() returns trigger language plpgsql as $$ begin if current_setting('sif.status_sync', true) = 'on' then return NEW;
end if;
if NEW.status is distinct
from OLD.status then raise exception 'status is derived from the latest status transition on table %: old %, new %',
    TG_TABLE_NAME,
    OLD.status,
    NEW.status;
end if;
return NEW;
end;
$$;
create trigger trg_archetype_status_immutable before
update of status on archetype for each row execute function tgf_reject_status_update();
create trigger trg_structure_status_immutable before
update of status on structure for each row execute function tgf_reject_status_update();
create trigger trg_mechanism_status_immutable before
update of status on mechanism for each row execute function tgf_reject_status_update();
create trigger trg_interface_status_immutable before
update of status on interface for each row execute function tgf_reject_status_update();
create trigger trg_effector_status_immutable before
update of status on effector for each row execute function tgf_reject_status_update();
create trigger trg_receptor_status_immutable before
update of status on receptor for each row execute function tgf_reject_status_update();
create trigger trg_interaction_status_immutable before
update of status on interaction for each row execute function tgf_reject_status_update();
create trigger trg_directive_status_immutable before
update of status on directive for each row execute function tgf_reject_status_update();
create trigger trg_norm_status_immutable before
update of status on norm for each row execute function tgf_reject_status_update();
create or replace function tgf_reject_revision_id_update() returns trigger language plpgsql as $$ begin if NEW.revision_id is distinct
from OLD.revision_id then raise exception 'revision_id is immutable on table %: old %, new %',
    TG_TABLE_NAME,
    OLD.revision_id,
    NEW.revision_id;
end if;
return NEW;
end;
$$;
create trigger trg_archetype_revision_immutable before
update of revision_id on archetype for each row execute function tgf_reject_revision_id_update();
create trigger trg_structure_revision_immutable before
update of revision_id on structure for each row execute function tgf_reject_revision_id_update();
create trigger trg_mechanism_revision_immutable before
update of revision_id on mechanism for each row execute function tgf_reject_revision_id_update();
create trigger trg_interface_revision_immutable before
update of revision_id on interface for each row execute function tgf_reject_revision_id_update();
create trigger trg_effector_revision_immutable before
update of revision_id on effector for each row execute function tgf_reject_revision_id_update();
create trigger trg_receptor_revision_immutable before
update of revision_id on receptor for each row execute function tgf_reject_revision_id_update();
create trigger trg_interaction_revision_immutable before
update of revision_id on interaction for each row execute function tgf_reject_revision_id_update();
create trigger trg_directive_revision_immutable before
update of revision_id on directive for each row execute function tgf_reject_revision_id_update();
create trigger trg_norm_revision_immutable before
update of revision_id on norm for each row execute function tgf_reject_revision_id_update();
create or replace function tgf_restrict_owner_delete_when_transitions_exist() returns trigger language plpgsql as $$ begin if exists (
        select 1
        from ascription_status_transition ast
        where ast.gsm_type = TG_TABLE_NAME
            and ast.revision_id = OLD.revision_id
    ) then raise exception 'cannot delete %.% while status transitions exist',
    TG_TABLE_NAME,
    OLD.revision_id;
end if;
return OLD;
end;
$$;
create trigger trg_archetype_delete_restrict before delete on archetype for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_structure_delete_restrict before delete on structure for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_mechanism_delete_restrict before delete on mechanism for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_interface_delete_restrict before delete on interface for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_effector_delete_restrict before delete on effector for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_receptor_delete_restrict before delete on receptor for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_interaction_delete_restrict before delete on interaction for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_directive_delete_restrict before delete on directive for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
create trigger trg_norm_delete_restrict before delete on norm for each row execute function tgf_restrict_owner_delete_when_transitions_exist();
commit;