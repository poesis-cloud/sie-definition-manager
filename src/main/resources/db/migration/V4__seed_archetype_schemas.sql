-- ============================================================
-- V4__seed_archetype_schemas.sql
-- Adds actual JSON Schema content to each seed archetype's
-- definition payload. The 'schema' key carries the JSON Schema
-- document (draft 2020-12) that validates ascription definitions
-- typed by that archetype.
--
-- This enables:
--   - definition-time validation of ascription payloads
--   - dynamic OpenAPI documentation from stored schemas
--   - gsmType derivation from archetype schema_uri
-- ============================================================
-- 1. Seed Archetype (meta-schema for archetypes)
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Archetype.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "schemaUri": { "type": "string", "description": "Canonical URI for the JSON Schema." },
               "schema": { "type": "object", "description": "JSON Schema document (draft 2020-12+)." }
           },
           "required": ["schema"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Archetype.schema.json';
-- 2. StructureArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Structure.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "purpose": { "type": "string", "minLength": 1, "description": "The Structure raison d etre and identity discriminator. Globally unique." }
           },
           "required": ["purpose"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Structure.schema.json';
-- 3. MechanismArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Mechanism.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "structureId": { "type": "string", "format": "uuid", "description": "Reference to the owning Structure revision_id." },
               "function": { "type": "string", "minLength": 1, "description": "Capability label unique within the owning Structure." },
               "rule": { "type": "string", "description": "Starlark source code." },
               "ruleLanguage": { "type": "string", "enum": ["STARLARK"] }
           },
           "required": ["structureId", "function", "rule", "ruleLanguage"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Mechanism.schema.json';
-- 4. EffectorArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Effector.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "mechanismId": { "type": "string", "format": "uuid", "description": "Reference to the owning Mechanism revision_id." },
               "portArchetypeId": { "type": "string", "format": "uuid", "description": "Archetype that types the port data." },
               "interfaceId": { "type": "string", "format": "uuid", "description": "Optional Interface exposing this port." }
           },
           "required": ["mechanismId", "portArchetypeId"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Effector.schema.json';
-- 5. ReceptorArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Receptor.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "mechanismId": { "type": "string", "format": "uuid", "description": "Reference to the owning Mechanism revision_id." },
               "portArchetypeId": { "type": "string", "format": "uuid", "description": "Archetype that types the port data." },
               "interfaceId": { "type": "string", "format": "uuid", "description": "Optional Interface exposing this port." }
           },
           "required": ["mechanismId", "portArchetypeId"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Receptor.schema.json';
-- 6. InteractionArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Interaction.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "effectorId": { "type": "string", "format": "uuid", "description": "The emitting Effector revision_id." },
               "receptorId": { "type": "string", "format": "uuid", "description": "The receiving Receptor revision_id." }
           },
           "required": ["effectorId", "receptorId"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Interaction.schema.json';
-- 7. InterfaceArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Interface.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "structureId": { "type": "string", "format": "uuid", "description": "Reference to the owning Structure revision_id." },
               "effectors": { "type": "array", "items": { "type": "string", "format": "uuid" }, "description": "Effector revision_ids exposed by this Interface." },
               "receptors": { "type": "array", "items": { "type": "string", "format": "uuid" }, "description": "Receptor revision_ids exposed by this Interface." }
           },
           "required": ["structureId"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Interface.schema.json';
-- 8. DirectiveArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Directive.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "structureId": { "type": "string", "format": "uuid", "description": "The authoring Structure revision_id." },
               "modal": { "type": "string", "enum": ["MUST", "MUST_NOT", "SHOULD", "SHOULD_NOT", "MAY"] },
               "verb": { "type": "string", "enum": ["ENSURE", "PREVENT", "MAINTAIN", "OPTIMIZE", "MINIMIZE", "MAXIMIZE", "MONITOR", "ENABLE"] },
               "qualifierId": { "type": "string", "format": "uuid", "description": "Archetype defining the viability dimension." },
               "purposeId": { "type": "string", "format": "uuid", "description": "Optional purposed Structure revision_id." }
           },
           "required": ["structureId", "modal", "verb", "qualifierId"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Directive.schema.json';
-- 9. NormArchetype
update archetype
set definition = definition || jsonb_build_object(
        'schema',
        '{
           "$schema": "https://json-schema.org/draft/2020-12/schema",
           "$id": "urn:sie:gsm:v1:Norm.schema.json",
           "$gsm:sealed": true,
           "type": "object",
           "properties": {
               "structureId": { "type": "string", "format": "uuid", "description": "The authoring Structure revision_id." },
               "guard": { "type": "string", "default": "true", "description": "CEL applicability-guard expression." },
               "predicate": { "type": "string", "description": "CEL property-assertion expression." },
               "toleranceMode": { "type": "string", "enum": ["INSTANTANEOUS", "AGGREGATED", "SUSTAINED"] },
               "temporalWindow": { "type": "string", "description": "ISO 8601 duration for time-based modes." },
               "temporalAggregation": { "type": "string", "enum": ["SUM", "AVG", "MIN", "MAX", "COUNT", "P50", "P90", "P95", "P99"] },
               "sustainedThreshold": { "type": "number", "minimum": 0, "maximum": 1 }
           },
           "required": ["structureId", "predicate", "toleranceMode"],
           "additionalProperties": true
       }'::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Norm.schema.json';