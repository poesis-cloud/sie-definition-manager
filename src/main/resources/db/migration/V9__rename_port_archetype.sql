-- V9: Rename port_archetype_id to output_archetype_id (effector)
-- and input_archetype_id (receptor).
begin;
-- Effector: rename column + index
alter table effector
    rename column port_archetype_id to output_archetype_id;
alter index idx_effector_port_arch
rename to idx_effector_output_arch;
-- Receptor: rename column + index
alter table receptor
    rename column port_archetype_id to input_archetype_id;
alter index idx_receptor_port_arch
rename to idx_receptor_input_arch;
-- Update seed schemas in archetype table
update archetype
set definition = jsonb_set(
        definition,
        '{schema}',
        replace(
            replace(
                definition->>'schema',
                '"portArchetypeId"',
                '"outputArchetypeId"'
            ),
            'Archetype that types the port data.',
            'Archetype that types the Effector output data.'
        )::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Effector.schema.json';
update archetype
set definition = jsonb_set(
        definition,
        '{schema}',
        replace(
            replace(
                definition->>'schema',
                '"portArchetypeId"',
                '"inputArchetypeId"'
            ),
            'Archetype that types the port data.',
            'Archetype that types the Receptor input data.'
        )::jsonb
    )
where schema_uri = 'urn:sie:gsm:v1:Receptor.schema.json';
commit;