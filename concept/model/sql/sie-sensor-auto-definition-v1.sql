-- ============================================================
-- SIE Sensor Auto-Definition Logic - v1
-- ============================================================
--
-- This pseudo-code / SQL defines how SIE automatically creates
-- SensorMechanisms when Policies have measurable evaluationCriteria.
--
-- This closes the homeostatic loop:
--   Policy.evaluationCriteria → SensorMechanism → MeasurementEvent
--   → Interaction(Sensor.Effector → Governor.Receptor)
--   → Governor receives feedback → Governor adapts Policy
--
-- ============================================================
-- CONCEPTUAL ALGORITHM (Pseudo-Code)
-- ============================================================
--
-- FUNCTION auto_define_sensor_for_policy(policy: Policy) -> SensorMechanism
--
--   -- Pre-conditions
--   ASSERT policy.evaluationCriteria IS NOT EMPTY
--   ASSERT policy.status == INTEGRATED
--   ASSERT NOT EXISTS sensor WHERE sensor.measures == policy
--
--   -- Step 1: Derive measurement schema from evaluationCriteria
--   -- The evaluationCriteria is a CSDSL JSON AST expression.
--   -- We extract measured quantities to define the schema.
--
--   measurementSchema = derive_measurement_schema(policy.evaluationCriteria)
--
--   -- Example: if evaluationCriteria = "P95(latency) <= 100ms"
--   -- Then measurementSchema captures:
--   --   { "metricName": "latency", "aggregation": "P95", "unit": "ms" }
--
--   -- Step 2: Create EventArchetype for measurement events
--   measurementEventArchetype = CREATE EventArchetype(
--     name = policy.name || "_Measurement",
--     schema = CREATE ArchetypeSchema(
--       uri = "urn:sie:measurement:" || policy.id,
--       definition = measurementSchema
--     )
--   )
--
--   -- Step 3: Determine source event archetype (what the sensor observes)
--   -- Sensors observe ActuationFeedbackEvents from the Regulation Plane.
--   sourceEventArchetype = FIND EventArchetype
--     WHERE schema.name LIKE '%ActuationFeedbackEvent%'
--       AND scope INTERSECTS policy.scope
--
--   -- Step 4: Create SensorMechanism
--   sensorMechanism = CREATE SensorMechanism(
--     name = policy.name || "_Sensor"
--   )
--
--   -- Step 5: Create Receptor (observes actuation feedback)
--   sensorReceptor = CREATE Receptor(
--     name = sensorMechanism.name || "_Receptor",
--     receivedEventArchetype = sourceEventArchetype
--   )
--   ADD sensorReceptor TO sensorMechanism.receptors
--
--   -- Step 6: Create Effector (emits measurement events)
--   sensorEffector = CREATE Effector(
--     name = sensorMechanism.name || "_Effector",
--     producedEventArchetype = measurementEventArchetype,
--     producedEventSourceMapping = CREATE EffectorEventSourceMapping(
--       valueMapping = derive_value_mapping(sourceEventArchetype, measurementSchema)
--     )
--   )
--   ADD sensorEffector TO sensorMechanism.effectors
--
--   -- Step 7: Create Interaction to Governor
--   -- The Governor that owns the Policy must receive the measurement.
--   governorMechanism = FIND GovernorControllerMechanism
--     WHERE policy IN governorMechanism.ownedPolicies
--
--   governorReceptor = FIND OR CREATE Receptor IN governorMechanism
--     WHERE receivedEventArchetype.schema.name LIKE '%MeasurementEvent%'
--   -- If not exists, create it:
--   IF governorReceptor IS NULL THEN
--     governorReceptor = CREATE Receptor(
--       name = governorMechanism.name || "_Measurement_Receptor",
--       receivedEventArchetype = measurementEventArchetype
--     )
--     ADD governorReceptor TO governorMechanism.receptors
--   END IF
--
--   feedbackInteraction = CREATE Interaction(
--     name = sensorMechanism.name || "_to_" || governorMechanism.name,
--     effector = sensorEffector,
--     receptor = governorReceptor
--   )
--
--   -- Step 8: Emit event for traceability
--   EMIT SensorAutoDefinedEvent(
--     policyId = policy.id,
--     sensorId = sensorMechanism.id,
--     measurementEventArchetypeId = measurementEventArchetype.id,
--     interactionId = feedbackInteraction.id
--   )
--
--   RETURN sensorMechanism
--
-- END FUNCTION
--
-- ============================================================
-- HELPER: derive_measurement_schema
-- ============================================================
--
-- FUNCTION derive_measurement_schema(evaluationCriteria: JSON) -> JSON
--
--   -- evaluationCriteria is a CSDSL JSON AST expression.
--   -- We traverse it to extract measured quantities.
--
--   metrics = []
--
--   FOR EACH node IN traverse(evaluationCriteria):
--     IF node.type == "FunctionCallNode" THEN
--       -- Aggregation functions indicate metrics
--       -- E.g., AVG(latency), P95(response_time), COUNT(errors)
--       metric = {
--         "name": node.arguments[0].value,
--         "aggregation": node.functionName,
--         "sourceType": infer_source_type(node.arguments[0])
--       }
--       APPEND metric TO metrics
--
--     ELSE IF node.type == "VariableReferenceNode" AND is_metric(node) THEN
--       -- Direct metric references
--       metric = {
--         "name": node.variableName,
--         "aggregation": null,
--         "sourceType": infer_source_type(node)
--       }
--       APPEND metric TO metrics
--   END FOR
--
--   RETURN {
--     "type": "object",
--     "properties": {
--       "policyId": { "type": "string", "format": "uuid" },
--       "timestamp": { "type": "string", "format": "date-time" },
--       "metrics": {
--         "type": "array",
--         "items": {
--           "type": "object",
--           "properties": {
--             "name": { "type": "string" },
--             "value": { "type": "number" },
--             "aggregation": { "type": "string" },
--             "unit": { "type": "string" }
--           }
--         }
--       },
--       "evaluationResult": { "type": "boolean" },
--       "deviationMagnitude": { "type": "number" }
--     }
--   }
--
-- END FUNCTION
--
-- ============================================================
-- HELPER: derive_value_mapping
-- ============================================================
--
-- FUNCTION derive_value_mapping(
--   sourceArchetype: EventArchetype,
--   measurementSchema: JSON
-- ) -> Map<String, String>
--
--   -- Maps source event properties to measurement event properties.
--
--   mapping = {}
--
--   -- Fixed mappings
--   mapping["policyId"] = LITERAL(policy.id)
--   mapping["timestamp"] = "$.timestamp" -- from source
--
--   -- Dynamic mappings based on schema
--   FOR EACH metric IN measurementSchema.properties.metrics.items:
--     sourceProperty = FIND property IN sourceArchetype.schema
--       WHERE property.name == metric.name OR
--             property.semanticAlias == metric.name
--
--     IF sourceProperty EXISTS THEN
--       mapping["metrics[" || metric.name || "].value"] = 
--         "$.payload." || sourceProperty.path
--     ELSE
--       -- Flag for manual mapping or LLM assistance
--       mapping["metrics[" || metric.name || "].value"] = 
--         PLACEHOLDER(requires_mapping = true)
--     END IF
--   END FOR
--
--   RETURN mapping
--
-- END FUNCTION
--
-- ============================================================
-- SQL TRIGGER: Auto-define Sensor when Policy is INTEGRATED
-- ============================================================

