-- SIE CSDM (v1) — Qualifiers + Schemas
--
-- Translation of the conceptual model (Schema, SystemicPrimitivePrototype,
-- SystemicPrimitiveQualifierDefinition, SystemicPrimitiveQualifier).
--
-- Key choices:
-- - Qualifier VALUES are always per primitive instance.
-- - Qualifier DEFINITIONS may be available at prototype-level and/or instance-level.
-- - Value stored as JSONB; schema validation left to app/integration (as agreed).
-- - Keep a single qualifier-value table (no partitioning) to avoid DDL bloat.

begin;

-- ============================================================
-- REGISTRY
-- ============================================================

create table if not exists schema_definition (
  id  uuid primary key,
  uri text not null unique
);

create table if not exists systemic_primitive_qualifier_definition (
  id        uuid primary key,
  schema_id uuid not null references schema_definition(id)
);

create table if not exists systemic_primitive_prototype (
  id        uuid primary key,
  schema_id uuid not null references schema_definition(id),
  name      text not null unique
);

-- Prototype-level availability of qualifier definitions
create table if not exists prototype_qualifier_definition (
  prototype_id uuid not null references systemic_primitive_prototype(id) on delete cascade,
  qdef_id      uuid not null references systemic_primitive_qualifier_definition(id),
  primary key (prototype_id, qdef_id)
);

-- ============================================================
-- PRIMITIVES (SQL-only anchor; not required in UML)
-- ============================================================

-- Anchor/supertype to provide FK integrity for qualifier values,
-- while still allowing one table per concrete primitive subtype (as in the CSDM).
create table if not exists systemic_primitive (
  id            uuid primary key,
  prototype_id  uuid not null references systemic_primitive_prototype(id),
  primitive_type text not null
);

create index if not exists ix_systemic_primitive_prototype
  on systemic_primitive(prototype_id);

create index if not exists ix_systemic_primitive_type
  on systemic_primitive(primitive_type);

-- ============================================================
-- INSTANCE-LEVEL QUALIFIER DEFINITIONS
-- ============================================================

-- A primitive instance may own extra qualifier definitions beyond its prototype.
create table if not exists primitive_qualifier_definition (
  primitive_id uuid not null references systemic_primitive(id) on delete cascade,
  qdef_id      uuid not null references systemic_primitive_qualifier_definition(id),
  primary key (primitive_id, qdef_id)
);

-- ============================================================
-- QUALIFIER VALUES (always per primitive instance)
-- ============================================================

create table if not exists systemic_primitive_qualifier (
  id           uuid primary key,
  primitive_id uuid not null references systemic_primitive(id) on delete cascade,
  qdef_id      uuid not null references systemic_primitive_qualifier_definition(id),
  value_json   jsonb not null,
  unique (primitive_id, qdef_id)
);

create index if not exists ix_spq_qdef
  on systemic_primitive_qualifier(qdef_id);

create index if not exists ix_spq_primitive
  on systemic_primitive_qualifier(primitive_id);

-- ============================================================
-- OPTIONAL ENFORCEMENT ("definition must be allowed")
--
-- Allowed set for a primitive instance is:
-- 1) prototype_qualifier_definition(prototype_id, qdef_id)
--    where prototype_id = systemic_primitive.prototype_id
-- UNION
-- 2) primitive_qualifier_definition(primitive_id, qdef_id)
--
-- This is enforceable in SQL via triggers.
-- ============================================================

create or replace function sie_enforce_qualifier_definition_allowed()
returns trigger
language plpgsql
as $$
declare
  v_prototype_id uuid;
  v_allowed boolean;
begin
  select sp.prototype_id
    into v_prototype_id
  from systemic_primitive sp
  where sp.id = new.primitive_id;

  if v_prototype_id is null then
    raise exception 'Unknown systemic_primitive %', new.primitive_id;
  end if;

  v_allowed := exists (
    select 1
    from prototype_qualifier_definition pqd
    where pqd.prototype_id = v_prototype_id
      and pqd.qdef_id = new.qdef_id
  )
  or exists (
    select 1
    from primitive_qualifier_definition iqd
    where iqd.primitive_id = new.primitive_id
      and iqd.qdef_id = new.qdef_id
  );

  if not v_allowed then
    raise exception 'Qualifier definition % not allowed for primitive %', new.qdef_id, new.primitive_id;
  end if;

  return new;
end;
$$;

drop trigger if exists trg_spq_enforce_allowed on systemic_primitive_qualifier;
create trigger trg_spq_enforce_allowed
before insert or update of primitive_id, qdef_id on systemic_primitive_qualifier
for each row execute function sie_enforce_qualifier_definition_allowed();

commit;

-- Notes:
-- - primitive_type is kept on systemic_primitive for classification and filtering.
-- - If you want strict control of primitive_type values, replace text with an enum.
