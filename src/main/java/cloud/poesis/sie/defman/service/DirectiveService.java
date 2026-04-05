package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.DirectiveRepository;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * GSM Directive ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link DirectiveEntity} ascriptions including Directive
 * consistency validation (verb/modal contradiction detection) and governing cascade from owning
 * Structure.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class DirectiveService implements AscriptionSubtypeService<DirectiveEntity> {

  private final DirectiveRepository directiveRepo;
  private final StructureService structureService;
  private final ArchetypeService archetypeService;

  public DirectiveService(
      DirectiveRepository directiveRepo,
      StructureService structureService,
      ArchetypeService archetypeService) {
    this.directiveRepo = directiveRepo;
    this.structureService = structureService;
    this.archetypeService = archetypeService;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.DIRECTIVE;
  }

  @Override
  public AbstractAscriptionRepository<DirectiveEntity> getRepository() {
    return directiveRepo;
  }

  @Override
  public DirectiveEntity create(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    UUID structureId =
        AscriptionParsingService.extractRequiredUuid(
            statement,
            "structure",
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE);
    StructureEntity structure = structureService.findEntityById(structureId);

    UUID qualifierId =
        AscriptionParsingService.extractRequiredUuid(
            statement,
            "qualifier",
            AscriptionConsistencyRuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE);
    ArchetypeEntity qualifier = archetypeService.findEntityById(qualifierId);

    return new DirectiveEntity(definition, archetypeRef, statement, structure, qualifier);
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<Map.Entry<AscriptionEntity, String>> getRefereeReferences(AscriptionEntity entity) {
    var d = (DirectiveEntity) entity;
    return List.of(
        Map.entry(d.getStructure(), "structure"), Map.entry(d.getQualifier(), "qualifier"));
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING);
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    if (sourceType == DefinitionSubjectType.STRUCTURE) {
      return directiveRepo.findAllByStructureId(sourceAscriptionId);
    }
    return List.of();
  }

  /**
   * Returns in-effect directives whose statement purpose matches the given string.
   *
   * @param purpose the governed purpose string
   * @return the matching directive entities
   */
  public List<DirectiveEntity> findAllInEffectByPurpose(String purpose) {
    return directiveRepo.findAllInEffectByPurpose(purpose);
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    var d = (DirectiveEntity) entity;
    var stmt = d.getStatement();
    var purpose = stmt.has("purpose") ? stmt.get("purpose").asText() : null;
    return purpose != null
        ? Map.of(
            "structure", d.getStructure().getDefinition().getId(),
            "qualifier", d.getQualifier().getDefinition().getId(),
            "purpose", purpose)
        : Map.of(
            "structure", d.getStructure().getDefinition().getId(),
            "qualifier", d.getQualifier().getDefinition().getId());
  }
}
