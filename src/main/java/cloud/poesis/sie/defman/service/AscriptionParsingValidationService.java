package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Validates ascription statements against archetype JSON Schemas.
 *
 * <p>Extracted from {@link AscriptionService} to separate statement/schema validation concerns from
 * entity lifecycle management.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class AscriptionParsingValidationService {

  private static final Map<String, String> GSM_BASE_SCHEMA_LOCATIONS =
      DefinitionSubjectType.archetypeTitles().stream()
          .collect(
              java.util.stream.Collectors.toUnmodifiableMap(
                  title -> "gsmarc://gsm/" + title + "/v1",
                  title -> "classpath:gsm/schemas/" + title + ".schema.json"));

  /**
   * Classpath-only JSON Schema factory for resolving GSM base archetype {@code gsmarc://gsm/} URIs.
   * Used when no tenant archetypes need DB resolution. GSM §8 security invariant: DM MUST NOT
   * resolve {@code $schema} URIs from incoming tenant schemas via network — all resolution is
   * local.
   */
  private static final JsonSchemaFactory CLASSPATH_SCHEMA_FACTORY =
      JsonSchemaFactory.getInstance(
          SpecVersion.VersionFlag.V202012,
          builder ->
              builder.schemaMappers(
                  mappers ->
                      mappers.mappings(
                          GSM_BASE_SCHEMA_LOCATIONS::containsKey, GSM_BASE_SCHEMA_LOCATIONS::get)));

  // GSM base schema property sets for extensible subject types (sealed — derived
  // from DefinitionSubjectType.statementProperties and never change at runtime).
  // Used to classify validation errors as GSM-base vs tenant-extension.
  private static final Map<DefinitionSubjectType, Set<String>> GSM_BASE_PROPERTIES;

  static {
    var map = new EnumMap<DefinitionSubjectType, Set<String>>(DefinitionSubjectType.class);
    for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
      Set<String> props = type.getStatementProperties();
      if (!props.isEmpty()) {
        map.put(type, props);
      }
    }
    GSM_BASE_PROPERTIES = Collections.unmodifiableMap(map);
  }

  private final ArchetypeParsingService archetypeSchemaService;
  private final JsonSchemaPositionWalker schemaPositionWalker;

  public AscriptionParsingValidationService(
      ArchetypeParsingService archetypeSchemaService,
      JsonSchemaPositionWalker schemaPositionWalker) {
    this.archetypeSchemaService = archetypeSchemaService;
    this.schemaPositionWalker = schemaPositionWalker;
  }

  // ======================================================================
  // Statement validation (JSON Schema)
  // ======================================================================

  /**
   * Validates a statement against the archetype's JSON Schema.
   *
   * @param statement the JSON statement payload to validate
   * @param archetype the archetype whose schema defines the validation surface
   * @param subjectType the GSM subject type (used for error classification)
   * @throws RuleViolationException if validation fails
   */
  void validateStatement(
      JsonNode statement, ArchetypeEntity archetype, DefinitionSubjectType subjectType) {
    JsonNode archetypeStatement = archetype.getStatement();

    SchemaValidatorsConfig config =
        SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    JsonSchemaFactory factory = buildSchemaFactory(archetypeStatement);
    JsonSchema schema =
        factory.getSchema(applyStatementClosure(archetypeStatement, subjectType), config);
    Set<ValidationMessage> errors = schema.validate(statement);

    if (errors.isEmpty()) {
      return;
    }

    AscriptionConsistencyRuleType baseRule = statementValidationRule();
    AscriptionConsistencyRuleType extensionRule = extensionStatementValidationRule(subjectType);
    Set<String> baseProps = GSM_BASE_PROPERTIES.get(subjectType);

    if (baseProps != null && extensionRule != null) {
      List<String> baseMessages = new ArrayList<>();
      List<String> extensionMessages = new ArrayList<>();

      for (ValidationMessage err : errors) {
        if (isBaseSchemaError(err, baseProps)) {
          baseMessages.add(err.getMessage());
        } else {
          extensionMessages.add(err.getMessage());
        }
      }

      if (!baseMessages.isEmpty()) {
        throw RuleViolationException.of(
            baseRule,
            "Statement validation failed against archetype "
                + archetype.getDefinition().getId()
                + ": "
                + baseMessages,
            "archetypeDefinitionId",
            archetype.getDefinition().getId(),
            "violations",
            baseMessages);
      }
      if (!extensionMessages.isEmpty()) {
        throw RuleViolationException.of(
            extensionRule,
            "Statement validation failed against tenant-extended archetype "
                + archetype.getDefinition().getId()
                + ": "
                + extensionMessages,
            "archetypeDefinitionId",
            archetype.getDefinition().getId(),
            "violations",
            extensionMessages);
      }
    }

    List<String> messages = errors.stream().map(ValidationMessage::getMessage).toList();
    throw RuleViolationException.of(
        baseRule,
        "Statement validation failed against archetype "
            + archetype.getDefinition().getId()
            + ": "
            + messages,
        "archetypeDefinitionId",
        archetype.getDefinition().getId(),
        "violations",
        messages);
  }

  // ======================================================================
  // Statement closure (GSM §5 — "Statement closure")
  // ======================================================================

  /**
   * Returns the schema to validate a statement against: the typing archetype schema with {@code
   * unevaluatedProperties: false} applied at its root, so that a governed statement carries only
   * properties declared somewhere in the resolved chain.
   *
   * <p>Closure is applied here rather than declared in the schemas because it is only expressible
   * where the concrete typing archetype is known — see GSM §5 ("Statement closure") in {@code
   * gsm-specifications/model/gsm.puml} for the normative rule and its rationale.
   *
   * <p>{@link DefinitionSubjectType#ARCHETYPE} statements are exempt: an Archetype statement is
   * itself a JSON Schema, and the sealed Archetype meta-schema is deliberately open so tenants may
   * declare vocabulary keywords. Their extra keywords are governed by {@code
   * ArchetypeAnnotationValidationService}, not by closure.
   *
   * <p>The input node is never mutated and the persisted archetype statement is unaffected. Schemas
   * already declaring an at-least-as-strict top-level closure are returned unchanged.
   *
   * @param archetypeSchema the resolved typing archetype schema
   * @param subjectType the GSM subject type of the statement being validated
   * @return the schema to validate against
   */
  static JsonNode applyStatementClosure(
      JsonNode archetypeSchema, DefinitionSubjectType subjectType) {
    if (subjectType == DefinitionSubjectType.ARCHETYPE
        || !archetypeSchema.isObject()
        || declaresClosure(archetypeSchema)) {
      return archetypeSchema;
    }
    ObjectNode closed = JsonNodeFactory.instance.objectNode();
    closed.setAll((ObjectNode) archetypeSchema);
    closed.put("unevaluatedProperties", false);
    return closed;
  }

  /**
   * Reports whether a schema already closes itself at top level. Nested closures are irrelevant —
   * they constrain sub-objects, not the statement root.
   */
  private static boolean declaresClosure(JsonNode schema) {
    return isFalse(schema.get("unevaluatedProperties"))
        || isFalse(schema.get("additionalProperties"));
  }

  private static boolean isFalse(JsonNode node) {
    return node != null && node.isBoolean() && !node.booleanValue();
  }

  // ======================================================================
  // Rule type derivation
  // ======================================================================

  private static AscriptionConsistencyRuleType statementValidationRule() {
    return AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE;
  }

  private static AscriptionConsistencyRuleType extensionStatementValidationRule(
      DefinitionSubjectType subjectType) {
    return switch (subjectType) {
      case STRUCTURE, MECHANISM, EFFECTOR, RECEPTOR, INTERACTION, DIRECTIVE, NORM ->
          AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_NON_GSM_ARCHETYPE;
      case ARCHETYPE -> null;
    };
  }

  // ======================================================================
  // Schema factory with tenant-aware resolution
  // ======================================================================

  JsonSchemaFactory buildSchemaFactory(JsonNode archetypeSchema) {
    Map<String, String> tenantSchemaJsonByUri = collectTenantSchemaMap(archetypeSchema);

    if (tenantSchemaJsonByUri.isEmpty()) {
      return CLASSPATH_SCHEMA_FACTORY;
    }

    return JsonSchemaFactory.getInstance(
        SpecVersion.VersionFlag.V202012,
        builder ->
            builder
                .schemaLoaders(
                    loaders ->
                        loaders.add(
                            iri -> {
                              String uri = iri.toString();
                              String json = tenantSchemaJsonByUri.get(uri);
                              if (json != null) {
                                return () ->
                                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
                              }
                              return null;
                            }))
                .schemaMappers(
                    mappers ->
                        mappers.mappings(
                            GSM_BASE_SCHEMA_LOCATIONS::containsKey,
                            GSM_BASE_SCHEMA_LOCATIONS::get)));
  }

  static String gsmBaseSchemaLocation(String identity) {
    return GSM_BASE_SCHEMA_LOCATIONS.get(identity);
  }

  // ======================================================================
  // Tenant schema resolution helpers
  // ======================================================================

  private Map<String, String> collectTenantSchemaMap(JsonNode schema) {
    Map<String, String> result = new HashMap<>();
    collectTenantRefs(schema, result);
    return result;
  }

  private void collectTenantRefs(JsonNode schema, Map<String, String> result) {
    schemaPositionWalker.walk(
        schema,
        (schemaPosition, pointer) -> {
          JsonNode refNode = schemaPosition.get("$ref");
          if (refNode != null && refNode.isTextual()) {
            String ref = refNode.asText();
            if (!result.containsKey(ref)
                && ArchetypeParsingService.isAllowedRef(ref)
                && !ref.startsWith("#")) {
              if (!ArchetypeParsingService.isGsmBaseId(ref)) {
                resolveTenantArchetypeFromDb(ref, result);
              }
            }
          }
        });
  }

  private void resolveTenantArchetypeFromDb(String uri, Map<String, String> result) {
    var found = archetypeSchemaService.findResolvableByUri(uri);
    if (found.isPresent()) {
      JsonNode stmt = found.get().getStatement();
      result.put(uri, stmt.toString());
      collectTenantRefs(stmt, result);
      return;
    }
    throw RuleViolationException.of(
        AscriptionConsistencyRuleType.ARCHETYPE_REF_INTEGRITY,
        "Archetype $ref does not resolve to a governed Archetype URI: " + uri,
        "$ref",
        uri);
  }

  // ======================================================================
  // Error classification helpers
  // ======================================================================

  private static boolean isBaseSchemaError(ValidationMessage error, Set<String> baseProps) {
    var instanceLoc = error.getInstanceLocation();
    if (instanceLoc != null && instanceLoc.getNameCount() > 0) {
      String rootProp = instanceLoc.getName(0);
      return baseProps.contains(rootProp);
    }
    String property = error.getProperty();
    if (property != null && !property.isEmpty()) {
      return baseProps.contains(property);
    }
    return true;
  }
}
