package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.service.MechanismRuleValidationService.PortSignature;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * GSM §Mechanism U12: auto-derives Effector and Receptor port entities from a Mechanism's Starlark
 * rule AST.
 *
 * <p>Parses port signatures from the validated rule, resolves data and port archetypes, and creates
 * (or reuses) Definitions and port entities. Supports typed port archetypes via {@code .by()} and
 * {@code .on()} Starlark DSL clauses.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class MechanismPortDerivationService {

  private static final Logger LOG = LoggerFactory.getLogger(MechanismPortDerivationService.class);

  private final MechanismRuleValidationService ruleValidation;
  private final ArchetypeService archetypeService;
  private final EffectorService effectorService;
  private final ReceptorService receptorService;
  private final DefinitionService definitionService;
  private final AscriptionStateMachineService stateMachine;
  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;

  public MechanismPortDerivationService(
      MechanismRuleValidationService ruleValidation,
      ArchetypeService archetypeService,
      @Lazy EffectorService effectorService,
      @Lazy ReceptorService receptorService,
      DefinitionService definitionService,
      AscriptionStateMachineService stateMachine,
      EntityManager entityManager,
      ObjectMapper objectMapper) {
    this.ruleValidation = ruleValidation;
    this.archetypeService = archetypeService;
    this.effectorService = effectorService;
    this.receptorService = receptorService;
    this.definitionService = definitionService;
    this.stateMachine = stateMachine;
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
  }

  /**
   * Derives port entities (Effectors and Receptors) from the Mechanism's Starlark rule.
   *
   * @param mechanism the newly created Mechanism entity
   */
  public void derivePortsFromRule(MechanismEntity mechanism) {
    String rule = mechanism.getStatement().get("rule").asText();
    List<PortSignature> signatures = ruleValidation.collectPortSignatures(rule);
    if (signatures.isEmpty()) {
      return;
    }
    derivePortEntities(mechanism, signatures);
  }

  /**
   * GSM §Mechanism U12: derive port entities with Definition reuse. Match existing Definitions by
   * (Mechanism Definition, data Archetype, direction). Resolves tenant port archetypes from
   * PortSignature.portArchetypeName (falls back to base EffectorArchetype/ReceptorArchetype).
   */
  private void derivePortEntities(MechanismEntity mechanism, List<PortSignature> signatures) {
    ArchetypeEntity baseEffectorArchetype =
        archetypeService.findInEffectBySchemaTitle("EffectorArchetype");
    ArchetypeEntity baseReceptorArchetype =
        archetypeService.findInEffectBySchemaTitle("ReceptorArchetype");
    if (baseEffectorArchetype == null || baseReceptorArchetype == null) {
      LOG.warn("Base EffectorArchetype/ReceptorArchetype not in-effect; skipping auto-derivation");
      return;
    }

    UUID mechanismDefId = mechanism.getDefinition().getId();

    Set<PortSignature> unique = new HashSet<>(signatures);

    for (PortSignature sig : unique) {
      ArchetypeEntity dataArchetype =
          archetypeService.findInEffectBySchemaTitle(sig.dataArchetypeName());
      if (dataArchetype == null) {
        LOG.warn(
            "Auto-derivation: data Archetype '{}' not in-effect; skipping port",
            sig.dataArchetypeName());
        continue;
      }

      if ("effector".equals(sig.direction())) {
        ArchetypeEntity portArchetype =
            resolvePortArchetype(sig.portArchetypeName(), baseEffectorArchetype, "Effector");
        deriveEffector(mechanism, mechanismDefId, portArchetype, dataArchetype);
      } else {
        ArchetypeEntity portArchetype =
            resolvePortArchetype(sig.portArchetypeName(), baseReceptorArchetype, "Receptor");
        deriveReceptor(mechanism, mechanismDefId, portArchetype, dataArchetype);
      }
    }
  }

  private ArchetypeEntity resolvePortArchetype(
      String portArchetypeName, ArchetypeEntity baseArchetype, String portKind) {
    if (portArchetypeName == null) {
      return baseArchetype;
    }
    ArchetypeEntity resolved = archetypeService.findInEffectBySchemaTitle(portArchetypeName);
    if (resolved == null) {
      LOG.warn(
          "Auto-derivation: {} port Archetype '{}' not in-effect; falling back to base {}",
          portKind,
          portArchetypeName,
          baseArchetype.getStatement().get("title").asText());
      return baseArchetype;
    }
    return resolved;
  }

  private void deriveEffector(
      MechanismEntity mechanism,
      UUID mechanismDefId,
      ArchetypeEntity effectorArchetype,
      ArchetypeEntity dataArchetype) {
    DefinitionEntity definition =
        findOrCreatePortDefinition(
            mechanismDefId,
            dataArchetype.getDefinition().getId(),
            effectorService.findAllByMechanismDefinitionId(mechanismDefId),
            e ->
                ((EffectorEntity) e)
                    .getOutputArchetype()
                    .getDefinition()
                    .getId()
                    .equals(dataArchetype.getDefinition().getId()),
            DefinitionSubjectType.EFFECTOR);

    ObjectNode statement = objectMapper.createObjectNode();
    statement.put("mechanism", mechanismDefId.toString());
    statement.put("archetype", dataArchetype.getDefinition().getId().toString());

    EffectorEntity effector =
        new EffectorEntity(definition, effectorArchetype, statement, mechanism, dataArchetype);
    EffectorEntity saved = effectorService.save(effector);
    stateMachine.recordTransition(saved, null, AscriptionStatusType.DRAFT);
    entityManager.refresh(saved);
    LOG.debug(
        "Auto-derived Effector {} for data archetype {}",
        saved.getId(),
        dataArchetype.getStatement().get("title").asText());
  }

  private void deriveReceptor(
      MechanismEntity mechanism,
      UUID mechanismDefId,
      ArchetypeEntity receptorArchetype,
      ArchetypeEntity dataArchetype) {
    DefinitionEntity definition =
        findOrCreatePortDefinition(
            mechanismDefId,
            dataArchetype.getDefinition().getId(),
            receptorService.findAllByMechanismDefinitionId(mechanismDefId),
            e ->
                ((ReceptorEntity) e)
                    .getInputArchetype()
                    .getDefinition()
                    .getId()
                    .equals(dataArchetype.getDefinition().getId()),
            DefinitionSubjectType.RECEPTOR);

    ObjectNode statement = objectMapper.createObjectNode();
    statement.put("mechanism", mechanismDefId.toString());
    statement.put("archetype", dataArchetype.getDefinition().getId().toString());

    ReceptorEntity receptor =
        new ReceptorEntity(definition, receptorArchetype, statement, mechanism, dataArchetype);
    ReceptorEntity saved = receptorService.save(receptor);
    stateMachine.recordTransition(saved, null, AscriptionStatusType.DRAFT);
    entityManager.refresh(saved);
    LOG.debug(
        "Auto-derived Receptor {} for data archetype {}",
        saved.getId(),
        dataArchetype.getStatement().get("title").asText());
  }

  private DefinitionEntity findOrCreatePortDefinition(
      UUID mechanismDefId,
      UUID dataArchetypeDefId,
      List<? extends AscriptionEntity> existingPorts,
      java.util.function.Predicate<AscriptionEntity> matcher,
      DefinitionSubjectType portType) {
    for (AscriptionEntity existing : existingPorts) {
      if (matcher.test(existing)) {
        return existing.getDefinition();
      }
    }
    return definitionService.create(portType);
  }
}