begin;

-- Function to trigger sensor auto-definition
create or replace function sie_auto_define_sensor_on_policy_integrated()
returns trigger
language plpgsql
as $$
declare
  v_has_criteria boolean;
  v_sensor_exists boolean;
begin
  -- Only trigger on status change to INTEGRATED
  if new.status != 'INTEGRATED' or old.status = 'INTEGRATED' then
    return new;
  end if;

  -- Check if policy has evaluationCriteria
  v_has_criteria := new.evaluation_criteria is not null 
                    and new.evaluation_criteria::text != 'null'
                    and new.evaluation_criteria::text != '{}';

  if not v_has_criteria then
    return new;
  end if;

  -- Check if sensor already exists for this policy
  select exists (
    select 1 from sensor_mechanism sm
    where sm.measured_policy_id = new.id
  ) into v_sensor_exists;

  if v_sensor_exists then
    return new;
  end if;

  -- Queue sensor auto-definition job
  -- (Actual creation is async via job processor to allow LLM assistance)
  insert into sensor_auto_definition_queue (
    policy_id,
    policy_name,
    evaluation_criteria,
    created_at
  ) values (
    new.id,
    new.name,
    new.evaluation_criteria,
    now()
  );

  -- Emit domain event
  perform pg_notify('sensor_auto_definition', json_build_object(
    'policyId', new.id,
    'policyName', new.name,
    'timestamp', now()
  )::text);

  return new;
end;
$$;

-- Create the queue table
create table if not exists sensor_auto_definition_queue (
  id            uuid primary key default gen_random_uuid(),
  policy_id     uuid not null,
  policy_name   text not null,
  evaluation_criteria jsonb not null,
  status        text not null default 'PENDING',
  processor_id  text,
  started_at    timestamptz,
  completed_at  timestamptz,
  result        jsonb,
  error         text,
  created_at    timestamptz not null default now()
);

create index if not exists ix_sadq_status 
  on sensor_auto_definition_queue(status);

-- Attach trigger to policy table
drop trigger if exists trg_policy_auto_sensor on policy;
create trigger trg_policy_auto_sensor
after update of status on policy
for each row execute function sie_auto_define_sensor_on_policy_integrated();

-- ============================================================
-- EXAMPLE: Manual invocation for existing policies
-- ============================================================

-- Queue sensor auto-definition for all INTEGRATED policies without sensors
insert into sensor_auto_definition_queue (
  policy_id,
  policy_name,
  evaluation_criteria
)
select
  p.id,
  p.name,
  p.evaluation_criteria
from policy p
where p.status = 'INTEGRATED'
  and p.evaluation_criteria is not null
  and p.evaluation_criteria::text not in ('null', '{}')
  and not exists (
    select 1 from sensor_mechanism sm
    where sm.measured_policy_id = p.id
  );

commit;

-- ============================================================
-- NOTES
-- ============================================================
--
-- 1. The sensor auto-definition is queued for async processing because:
--    - Schema derivation may require LLM assistance for complex criteria
--    - Governor receptor creation may need manual configuration
--    - Event mapping may need semantic matching
--
-- 2. The job processor (Kafka consumer or similar) will:
--    - Dequeue pending items
--    - Call the pseudo-code algorithm above
--    - Use LLM for ambiguous mappings
--    - Create all primitives in a transaction
--    - Update queue status
--
-- 3. This implements BR-META-009 (Auto-Define Sensor for Policy)
--    from sie-meta-governance-v1.puml
--
-- ============================================================
