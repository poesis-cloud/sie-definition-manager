-- V2: Make revision_id generation conditional in tgf_assign_ids().
-- When JPA supplies a revision_id (NOT NULL), preserve it.
-- When NULL (raw SQL insert), generate one.
-- revision_timestamp is ALWAYS overwritten by the DB (authoritative).

CREATE OR REPLACE FUNCTION tgf_assign_ids()
RETURNS trigger AS $$
BEGIN
  -- id: generate only when NULL (JPA always supplies one)
  IF NEW.id IS NULL THEN
    NEW.id := gen_random_uuid();   -- UUIDv4 fallback
  END IF;

  -- revision_id: generate only when NULL (JPA supplies one; raw SQL may not)
  IF NEW.revision_id IS NULL THEN
    NEW.revision_id := gen_random_uuid();
  END IF;

  -- revision_timestamp: ALWAYS set by DB (authoritative)
  NEW.revision_timestamp := now();

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
