# Vendored GSM base schemas

Pinned snapshot of the GSM base Archetype schemas. **Source of truth:**
`gsm/gsm-specifications/schemas/` (sibling repo in the Poesis workspace).

- On the classpath as `gsm/schemas/` (regular `src/main/resources` content).
- Refresh: `make sync-gsm-schemas`.
- Drift guard: `GsmSchemaVendorSyncTest` fails the build if this snapshot
  differs from the spec repo when the sibling checkout is present.
- Never edit these files here — change them in gsm-specifications and sync.
  (Excluded from Spotless so the bytes stay identical to upstream.)
