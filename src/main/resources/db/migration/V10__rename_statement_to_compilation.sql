-- ============================================================
-- V10: Rename column `statement` → `compilation` across all
--      GSM class tables and the ascription_all union view.
--
-- Rationale: the payload is the *compiled output* produced by
-- the definition-manager (a compiler), not a raw "statement".
-- Aligns DB schema with the GSM terminology update.
-- ============================================================
begin;
-- 1. Rename column on each class table
alter table archetype
  rename column statement to compilation;
alter table structure
  rename column statement to compilation;
alter table mechanism
  rename column statement to compilation;
alter table effector
  rename column statement to compilation;
alter table receptor
  rename column statement to compilation;
alter table interaction
  rename column statement to compilation;
alter table interface
  rename column statement to compilation;
alter table directive
  rename column statement to compilation;
alter table norm
  rename column statement to compilation;
-- 2. Recreate the union view with the new column name
create or replace view ascription_all as
select 'ARCHETYPE'::definition_subject_type as subject_type,
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from archetype
union all
select 'STRUCTURE',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from structure
union all
select 'MECHANISM',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from mechanism
union all
select 'EFFECTOR',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from effector
union all
select 'RECEPTOR',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from receptor
union all
select 'INTERACTION',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from interaction
union all
select 'INTERFACE',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from interface
union all
select 'DIRECTIVE',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from directive
union all
select 'NORM',
  definition_id,
  id,
  "timestamp",
  archetype_id,
  compilation,
  version,
  status
from norm;
commit;