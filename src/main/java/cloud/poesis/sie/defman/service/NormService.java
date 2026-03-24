package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.JsonNode;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.SimpleType;
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
import org.springframework.stereotype.Service;

/**
 * GSM Norm ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link NormEntity} ascriptions including CEL
 * guard/predicate profile validation (applicability-guard and property-assertion profiles) and
 * governing cascade from owning Structure.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class NormService extends AbstractAscriptionService<NormEntity> {

  // ======================================================================
  // CEL profile constants (from CelProfileValidator)
  // ======================================================================

  private static final Set<String> GUARD_COMPARISON_OPS =
      Set.of("_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_", "@in");
  private static final Set<String> GUARD_ALLOWED_FUNCTIONS = Set.of("matches");
  private static final Set<String> GUARD_ARITHMETIC_OPS = Set.of("_+_", "_-_", "_*_", "_%_", "_/_");
  private static final Set<String> PREDICATE_FORBIDDEN_FUNCTIONS = Set.of("now", "uuid");

  private final NormRepository normRepo;
  private final StructureService structureService;
  private final ArchetypeService archetypeService;
  private final CelCompiler guardCompiler;
  private final CelCompiler predicateCompiler;

  /**
   * Constructs the Norm service with its required dependencies.
   *
   * @param normRepo the norm repository
   * @param structureService the structure service for reference resolution
   * @param archetypeService the archetype service for qualifier resolution
   * @param definitionService the definition service
   * @param transitionService the status transition service
   * @param ascriptionRepository the base ascription repository
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   */
  public NormService(
      NormRepository normRepo,
      StructureService structureService,
      ArchetypeService archetypeService,
      DefinitionService definitionService,
      AscriptionStatusTransitionService transitionService,
      AscriptionRepository ascriptionRepository,
      EntityManager entityManager,
      DataProtectionService dataProtectionService) {
    super(
        definitionService,
        transitionService,
        ascriptionRepository,
        entityManager,
        dataProtectionService);
    this.normRepo = normRepo;
    this.structureService = structureService;
    this.archetypeService = archetypeService;
    this.guardCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("self", SimpleType.DYN).build();
    this.predicateCompiler =
        CelCompilerFactory.standardCelCompilerBuilder().addVar("self", SimpleType.DYN).build();
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
    if (statement.has("guard")) {
      validateGuard(statement.get("guard").asText());
    }
    if (statement.has("predicate")) {
      validatePredicate(statement.get("predicate").asText());
    }

    UUID structureId = extractRequiredUuid(statement, "structure");
    StructureEntity structure = structureService.findEntityById(structureId);

    UUID qualifierId = extractRequiredUuid(statement, "qualifier");
    ArchetypeEntity qualifier = archetypeService.findEntityById(qualifierId);

    // Semantic validations (after references are resolved)
    if (statement.has("guard")) {
      String guard = statement.get("guard").asText();
      if (guard != null && !guard.isBlank() && !"true".equals(guard.trim())) {
        validateGuardReferences(guard);
      }
    }
    if (statement.has("predicate")) {
      validatePredicatePropertyPaths(statement.get("predicate").asText(), qualifier);
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
    if (stmt.has("predicate")) {
      values.put("predicate", stmt.get("predicate").asText());
    }
    return values;
  }

  // ======================================================================
  // CEL Guard profile validation (inlined from CelProfileValidator)
  // ======================================================================

  /** Package-private for test access. */
  void validateGuard(String guard) {
    if (guard == null || guard.isBlank() || "true".equals(guard.trim())) {
      return;
    }
    CelExpr ast = parseGuardCel(guardCompiler, guard);
    Set<String> axes = new HashSet<>();
    validateGuardExpr(ast, axes, true);
  }

  /** Package-private for test access. */
  void validatePredicate(String predicate) {
    if (predicate == null || predicate.isBlank()) {
      throw RuleViolationException.of(
          RuleType.NORM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Predicate must not be empty",
          "field",
          "predicate");
    }
    CelExpr ast = parsePredicateCel(predicateCompiler, predicate);
    validatePredicateExpr(ast);
    // NORM_PREDICATE_AS_BOOLEAN_RESULT: top-level must be boolean-producing
    validatePredicateBooleanResult(ast);
  }

  private static CelExpr parseGuardCel(CelCompiler compiler, String expression) {
    CelValidationResult result = compiler.parse(expression);
    if (result.hasError()) {
      throw RuleViolationException.of(
          RuleType.NORM_GUARD_CEL_PARSING,
          "Guard CEL parse error: " + result.getErrorString(),
          "field",
          "guard");
    }
    try {
      return result.getAst().getExpr();
    } catch (CelValidationException e) {
      throw RuleViolationException.of(
          RuleType.NORM_GUARD_CEL_PARSING,
          "Guard CEL validation error: " + e.getMessage(),
          e,
          "field",
          "guard");
    }
  }

  private static CelExpr parsePredicateCel(CelCompiler compiler, String expression) {
    CelValidationResult result = compiler.parse(expression);
    if (result.hasError()) {
      throw RuleViolationException.of(
          RuleType.NORM_PREDICATE_CEL_PARSING,
          "Predicate CEL parse error: " + result.getErrorString(),
          "field",
          "predicate");
    }
    try {
      return result.getAst().getExpr();
    } catch (CelValidationException e) {
      throw RuleViolationException.of(
          RuleType.NORM_PREDICATE_CEL_PARSING,
          "Predicate CEL validation error: " + e.getMessage(),
          e,
          "field",
          "predicate");
    }
  }

  private void validateGuardExpr(CelExpr expr, Set<String> axes, boolean topLevel) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if ("_&&_".equals(fn)) {
          for (CelExpr arg : call.args()) {
            validateGuardExpr(arg, axes, true);
          }
          return;
        }
        if ("_||_".equals(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
              "Guard profile violation: '||' (OR) is forbidden. "
                  + "The guard must be a pure conjunction. "
                  + "Use 'in [...]' for set membership instead of OR.",
              "field",
              "guard",
              "construct",
              fn);
        }
        if ("_?_:_".equals(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
              "Guard profile violation: ternary operator (?:) is forbidden in guard expressions.",
              "field",
              "guard",
              "construct",
              fn);
        }
        if (GUARD_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
              "Guard profile violation: arithmetic operators are forbidden in guard expressions.",
              "field",
              "guard",
              "construct",
              fn);
        }
        if ("!_".equals(fn) || "_!_".equals(fn)) {
          for (CelExpr arg : call.args()) {
            validateGuardExpr(arg, axes, topLevel);
          }
          return;
        }
        if (GUARD_COMPARISON_OPS.contains(fn)) {
          if ("@in".equals(fn) && call.args().size() == 2) {
            validateInListConsistency(call.args().get(1));
          }
          if (topLevel) {
            validateSingleAxisPredicate(call, axes);
          }
          return;
        }
        if (call.target().isPresent()) {
          if (!GUARD_ALLOWED_FUNCTIONS.contains(fn)) {
            throw RuleViolationException.of(
                RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
                "Guard profile violation: only .matches() is allowed as a function call. "
                    + "Found: ."
                    + fn
                    + "()",
                "field",
                "guard",
                "construct",
                fn);
          }
          if (topLevel) {
            String axis = extractAxis(call.target().get());
            if (axis != null && !axes.add(axis)) {
              throw RuleViolationException.of(
                  RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
                  "Guard profile violation: duplicate axis '"
                      + axis
                      + "'. At most one predicate per (Archetype, propertyPath).",
                  "field",
                  "guard",
                  "axis",
                  axis);
            }
          }
          return;
        }
        throw RuleViolationException.of(
            RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
            "Guard profile violation: function call '"
                + fn
                + "' is forbidden. Only comparison operators and .matches() are allowed.",
            "field",
            "guard",
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
      rejectForbiddenInGuardOperand(arg);
    }
    Set<String> predAxes = new HashSet<>();
    for (CelExpr arg : call.args()) {
      collectAxes(arg, predAxes);
    }
    if (predAxes.size() > 1) {
      throw RuleViolationException.of(
          RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
          "Guard profile violation: cross-property comparison detected. "
              + "Each predicate must compare a single property to a literal. "
              + "Found axes: "
              + predAxes,
          "field",
          "guard",
          "axes",
          predAxes.toString());
    }
    for (String axis : predAxes) {
      if (!axes.add(axis)) {
        throw RuleViolationException.of(
            RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
            "Guard profile violation: duplicate axis '"
                + axis
                + "'. At most one predicate per (Archetype, propertyPath).",
            "field",
            "guard",
            "axis",
            axis);
      }
    }
  }

  private static void rejectForbiddenInGuardOperand(CelExpr expr) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if (GUARD_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
              "Guard profile violation: arithmetic operators are forbidden in guard expressions.",
              "field",
              "guard",
              "construct",
              fn);
        }
        throw RuleViolationException.of(
            RuleType.NORM_GUARD_AXIS_PREDICATE_NORMAL_FORM,
            "Guard profile violation: function call '"
                + fn
                + "' is forbidden in guard comparison operands. "
                + "Only property references and literals are allowed. "
                + "(Only .matches() is allowed as a standalone predicate.)",
            "field",
            "guard",
            "construct",
            fn);
      }
      case SELECT -> rejectForbiddenInGuardOperand(kind.select().operand());
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
  // CEL Predicate profile validation
  // ======================================================================

  private void validatePredicateExpr(CelExpr expr) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        String fn = call.function();
        if (PREDICATE_FORBIDDEN_FUNCTIONS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_PREDICATE_AS_DETERMINISTIC_EXPRESSION,
              "Predicate profile violation: non-deterministic functions (now(), uuid()) are forbidden. "
                  + "Predicate must be deterministic and side-effect-free.",
              "field",
              "predicate",
              "construct",
              fn);
        }
        call.target().ifPresent(this::validatePredicateExpr);
        for (CelExpr arg : call.args()) {
          validatePredicateExpr(arg);
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
          if (!rootName.equals("self") && Character.isUpperCase(rootName.charAt(0))) {
            throw RuleViolationException.of(
                RuleType.NORM_PREDICATE_AS_ARCHETYPE_BOUND_EXPRESSION,
                "Predicate profile violation: use 'self' as implicit root, "
                    + "not an explicit Archetype name. "
                    + "Example: 'self.encryptionLevel' instead of '"
                    + rootName
                    + "."
                    + sel.field()
                    + "'.",
                "field",
                "predicate",
                "rootName",
                rootName);
          }
        }
        validatePredicateExpr(operand);
      }
      case IDENT, CONSTANT -> {
        /* OK */
      }
      case LIST -> {
        CelExpr.CelList list = kind.list();
        for (CelExpr el : list.elements()) {
          validatePredicateExpr(el);
        }
      }
      default -> {
        /* comprehension, map, struct — allowed */
      }
    }
  }

  // ======================================================================
  // NORM_GUARD_COMPARISON_CONSISTENCY: in-list element validation
  // ======================================================================

  private static void validateInListConsistency(CelExpr listExpr) {
    if (listExpr.exprKind().getKind() != CelExpr.ExprKind.Kind.LIST) {
      return;
    }
    CelExpr.CelList list = listExpr.exprKind().list();
    List<CelExpr> elements = list.elements();
    if (elements.size() < 2) {
      throw RuleViolationException.of(
          RuleType.NORM_GUARD_COMPARISON_CONSISTENCY,
          "Guard 'in' list must have >= 2 elements (single value → use '=='). Found: "
              + elements.size(),
          "elementCount",
          elements.size());
    }
    Set<String> seen = new LinkedHashSet<>();
    CelConstant.Kind firstKind = null;
    for (CelExpr el : elements) {
      if (el.exprKind().getKind() != CelExpr.ExprKind.Kind.CONSTANT) {
        continue; // non-literal elements rejected by guard profile elsewhere
      }
      CelConstant c = el.exprKind().constant();
      CelConstant.Kind kind = c.getKind();
      if (firstKind == null) {
        firstKind = kind;
      } else if (kind != firstKind) {
        throw RuleViolationException.of(
            RuleType.NORM_GUARD_COMPARISON_CONSISTENCY,
            "Guard 'in' list elements must be type-homogeneous. "
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
            RuleType.NORM_GUARD_COMPARISON_CONSISTENCY,
            "Guard 'in' list elements must be unique. Duplicate: " + repr,
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
  // NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION + PROPERTY_PATH_RESOLUTION
  // ======================================================================

  /** Package-private for test access. */
  void validateGuardReferences(String guard) {
    CelExpr ast = parseGuardCel(guardCompiler, guard);
    Set<String> axes = new LinkedHashSet<>();
    collectAxes(ast, axes);
    for (String axis : axes) {
      int dot = axis.indexOf('.');
      if (dot <= 0) continue;
      String archetypeName = axis.substring(0, dot);
      String propertyPath = axis.substring(dot + 1);
      // NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION
      Optional<ArchetypeEntity> archetype = archetypeService.findInEffectByTitle(archetypeName);
      if (archetype.isEmpty()) {
        throw RuleViolationException.of(
            RuleType.NORM_GUARD_ARCHETYPE_REFERENCE_RESOLUTION,
            "Guard references Archetype '"
                + archetypeName
                + "' which does not exist as an active Archetype",
            "archetypeName",
            archetypeName,
            "axis",
            axis);
      }
      // NORM_GUARD_PROPERTY_PATH_RESOLUTION
      JsonNode schema = archetype.get().getStatement();
      if (!resolveSchemaProperty(schema, propertyPath)) {
        throw RuleViolationException.of(
            RuleType.NORM_GUARD_PROPERTY_PATH_RESOLUTION,
            "Guard references property '"
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
  // NORM_PREDICATE_AS_BOOLEAN_RESULT
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

  private static void validatePredicateBooleanResult(CelExpr ast) {
    CelExpr.ExprKind kind = ast.exprKind();
    switch (kind.getKind()) {
      case CALL -> {
        String fn = kind.call().function();
        if ("_?_:_".equals(fn)) {
          return; // ternary: result type depends on branches — accept
        }
        if (!BOOLEAN_PRODUCING_OPS.contains(fn) && !GUARD_ARITHMETIC_OPS.contains(fn)) {
          return; // unknown function — accept (DYN)
        }
        if (GUARD_ARITHMETIC_OPS.contains(fn)) {
          throw RuleViolationException.of(
              RuleType.NORM_PREDICATE_AS_BOOLEAN_RESULT,
              "Predicate top-level expression is arithmetic ('" + fn + "') — must evaluate to bool",
              "function",
              fn);
        }
        // known boolean-producing op: OK
      }
      case CONSTANT -> {
        CelConstant c = kind.constant();
        if (c.getKind() != CelConstant.Kind.BOOLEAN_VALUE) {
          throw RuleViolationException.of(
              RuleType.NORM_PREDICATE_AS_BOOLEAN_RESULT,
              "Predicate top-level expression is a non-boolean constant ("
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
  // NORM_PREDICATE_PROPERTY_PATH_RESOLUTION
  // ======================================================================

  /** Package-private for test access. */
  void validatePredicatePropertyPaths(String predicate, ArchetypeEntity qualifier) {
    CelExpr ast = parsePredicateCel(predicateCompiler, predicate);
    Set<String> paths = new LinkedHashSet<>();
    collectSelfPaths(ast, paths);
    JsonNode schema = qualifier.getStatement();
    for (String path : paths) {
      if (!resolveSchemaProperty(schema, path)) {
        String title = schema.has("title") ? schema.get("title").asText() : "(unknown)";
        throw RuleViolationException.of(
            RuleType.NORM_PREDICATE_PROPERTY_PATH_RESOLUTION,
            "Predicate references 'self."
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

  private static void collectSelfPaths(CelExpr expr, Set<String> paths) {
    CelExpr.ExprKind kind = expr.exprKind();
    switch (kind.getKind()) {
      case SELECT -> {
        String path = extractSelfPath(expr);
        if (path != null) {
          paths.add(path);
        }
        collectSelfPaths(kind.select().operand(), paths);
      }
      case CALL -> {
        CelExpr.CelCall call = kind.call();
        call.target().ifPresent(t -> collectSelfPaths(t, paths));
        for (CelExpr arg : call.args()) {
          collectSelfPaths(arg, paths);
        }
      }
      case LIST -> {
        for (CelExpr el : kind.list().elements()) {
          collectSelfPaths(el, paths);
        }
      }
      default -> {
        /* IDENT, CONSTANT */
      }
    }
  }

  /**
   * Extracts the first-level property path from a self.* select chain. E.g., {@code self.config} →
   * "config", {@code self.config.timeout} → "config". Returns null if the root is not "self".
   */
  private static String extractSelfPath(CelExpr expr) {
    if (expr.exprKind().getKind() != CelExpr.ExprKind.Kind.SELECT) return null;
    CelExpr.CelSelect sel = expr.exprKind().select();
    CelExpr operand = sel.operand();
    if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
        && "self".equals(operand.exprKind().ident().name())) {
      return sel.field();
    }
    return null;
  }

  // ======================================================================
  // NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY
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
              RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY,
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
              RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY,
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
              RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY,
              "AGGREGATED mode forbids sustainedThreshold",
              "toleranceMode",
              mode);
        }
      }
      case "SUSTAINED" -> {
        if (!hasWindow || !hasAggregation || !hasThreshold) {
          throw RuleViolationException.of(
              RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY,
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
              RuleType.NORM_PREDICATE_TOLERANCE_MODE_CONSISTENCY,
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
