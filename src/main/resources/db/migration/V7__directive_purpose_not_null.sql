-- ============================================================
-- V7: Make directive.purpose_id NOT NULL
--
-- GSM: Directive.purpose is non-nullable — every Directive MUST
-- reference a purposed Structure.
-- ============================================================

begin;

-- 1. Enforce NOT NULL on purpose_id
alter table directive
  alter column purpose_id set not null;

-- 2. Replace partial index with a full index (no longer nullable)
drop index if exists idx_directive_purpose;
create index idx_directive_purpose on directive (purpose_id);

commit;
