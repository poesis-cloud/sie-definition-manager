package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.ArchetypeRepository;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.JsonNode;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GSM Norm ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link NormEntity} ascriptions including CEL
 * applicability/assertion profile validation (applicability and assertion profiles) and governing
 * cascade from owning Structure.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class NormService extends AbstractAscriptionService<NormEntity> {

  private static final Logger LOG = LoggerFactory.getLogger(NormService.class);

  // ======================================================================
  // CEL profile constants (from CelProfileValidator)
  // ======================================================================

  private static final Set<String> APPLICABILITY_COMPARISON_OPS =
      Set.of("_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_", "@in");
  private static final Set<String> APPLICABILITY_ALLOWED_FUNCTIONS = Set.of("matches");
  private static final Set<String> APPLICABILITY_ARITHMETIC_OPS =
      Set.of("_+_", "_-_", "_*_", "_%_", "_/_");
  private static final Set<String> ASSERTION_FORBIDDEN_FUNCTIONS = Set.of("now", "uuid");

  private static final List<AscriptionStatusType> IN_EFFECT =
      List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

  private final NormRepository normRepo;
  private final DirectiveService directiveService;
  private final StructureService structureService;
  private final ArchetypeService archetypeService;
  private final CelCompiler celParser;

  /**
   * Constructs the Norm service with its required dependencies.
   *
   * @param normRepo the norm repository
   * @param directiveService the directive service for governance chain checks
   * @param structureService the structure service for reference resolution
   * @param archetypeService the archetype service for qualifier resolution
   * @param definitionService the definition service
   * @param transitionService the status transition service
   * @param ascriptionService the ascription service for cross-subtype queries
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   */
  public NormService(
      NormRepository normRepo,
      DirectiveService directiveService,
      StructureService structureService,
      ArchetypeService archetypeService,
      ArchetypeRepository archetypeRepository,
      DefinitionService definitionService,
      AscriptionStatusTransitionService transitionService,
      AscriptionService ascriptionService,
      EntityManager entityManager,
      DataProtectionService dataProtectionService) {
    super(
        definitionService,
        transitionService,
        ascriptionService,
        archetypeRepository,
        entityManager,
        dataProtectionService);
    this.normRepo = normRepo;
    this.directiveService = directiveService;
    this.structureService = structureService;
    this.archetypeService = archetypeService;
    this.celParser = CelCompilerFactory.standardCelCompilerBuilder().build();
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.NORM;
  }

  @Override
  protected AbstractAscriptionRepository<NormEntity> getRepository() {
    return normRepo;
  }

  @Override
  public NormEntity buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    // GSM: validate CEL profiles before building entity
    if (statement.has("applicability")) {
      validateApplicability(statement.get("applicability").asText());
    }
    if (statement.has("assertion")) {
      validateAssertion(statement.get("assertion").asText());
    }

    UUID structureId = extractRequiredUuid(statement, "structure");
    StructureEntity structure = structureService.findEntityById(structureId);

    UUID qualifierId = extractRequiredUuid(statement, "qualifier");
    ArchetypeEntity qualifier = archetypeService.findEntityById(qualifierId);

    // Semantic validations (after references are resolved)
    if (statement.has("applicability")) {
      String applicability = statement.get("applicability").asText();
      if (applicability != null
          && !applicability.isBlank()
          && !"true".equals(applicability.trim())) {
        validateApplicabilityReferences(applicability);
      }
    }
    if (statement.has("assertion")) {
      validateAssertionPropertyPaths(statement.get("assertion").asText(), qualifier);
    }
    validateToleranceModeConsistency(statement);

    return new NormEntity(definition, archetypeRef, statement, structure, qualifier);
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
    var n = (NormEntity) entity;
    return List.of(
        new RefereeReference(n.getStructure(), "structure"),
        new RefereeReference(n.getQualifier(), "qualifier"));
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING);
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    if (sourceType == DefinitionSubjectType.STRUCTURE) {
      return normRepo.findAllByStructureId(sourceAscriptionId);
    }
    return List.of();
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    var n = (NormEntity) entity;
    var values = new LinkedHashMap<String, Object>();
    values.put("structure", n.getStructure().getDefinition().getId());
    values.put("qualifier", n.getQualifier().getDefinition().getId());
    var stmt = n.getStatement();
    if (stmt.has("assertion")) {
      values.put("assertion", stmt.get("assertion").asText());
    }
    return values;
  }

  @Override
  public void validateActivationUniqueness(AscriptionEntity entity) {
    var norm = (NormEntity) entity;
    validateGovernanceChain(norm);
    validateNormConflict(norm);
  }

  // ======================================================================
  // NORM_GOVERNANCE_CHAIN: Directive backing validation
  // ======================================================================

  private void validateGovernanceChain(NormEntity norm) {
    UUID structureDefId = norm.getStructure().getDefinition().getId();
    UUID qualifierId = norm.getQualifier().getId();

    List<DirectiveEntity> directives =
        directiveService.findAllByPurposeDefinitionIdAndStatusIn(structureDefId, IN_EFFECT);

    if (directives.isEmpty()) {
      throw RuleViolationException.of(
          RuleType.NORM_GOVERNANCE_CHAIN,
          "No in-effect Directive targets structure (definition "
              + structureDefId
              + ") as purpose — Norm has no governance authority",
          "structureDefinitionId",
          structureDefId);
    }

    Set<String> normAncestors = archetypeService.getAncestorTitles(qualifierId);

    boolean hasLegitimatingDirective =
        directives.stream()
            .anyMatch(
                d -> {
                  Set<String> directiveAncestors =
                      archetypeService.getAncestorTitles(d.getQualifier().getId());
                  // Norm qualifier must be same or descendant of Directive qualifier
                  // i.e. any Directive ancestor must appear in Norm ancestors
                  for (String da : directiveAncestors) {
                    if (normAncestors.contains(da)) {
                      return true;
                    }
                  }
                  return false;
                });

    if (!hasLegitimatingDirective) {
      throw RuleViolationException.of(
          RuleType.NORM_GOVERNANCE_CHAIN,
          "No in-effect Directive with purpose (definition "
              + structureDefId
              + ") has a qualifier that is ancestor-or-equal of Norm qualifier — "
              + "Norm qualifier lineage "
              + normAncestors
              + " has no overlap with any Directive qualifier lineage",
          "structureDefinitionId",
          structureDefId,
          "normQualifierAncestors",
          normAncestors.toString());
    }
  }

  // ======================================================================
  // NORM_CONFLICT: Overlapping Norm detection
  // ======================================================================

  private void validateNormConflict(NormEntity norm) {
    UUID structureDefId = norm.getStructure().getDefinition().getId();
    UUID thisDefId = norm.getDefinition().getId();
    UUID qualifierId = norm.getQualifier().getId();

    Set<String> normAncestors = archetypeService.getAncestorTitles(qualifierId);
    String normAssertion =
        norm.getStatement().has("assertion") ? norm.getStatement().get("assertion").asText() : "";

    List<NormEntity> siblings =
        normRepo.findAllByStructureDefinitionIdAndStatusIn(structureDefId, IN_EFFECT);

    for (NormEntity sibling : siblings) {
      if (sibling.getDefinition().getId().equals(thisDefId)) {
        continue;
      }

      // Check qualifier overlap: sibling's qualifier lineage must intersect norm's
      // lineage
      Set<String> siblingAncestors =
          archetypeService.getAncestorTitles(sibling.getQualifier().getId());
      boolean qualifierOverlap = false;
      for (String sa : siblingAncestors) {
        if (normAncestors.contains(sa)) {
          qualifierOverlap = true;
          break;
        }
      }
      if (!qualifierOverlap) {
        continue;
      }

      // Same assertion text on overlapping qualifier = direct conflict
      String siblingAssertion =
          sibling.getStatement().has("assertion")
              ? sibling.getStatement().get("assertion").asText()
              : "";
      if (normAssertion.equals(siblingAssertion)) {
        // Same assertion is a duplicate, not a conflict — skip
        continue;
      }

      // Check assertion property overlap: extract bare property identifiers
      Set<String> normProps = extractAssertionProperties(normAssertion);
      Set<String> siblingProps = extractAssertionProperties(siblingAssertion);
      Set<String> commonProps = new HashSet<>(normProps);
      commonProps.retainAll(siblingProps);

      if (!commonProps.isEmpty()) {
        LOG.warn(
            "[{}] Potential Norm conflict: Norm (definition {}) and sibling {} "
                + "(definition {}) target overlapping qualifier lineage and "
                + "assert on common properties {}",
            RuleType.NORM_CONFLICT.getType(),
            thisDefId,
            sibling.getId(),
            sibling.getDefinition().getId(),
            commonProps);
      }
    }
  }

  private Set<String> extractAssertionProperties(String assertion) {
    if (assertion == null || assertion.isBlank()) {
      return Set.of();
    }
    CelValidationResult result = celParser.parse(assertion);
    if (result.hasError()) {
      return Set.of();
    }
    try {
      CelExpr ast = result.getAst().getExpr();
      Set<String> paths = new LinkedHashSet<>();
      collectPropertyIdents(ast, paths, new HashSet<>());
      return paths;
    } catch (CelValidationException e) {
      return Set.of();
    }
  }

  // ======================================================================
  // CEL Applicability profile validation (inlined from CelProfileValidator)
  // ======================================================================

  /** Package-private for test access. */
  void validateApplicability(String applicability) {
    if (applicability == null || applicability.isBlank() || "true".equals(applicability.trim())) {
      return;
    }
    CelExpr ast = parseApplicabilityCel(celParser, applicability);
    Set<String> axes = new HashSet<>();
    validateApplicabilityExpr(ast, axes, true);
  }

  /** Package-private for test access. */
  void validateAssertion(String assertion) {
    if (assertion == null || assertion.isBlank()) {
      throw RuleViolationException.of(
          RuleType.ASCRIPTION_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Assertion must not be empty",
          "field",
          "assertion");
    }
    CelExpr ast = parseAssertionCel(celParser, assertion);
    validateAssertionExpr(ast);
    // NORM_ASSERTION_AS_BOOLEAN_RESULT: top-level must be boolean-producing
    validateAssertionBooleanResult(ast);
  }

  private static CelExpr parseApplicabilityCel(CelCompiler compiler, String expression) {
    CelValidationResult result = compiler.parse(expression);
    if (result.hasError()) {
      throw RuleViolationException.of(
          RuleType.NORM_APPLICABILITY_CEL_PARSING,
          "Applicability CEL parse error: " + result.getErrorString(),
          "field",
          "applicability");
    }
    try {
      return result.getAst().getExpr();
    } catch (CelValidationException e) {
      throw RuleViolationException.of(
          RuleType.NORM_APPLICABILITY_CEL_PARSING,
          "Applicability CEL validation error: " + e.getMessage(),
          e,
          "field",
          "applicability");
    }
  }

  private static CelExpr parseAssertionCel(CelCompiler compiler, String expression) {
    CelValidationResult result = compiler.parse(expression);
    if (result.hasError()) {
      throw RuleViolationException.of(
          RuleType.NORM_ASSERTION_CEL_PARSING,
          "Assertion CEL parse error: " + result.getErrorString(),
          "field",
          "assertion");
    }
    try {
      return result.getAst().getExpr();
    } catch (CelValidationException e) {
      throw RuleViolationException.of(
          RuleType.NORM_ASSERTION_CEL_PARSING,
          "Assertion CEL validation error: " + e.getMessage(),
          e,
          "field",
          "assertion");
    }
  }

  private void validateApplicabilityExpr(CelExpr expr, Set<String> axes, boolean topLevel) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if ("_&&_".equals(fn)) {
          for (CelExpr arg : call.args()) {
            validateApplicabilityExpr(arg, axes, true);
          }
          return;
        }
        if ("_||_".equals(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: '||' (OR) is forbidden. "
                  + "The applicability expression must be a pure conjunction. "
                  + "Use 'in [...]' for set membership instead of OR.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        if ("_?_:_".equals(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: ternary operator (?:) is forbidden in applicability expressions.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        if (APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: arithmetic operators are forbidden in guard expressions.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        if ("!_".equals(fn) || "_!_".equals(fn)) {
          for (CelExpr arg : call.args()) {
            validateApplicabilityExpr(arg, axes, topLevel);
          }
          return;
        }
        if (APPLICABILITY_COMPARISON_OPS.contains(fn)) {
          if ("@in".equals(fn) && call.args().size() == 2) {
            validateInListConsistency(call.args().get(1));
          }
          if (topLevel) {
            validateSingleAxisPredicate(call, axes);
          }
          return;
        }
        if (call.target().isPresent()) {
          if (!APPLICABILITY_ALLOWED_FUNCTIONS.contains(fn)) {
            throw RuleViolationException.of(
                RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                "Applicability profile violation: only .matches() is allowed as a function call. "
                    + "Found: ."
                    + fn
                    + "()",
                "field",
                "applicability",
                "construct",
                fn);
          }
          if (topLevel) {
            String axis = extractAxis(call.target().get());
            if (axis != null && !axes.add(axis)) {
              throw RuleViolationException.of(
                  RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
                  "Applicability profile violation: duplicate axis '"
                      + axis
                      + "'. At most one applicability predicate per (Archetype, propertyPath).",
                  "field",
                  "applicability",
                  "axis",
                  axis);
            }
          }
          return;
        }
        throw RuleViolationException.of(
            RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            "Applicability profile violation: function call '"
                + fn
                + "' is forbidden. Only comparison operators and .matches() are allowed.",
            "field",
            "applicability",
            "construct",
            fn);
      }
      case SELECT, IDENT, CONSTANT, LIST -> {
        /* leaf nodes */
      }
      default -> {
        /* comprehension, map, struct */
      }
    }
  }

  private void validateSingleAxisPredicate(CelExpr.CelCall call, Set<String> axes) {
    for (CelExpr arg : call.args()) {
      rejectForbiddenInApplicabilityOperand(arg);
    }
    Set<String> predAxes = new HashSet<>();
    for (CelExpr arg : call.args()) {
      collectAxes(arg, predAxes);
    }
    if (predAxes.size() > 1) {
      throw RuleViolationException.of(
          RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
          "Applicability profile violation: cross-property comparison detected. "
              + "Each applicability predicate must compare a single property to a literal. "
              + "Found axes: "
              + predAxes,
          "field",
          "applicability",
          "axes",
          predAxes.toString());
    }
    for (String axis : predAxes) {
      if (!axes.add(axis)) {
        throw RuleViolationException.of(
            RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            "Applicability profile violation: duplicate axis '"
                + axis
                + "'. At most one applicability predicate per (Archetype, propertyPath).",
            "field",
            "applicability",
            "axis",
            axis);
      }
    }
  }

  private static void rejectForbiddenInApplicabilityOperand(CelExpr expr) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if (APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
              "Applicability profile violation: arithmetic operators are forbidden in guard expressions.",
              "field",
              "applicability",
              "construct",
              fn);
        }
        throw RuleViolationException.of(
            RuleType.NORM_APPLICABILITY_AXIS_PREDICATE_NORMAL_FORM,
            "Applicability profile violation: function call '"
                + fn
                + "' is forbidden in applicability comparison operands. "
                + "Only property references and literals are allowed. "
                + "(Only .matches() is allowed as a standalone predicate.)",
            "field",
            "applicability",
            "construct",
            fn);
      }
      case SELECT -> rejectForbiddenInApplicabilityOperand(kind.select().operand());
      case IDENT, CONSTANT, LIST -> {
        /* valid */
      }
      default -> {
        /* not expected */
      }
    }
  }

  private static void collectAxes(CelExpr expr, Set<String> axes) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case SELECT -> {
        String axis = extractAxis(expr);
        if (axis != null) axes.add(axis);
      }
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        call.target().ifPresent(t -> collectAxes(t, axes));
        for (CelExpr arg : call.args()) {
          collectAxes(arg, axes);
        }
      }
      default -> {
        /* IDENT, CONSTANT, LIST — no axis */
      }
    }
  }

  private static String extractAxis(CelExpr expr) {
    if (expr.exprKind().getKind() != CelExpr.ExprKind.Kind.SELECT) return null;
    CelExpr.CelSelect sel = expr.exprKind().select();
    String field = sel.field();
    CelExpr operand = sel.operand();
    if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      return operand.exprKind().ident().name() + "." + field;
    }
    CelExpr root = operand;
    while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
      root = root.exprKind().select().operand();
    }
    if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
      CelExpr firstSelect = operand;
      while (firstSelect.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT
          && firstSelect.exprKind().select().operand().exprKind().getKind()
              != CelExpr.ExprKind.Kind.IDENT) {
        firstSelect = firstSelect.exprKind().select().operand();
      }
      if (firstSelect.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
        return root.exprKind().ident().name() + "." + firstSelect.exprKind().select().field();
      }
    }
    return null;
  }

  // ======================================================================
  // CEL Assertion profile validation
  // ======================================================================

  private void validateAssertionExpr(CelExpr expr) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if (ASSERTION_FORBIDDEN_FUNCTIONS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_AS_DETERMINISTIC_EXPRESSION,
              "Assertion profile violation: non-deterministic functions (now(), uuid()) are forbidden. "
                  + "Assertion must be deterministic and side-effect-free.",
              "field",
              "assertion",
              "construct",
              fn);
        }
        call.target().ifPresent(this::validateAssertionExpr);
        for (CelExpr arg : call.args()) {
          validateAssertionExpr(arg);
        }
      }
      case SELECT -> {
        CelExpr.CelSelect sel = kind.select();
        CelExpr operand = sel.operand();
        CelExpr root = operand;
        while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
          root = root.exprKind().select().operand();
        }
        if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
          String rootName = root.exprKind().ident().name();
          if ("self".equals(rootName)) {
            throw RuleViolationException.of(
                RuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
                "Assertion profile violation: use bare qualifier property names, "
                    + "not 'self.' prefix. "
                    + "Example: 'encryptionLevel' instead of 'self."
                    + sel.field()
                    + "'.",
                "field",
                "assertion",
                "rootName",
                rootName);
          }
          if (Character.isUpperCase(rootName.charAt(0))) {
            throw RuleViolationException.of(
                RuleType.NORM_ASSERTION_AS_ARCHETYPE_BOUND_EXPRESSION,
                "Assertion profile violation: use bare qualifier property names, "
                    + "not an explicit Archetype name. "
                    + "Example: 'encryptionLevel' instead of '"
                    + rootName
                    + "."
                    + sel.field()
                    + "'.",
                "field",
                "assertion",
                "rootName",
                rootName);
          }
        }
        validateAssertionExpr(operand);
      }
      case IDENT, CONSTANT -> {
        /* OK — bare identifiers are qualifier properties */
      }
      case LIST -> {
        CelExpr.CelList list = kind.list();
        for (CelExpr el : list.elements()) {
          validateAssertionExpr(el);
        }
      }
      default -> {
        /* comprehension, map, struct — allowed */
      }
    }
  }

  // ======================================================================
  // NORM_APPLICABILITY_COMPARISON_CONSISTENCY: in-list element validation
  // ======================================================================

  private static void validateInListConsistency(CelExpr listExpr) {
    if (listExpr.exprKind().getKind() != CelExpr.ExprKind.Kind.LIST) {
      return;
    }
    CelExpr.CelList list = listExpr.exprKind().list();
    List<CelExpr> elements = list.elements();
    if (elements.size() < 2) {
      throw RuleViolationException.of(
          RuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
          "Applicability 'in' list must have >= 2 elements (single value → use '=='). Found: "
              + elements.size(),
          "elementCount",
          elements.size());
    }
    Set<String> seen = new LinkedHashSet<>();
    CelConstant.Kind firstKind = null;
    for (CelExpr el : elements) {
      if (el.exprKind().getKind() != CelExpr.ExprKind.Kind.CONSTANT) {
        continue; // non-literal elements rejected by applicability profile elsewhere
      }
      CelConstant c = el.exprKind().constant();
      CelConstant.Kind kind = c.getKind();
      if (firstKind == null) {
        firstKind = kind;
      } else if (kind != firstKind) {
        throw RuleViolationException.of(
            RuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
            "Applicability 'in' list elements must be type-homogeneous. "
                + "Mixed types: "
                + firstKind
                + " and "
                + kind,
            "firstKind",
            firstKind.name(),
            "conflictingKind",
            kind.name());
      }
      String repr = constantToString(c);
      if (!seen.add(repr)) {
        throw RuleViolationException.of(
            RuleType.NORM_APPLICABILITY_COMPARISON_CONSISTENCY,
            "Applicability 'in' list elements must be unique. Duplicate: " + repr,
            "duplicate",
            repr);
      }
    }
  }

  private static String constantToString(CelConstant c) {
    return switch (c.getKind()) {
      case STRING_VALUE -> c.stringValue();
      case INT64_VALUE -> String.valueOf(c.int64Value());
      case UINT64_VALUE -> String.valueOf(c.uint64Value());
      case DOUBLE_VALUE -> String.valueOf(c.doubleValue());
      case BOOLEAN_VALUE -> String.valueOf(c.booleanValue());
      default -> c.toString();
    };
  }

  // ======================================================================
  // NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION + PROPERTY_PATH_RESOLUTION
  // ======================================================================

  /** Package-private for test access. */
  void validateApplicabilityReferences(String applicability) {
    CelExpr ast = parseApplicabilityCel(celParser, applicability);
    Set<String> axes = new LinkedHashSet<>();
    collectAxes(ast, axes);
    for (String axis : axes) {
      int dot = axis.indexOf('.');
      if (dot <= 0) continue;
      String archetypeName = axis.substring(0, dot);
      String propertyPath = axis.substring(dot + 1);
      // NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION
      Optional<ArchetypeEntity> archetype = archetypeService.findInEffectByTitle(archetypeName);
      if (archetype.isEmpty()) {
        throw RuleViolationException.of(
            RuleType.NORM_APPLICABILITY_ARCHETYPE_REFERENCE_RESOLUTION,
            "Applicability references Archetype '"
                + archetypeName
                + "' which does not exist as an active Archetype",
            "archetypeName",
            archetypeName,
            "axis",
            axis);
      }
      // NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION
      JsonNode schema = archetype.get().getStatement();
      if (!resolveSchemaProperty(schema, propertyPath)) {
        throw RuleViolationException.of(
            RuleType.NORM_APPLICABILITY_PROPERTY_PATH_RESOLUTION,
            "Applicability references property '"
                + propertyPath
                + "' which does not exist in Archetype '"
                + archetypeName
                + "' schema",
            "archetypeName",
            archetypeName,
            "propertyPath",
            propertyPath,
            "axis",
            axis);
      }
    }
  }

  // ======================================================================
  // NORM_ASSERTION_AS_BOOLEAN_RESULT
  // ======================================================================

  private static final Set<String> BOOLEAN_PRODUCING_OPS =
      Set.of(
          "_==_",
          "_!=_",
          "_<_",
          "_<=_",
          "_>_",
          "_>=_",
          "_&&_",
          "_||_",
          "!_",
          "_!_",
          "@in",
          "matches",
          "startsWith",
          "endsWith",
          "contains",
          "has",
          "exists",
          "all",
          "exists_one");

  private static void validateAssertionBooleanResult(CelExpr ast) {
    CelExpr.ExprKind kind = ast.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        String fn = kind.call().function();
        if ("_?_:_".equals(fn)) {
          return; // ternary: result type depends on branches — accept
        }
        if (!BOOLEAN_PRODUCING_OPS.contains(fn) && !APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          return; // unknown function — accept (DYN)
        }
        if (APPLICABILITY_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT,
              "Assertion top-level expression is arithmetic ('" + fn + "') — must evaluate to bool",
              "function",
              fn);
        }
        // known boolean-producing op: OK
      }
      case CONSTANT -> {
        CelConstant c = kind.constant();
        if (c.getKind() != CelConstant.Kind.BOOLEAN_VALUE) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_AS_BOOLEAN_RESULT,
              "Assertion top-level expression is a non-boolean constant ("
                  + c.getKind()
                  + ") — must evaluate to bool",
              "constantKind",
              c.getKind().name());
        }
      }
      case IDENT, SELECT -> {
        // DYN — could be bool at runtime; accept
      }
      default -> {
        // LIST, comprehension, etc. — accept (rare/unusual but not provably wrong)
      }
    }
  }

  // ======================================================================
  // NORM_ASSERTION_PROPERTY_PATH_RESOLUTION
  // ======================================================================

  /** Package-private for test access. */
  void validateAssertionPropertyPaths(String assertion, ArchetypeEntity qualifier) {
    CelExpr ast = parseAssertionCel(celParser, assertion);
    Set<String> paths = new LinkedHashSet<>();
    collectPropertyIdents(ast, paths, new HashSet<>());
    JsonNode schema = qualifier.getStatement();
    for (String path : paths) {
      if (!resolveSchemaProperty(schema, path)) {
        String title = schema.has("title") ? schema.get("title").asText() : "(unknown)";
        throw RuleViolationException.of(
            RuleType.NORM_ASSERTION_PROPERTY_PATH_RESOLUTION,
            "Assertion references '"
                + path
                + "' which does not exist in qualifier Archetype '"
                + title
                + "' schema",
            "qualifierTitle",
            title,
            "propertyPath",
            path);
      }
    }
  }

  private static void collectPropertyIdents(
      CelExpr expr, Set<String> paths, Set<String> boundVars) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case IDENT -> {
        String name = kind.ident().name();
        if (!boundVars.contains(name)) {
          paths.add(name);
        }
      }
      case SELECT -> {
        CelExpr root = expr;
        while (root.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT) {
          root = root.exprKind().select().operand();
        }
        if (root.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT) {
          String rootName = root.exprKind().ident().name();
          if (!boundVars.contains(rootName)) {
            paths.add(rootName);
          }
        }
      }
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        call.target().ifPresent(t -> collectPropertyIdents(t, paths, boundVars));
        for (CelExpr arg : call.args()) {
          collectPropertyIdents(arg, paths, boundVars);
        }
      }
      case LIST -> {
        for (CelExpr el : kind.list().elements()) {
          collectPropertyIdents(el, paths, boundVars);
        }
      }
      case COMPREHENSION -> {
        CelExpr.CelComprehension comp = kind.comprehension();
        collectPropertyIdents(comp.iterRange(), paths, boundVars);
        Set<String> innerBound = new HashSet<>(boundVars);
        innerBound.add(comp.iterVar());
        collectPropertyIdents(comp.accuInit(), paths, innerBound);
        collectPropertyIdents(comp.loopCondition(), paths, innerBound);
        collectPropertyIdents(comp.loopStep(), paths, innerBound);
        collectPropertyIdents(comp.result(), paths, innerBound);
      }
      default -> {
        /* CONSTANT, MAP, STRUCT */
      }
    }
  }

  // ======================================================================
  // NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY
  // ======================================================================

  /** Package-private for test access. */
  static void validateToleranceModeConsistency(JsonNode statement) {
    if (!statement.has("toleranceMode")) return;
    String mode = statement.get("toleranceMode").asText();
    boolean hasWindow =
        statement.has("temporalWindow") && !statement.get("temporalWindow").isNull();
    boolean hasAggregation =
        statement.has("temporalAggregation") && !statement.get("temporalAggregation").isNull();
    boolean hasThreshold =
        statement.has("sustainedThreshold") && !statement.get("sustainedThreshold").isNull();

    switch (mode) {
      case "INSTANTANEOUS" -> {
        if (hasWindow || hasAggregation || hasThreshold) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY,
              "INSTANTANEOUS mode forbids temporalWindow, temporalAggregation,"
                  + " and sustainedThreshold",
              "toleranceMode",
              mode,
              "hasTemporalWindow",
              hasWindow,
              "hasTemporalAggregation",
              hasAggregation,
              "hasSustainedThreshold",
              hasThreshold);
        }
      }
      case "AGGREGATED" -> {
        if (!hasWindow || !hasAggregation) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY,
              "AGGREGATED mode requires temporalWindow and temporalAggregation",
              "toleranceMode",
              mode,
              "hasTemporalWindow",
              hasWindow,
              "hasTemporalAggregation",
              hasAggregation);
        }
        if (hasThreshold) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY,
              "AGGREGATED mode forbids sustainedThreshold",
              "toleranceMode",
              mode);
        }
      }
      case "SUSTAINED" -> {
        if (!hasWindow || !hasAggregation || !hasThreshold) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY,
              "SUSTAINED mode requires temporalWindow, temporalAggregation,"
                  + " and sustainedThreshold",
              "toleranceMode",
              mode,
              "hasTemporalWindow",
              hasWindow,
              "hasTemporalAggregation",
              hasAggregation,
              "hasSustainedThreshold",
              hasThreshold);
        }
        double threshold = statement.get("sustainedThreshold").asDouble();
        if (threshold < 0.0 || threshold > 1.0) {
          throw RuleViolationException.of(
              RuleType.NORM_ASSERTION_TOLERANCE_MODE_CONSISTENCY,
              "sustainedThreshold must be in [0, 1]. Found: " + threshold,
              "toleranceMode",
              mode,
              "sustainedThreshold",
              threshold);
        }
      }
      default -> {
        /* unknown mode — schema validation catches this */
      }
    }
  }

  // ======================================================================
  // Schema property resolution helper
  // ======================================================================

  private static boolean resolveSchemaProperty(JsonNode schema, String propertyPath) {
    String[] parts = propertyPath.split("\\.");
    JsonNode current = schema;
    for (String part : parts) {
      JsonNode props = current.get("properties");
      if (props == null || !props.has(part)) return false;
      current = props.get(part);
    }
    return true;
  }
}
