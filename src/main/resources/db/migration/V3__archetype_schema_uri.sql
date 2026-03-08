-- ============================================================
-- V3__archetype_schema_uri.sql
-- Adds schema_uri envelope column to archetype table (Option D).
-- schema_uri is assigned by the definition-manager after publishing
-- definition.schema content to the schema registry.
-- ============================================================

alter table archetype add column if not exists schema_uri text;

-- Migrate existing seed data: copy definition->>'schemaUri' to envelope column
update archetype
   set schema_uri = definition->>'schemaUri'
 where definition ? 'schemaUri'
   and schema_uri is null;

-- Index for lookup by schema URI (e.g., resolve base archetype from URN)
create index if not exists idx_archetype_schema_uri
    on archetype (schema_uri)
 where schema_uri is not null;
