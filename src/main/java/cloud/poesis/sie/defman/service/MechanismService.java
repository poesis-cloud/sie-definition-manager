package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.repository.AbstractAscriptionRepository;
import cloud.poesis.sie.defman.repository.AscriptionRepository;
import cloud.poesis.sie.defman.repository.EffectorRepository;
import cloud.poesis.sie.defman.repository.MechanismRepository;
import cloud.poesis.sie.defman.repository.ReceptorRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.PrimitiveType;
import cloud.poesis.sie.defman.type.RuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.DictExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.ListExpression;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.SyntaxError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GSM Mechanism ascription service.
 *
 * <p>Manages lifecycle and persistence of {@link MechanismEntity} ascriptions including Starlark
 * rule structural validation, auto-derivation of Effectors and Receptors from the rule AST, and
 * governing cascade from owning Structure.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class MechanismService extends AbstractAscriptionService<MechanismEntity> {

  // ======================================================================
  // Starlark validation constants (from StarlarkRuleValidator)
  // ======================================================================

  private static final Set<String> ALLOWED_GLOBALS =
      Set.of("sys", "on", "now", "uuid7", "fullmatch", "search");

  private static final Set<String> STARLARK_BUILTINS =
      Set.of(
          "True",
          "False",
          "None",
          "bool",
          "dict",
          "float",
          "int",
          "list",
          "str",
          "tuple",
          "type",
          "abs",
          "all",
          "any",
          "dir",
          "enumerate",
          "fail",
          "getattr",
          "hasattr",
          "hash",
          "len",
          "max",
          "min",
          "print",
          "range",
          "repr",
          "reversed",
          "sorted",
          "zip",
          "map",
          "filter",
          "struct");

  private static final Set<String> SYS_METHODS =
      Set.of("emit", "create", "modify", "delete", "acquire");

  /** Valid read-only properties on the sys namespace object. */
  private static final Set<String> SYS_PROPERTIES = Set.of("id");

  /** GSM §Mechanism V14: maximum number of top-level statements allowed in a Mechanism rule. */
  static final int MAX_RULE_STATEMENTS = 200;

  private static final Logger LOG = LoggerFactory.getLogger(MechanismService.class);

  private final MechanismRepository mechanismRepo;
  private final StructureService structureService;
  private final ArchetypeService archetypeService;
  private final EffectorRepository effectorRepo;
  private final ReceptorRepository receptorRepo;

  /**
   * Constructs the Mechanism service with its required dependencies.
   *
   * @param mechanismRepo the mechanism repository
   * @param structureService the structure service for reference resolution
   * @param archetypeService the archetype service for data archetype resolution
   * @param effectorRepo the effector repository for port auto-derivation
   * @param receptorRepo the receptor repository for port auto-derivation
   * @param definitionService the definition service
   * @param transitionService the status transition service
   * @param ascriptionRepository the base ascription repository
   * @param entityManager the JPA entity manager
   * @param dataProtectionService the data protection service
   */
  public MechanismService(
      MechanismRepository mechanismRepo,
      StructureService structureService,
      ArchetypeService archetypeService,
      EffectorRepository effectorRepo,
      ReceptorRepository receptorRepo,
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
    this.mechanismRepo = mechanismRepo;
    this.structureService = structureService;
    this.archetypeService = archetypeService;
    this.effectorRepo = effectorRepo;
    this.receptorRepo = receptorRepo;
  }

  @Override
  public DefinitionSubjectType getSubjectType() {
    return DefinitionSubjectType.MECHANISM;
  }

  @Override
  protected AbstractAscriptionRepository<MechanismEntity> getRepository() {
    return mechanismRepo;
  }

  @Override
  public MechanismEntity buildEntity(
      DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
    UUID structureId = extractRequiredUuid(statement, "structure");
    StructureEntity structure = structureService.findEntityById(structureId);

    MechanismEntity entity = new MechanismEntity(definition, archetypeRef, statement, structure);

    // GSM: Starlark rule structural validation
    validateStarlarkRule(statement.get("rule").asText());

    return entity;
  }

  /**
   * Finds a Mechanism entity by its ascription id.
   *
   * @param id the ascription UUID
   * @return the mechanism entity
   * @throws ResourceNotFoundException if no mechanism exists with the given id
   */
  public MechanismEntity findEntityById(UUID id) {
    return mechanismRepo
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(PrimitiveType.MECHANISM, id));
  }

  // ---- Lifecycle descriptors ----

  @Override
  public List<RefereeReference> getRefereeReferences(AscriptionEntity entity) {
    var m = (MechanismEntity) entity;
    return List.of(new RefereeReference(m.getStructure(), "structure"));
  }

  @Override
  public Map<DefinitionSubjectType, AscriptionStatusTransitionCascadeType> getCascadeTargetRoles() {
    return Map.of(DefinitionSubjectType.STRUCTURE, AscriptionStatusTransitionCascadeType.GOVERNING);
  }

  @Override
  public List<? extends AscriptionEntity> findCascadeTargetsFrom(
      DefinitionSubjectType sourceType, UUID sourceAscriptionId) {
    if (sourceType == DefinitionSubjectType.STRUCTURE) {
      return mechanismRepo.findAllByStructureId(sourceAscriptionId);
    }
    return List.of();
  }

  @Override
  public Map<String, Object> getIdentityBoundValues(AscriptionEntity entity) {
    var m = (MechanismEntity) entity;
    return Map.of(
        "structure", m.getStructure().getDefinition().getId(),
        "function", m.getStatement().get("function").asText());
  }

  @Override
  public void validateActivationUniqueness(AscriptionEntity entity) {
    var m = (MechanismEntity) entity;

    String function =
        m.getStatement().has("function") ? m.getStatement().get("function").asText() : null;
    if (function == null || function.isBlank()) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Mechanism function must not be empty",
          "property",
          "function");
    }
    UUID structureDefId = m.getStructure().getDefinition().getId();
    UUID thisDefId = m.getDefinition().getId();
    List<MechanismEntity> inEffect =
        mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
            structureDefId, List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
    for (MechanismEntity sibling : inEffect) {
      if (sibling.getDefinition().getId().equals(thisDefId)) continue;
      String sibFunc =
          sibling.getStatement().has("function")
              ? sibling.getStatement().get("function").asText()
              : null;
      if (function.equals(sibFunc)) {
        throw RuleViolationException.of(
            RuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
            "Mechanism function '" + function + "' already in-effect for another definition",
            "property",
            "function",
            "value",
            function,
            "conflictingAscriptionId",
            sibling.getId(),
            "conflictingDefinitionId",
            sibling.getDefinition().getId());
      }
    }
  }

  // ======================================================================
  // Starlark rule validation (inlined from StarlarkRuleValidator)
  // ======================================================================

  /**
   * Validates a Starlark rule and returns the trigger archetype name. Package-private for test
   * access.
   */
  String validateStarlarkRule(String rule) {
    if (rule == null || rule.isBlank()) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_STATEMENT_COMPLIANCE_TO_GSM_ARCHETYPE,
          "Mechanism rule must not be null or blank",
          "field",
          "rule");
    }

    StarlarkFile file = parseStarlark(rule);

    // GSM §Mechanism V14: execution budget — reject rules exceeding statement limit
    int stmtCount = countStatements(file);
    if (stmtCount > MAX_RULE_STATEMENTS) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_STARLARK_BUDGET,
          "Mechanism rule exceeds execution budget: "
              + stmtCount
              + " statements (max "
              + MAX_RULE_STATEMENTS
              + ")",
          "statementCount",
          stmtCount,
          "maxStatements",
          MAX_RULE_STATEMENTS);
    }

    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof LoadStatement) {
        throw RuleViolationException.of(
            RuleType.MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST,
            "load() statements are not allowed in Mechanism rules",
            "field",
            "rule",
            "construct",
            "load");
      }
    }

    String triggerArchetype = validateOnTrigger(file);

    if (countOnCalls(file) > 1) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT,
          "Mechanism rule must have exactly one on() trigger declaration",
          "field",
          "rule",
          "construct",
          "on");
    }

    Set<String> locals = collectLocals(file);
    Set<String> unknowns = collectUnknownGlobals(file, locals);
    if (!unknowns.isEmpty()) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
          "Unknown globals in Mechanism rule: "
              + unknowns
              + ". Allowed: "
              + ALLOWED_GLOBALS
              + " + Starlark built-ins",
          "unknownGlobals",
          unknowns.toString());
    }

    // Collect all archetype names referenced in sys.* calls
    Set<String> referencedArchetypes = new HashSet<>();
    referencedArchetypes.add(triggerArchetype);

    for (Statement stmt : file.getStatements()) {
      validateSysCallsInStatement(stmt);
      collectSysArchetypeNames(stmt, referencedArchetypes);
      if (stmt instanceof ForStatement fs) {
        for (Statement body : fs.getBody()) {
          validateSysCallsInStatement(body);
          collectSysArchetypeNames(body, referencedArchetypes);
        }
      }
    }

    // GSM §Mechanism V10: validate archetype names resolve to declared Archetypes
    for (String archetypeName : referencedArchetypes) {
      ArchetypeEntity resolved = archetypeService.findInEffectBySchemaTitle(archetypeName);
      if (resolved == null) {
        LOG.warn(
            "Mechanism rule references undeclared Archetype: '{}'. "
                + "The Archetype must be in-effect before Mechanism activation.",
            archetypeName);
      }
    }

    // GSM §Mechanism V11: best-effort dict literal schema conformance
    for (Statement stmt : file.getStatements()) {
      validateDictLiteralConformance(stmt);
      if (stmt instanceof ForStatement fs) {
        for (Statement body : fs.getBody()) {
          validateDictLiteralConformance(body);
        }
      }
    }

    return triggerArchetype;
  }

  private StarlarkFile parseStarlark(String rule) {
    ParserInput input = ParserInput.fromString(rule, "<mechanism-rule>");
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    if (!file.errors().isEmpty()) {
      StringBuilder sb = new StringBuilder("Starlark syntax errors:");
      for (SyntaxError e : file.errors()) {
        sb.append("\n  - ").append(e.message());
      }
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_STARLARK_PARSING, sb.toString(), "field", "rule");
    }
    return file;
  }

  private String validateOnTrigger(StarlarkFile file) {
    CallExpression onCall = extractOnCall(file);
    if (onCall == null) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT,
          "Mechanism rule must begin with on(\"ArchetypeName\") as its first executable statement",
          "field",
          "rule",
          "construct",
          "on");
    }

    List<Argument> args = onCall.getArguments();
    if (args.size() != 1 || !(args.get(0) instanceof Argument.Positional)) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
          "on() must have exactly one positional string argument",
          "field",
          "rule",
          "construct",
          "on");
    }

    Expression argExpr = args.get(0).getValue();
    if (!(argExpr instanceof StringLiteral sl)) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
          "on() argument must be a string literal",
          "field",
          "rule",
          "construct",
          "on");
    }

    String archetype = sl.getValue();
    if (archetype == null || archetype.isBlank()) {
      throw RuleViolationException.of(
          RuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
          "on() argument must be a non-empty string literal",
          "field",
          "rule",
          "construct",
          "on");
    }
    return archetype;
  }

  private CallExpression extractOnCall(StarlarkFile file) {
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof ExpressionStatement es
          && es.getExpression() instanceof CallExpression call) {
        if (call.getFunction() instanceof Identifier id && "on".equals(id.getName())) {
          return call;
        }
      }
      if (stmt instanceof AssignmentStatement as && as.getRHS() instanceof CallExpression call) {
        if (call.getFunction() instanceof Identifier id && "on".equals(id.getName())) {
          return call;
        }
      }
    }
    return null;
  }

  private boolean isGlobalCall(CallExpression call, String name) {
    return call.getFunction() instanceof Identifier id && name.equals(id.getName());
  }

  private int countOnCalls(StarlarkFile file) {
    int count = 0;
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof ExpressionStatement es
          && es.getExpression() instanceof CallExpression call) {
        if (isGlobalCall(call, "on")) count++;
      }
      if (stmt instanceof AssignmentStatement as && as.getRHS() instanceof CallExpression call) {
        if (isGlobalCall(call, "on")) count++;
      }
    }
    return count;
  }

  private void validateSysCallsInStatement(Statement stmt) {
    if (stmt instanceof ExpressionStatement es) {
      validateSysCallsInExpr(es.getExpression());
    } else if (stmt instanceof AssignmentStatement as) {
      validateSysCallsInExpr(as.getRHS());
    }
  }

  private void validateSysCallsInExpr(Expression expr) {
    if (!(expr instanceof CallExpression call)) return;

    if (call.getFunction() instanceof DotExpression dot
        && dot.getObject() instanceof Identifier obj
        && "sys".equals(obj.getName())) {

      String method = dot.getField().getName();
      if (!SYS_METHODS.contains(method)) {
        throw RuleViolationException.of(
            RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
            "Unknown sys method: sys." + method + ". Allowed: " + SYS_METHODS,
            "method",
            method);
      }

      List<Argument> args = call.getArguments();
      if (args.isEmpty()) {
        throw RuleViolationException.of(
            sysMethodArityRule(method),
            "sys." + method + "() requires at least one argument",
            "method",
            method);
      }

      Expression firstArg = args.get(0).getValue();
      if (!(firstArg instanceof StringLiteral)) {
        throw RuleViolationException.of(
            sysMethodArityRule(method),
            "sys." + method + "() first argument must be a string literal (archetype name)",
            "method",
            method);
      }

      // Validate response= keyword arg (only valid on sys.emit)
      for (Argument arg : args) {
        if (arg instanceof Argument.Keyword kw && "response".equals(kw.getName())) {
          if (!"emit".equals(method)) {
            throw RuleViolationException.of(
                RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_RESPONSE,
                "response= keyword argument is only valid on sys.emit(), not sys." + method + "()",
                "method",
                method);
          }
          if (!(kw.getValue() instanceof StringLiteral sl)) {
            throw RuleViolationException.of(
                RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_RESPONSE,
                "sys.emit() response= must be a string literal (archetype name)",
                "method",
                method);
          }
          if (sl.getValue().isBlank()) {
            throw RuleViolationException.of(
                RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_RESPONSE,
                "sys.emit() response= must be a non-empty archetype name",
                "method",
                method);
          }
        }
      }
    }
  }

  private static RuleType sysMethodArityRule(String method) {
    return switch (method) {
      case "emit" -> RuleType.MECHANISM_RULE_SYS_EMIT_METHOD_ARITY;
      case "create" -> RuleType.MECHANISM_RULE_SYS_CREATE_METHOD_ARITY;
      case "modify" -> RuleType.MECHANISM_RULE_SYS_MODIFY_METHOD_ARITY;
      case "delete" -> RuleType.MECHANISM_RULE_SYS_DELETE_METHOD_ARITY;
      case "acquire" -> RuleType.MECHANISM_RULE_SYS_ACQUIRE_METHOD_ARITY;
      default -> RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST;
    };
  }

  private Set<String> collectLocals(StarlarkFile file) {
    Set<String> locals = new HashSet<>();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof AssignmentStatement as && as.getLHS() instanceof Identifier id) {
        locals.add(id.getName());
      }
      if (stmt instanceof ForStatement fs && fs.getVars() instanceof Identifier id) {
        locals.add(id.getName());
      }
    }
    return locals;
  }

  private Set<String> collectUnknownGlobals(StarlarkFile file, Set<String> locals) {
    Set<String> unknowns = new HashSet<>();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof ExpressionStatement es) {
        collectUnknownGlobalsInExpr(es.getExpression(), locals, unknowns);
      } else if (stmt instanceof AssignmentStatement as) {
        collectUnknownGlobalsInExpr(as.getRHS(), locals, unknowns);
      } else if (stmt instanceof ForStatement fs) {
        collectUnknownGlobalsInExpr(fs.getCollection(), locals, unknowns);
        for (Statement body : fs.getBody()) {
          if (body instanceof ExpressionStatement es) {
            collectUnknownGlobalsInExpr(es.getExpression(), locals, unknowns);
          } else if (body instanceof AssignmentStatement as2) {
            collectUnknownGlobalsInExpr(as2.getRHS(), locals, unknowns);
          }
        }
      }
    }
    return unknowns;
  }

  private void collectUnknownGlobalsInExpr(
      Expression expr, Set<String> locals, Set<String> unknowns) {
    if (expr instanceof Identifier id) {
      String name = id.getName();
      if (!ALLOWED_GLOBALS.contains(name)
          && !STARLARK_BUILTINS.contains(name)
          && !locals.contains(name)) {
        unknowns.add(name);
      }
    } else if (expr instanceof CallExpression call) {
      collectUnknownGlobalsInExpr(call.getFunction(), locals, unknowns);
      for (Argument arg : call.getArguments()) {
        collectUnknownGlobalsInExpr(arg.getValue(), locals, unknowns);
      }
    } else if (expr instanceof DotExpression dot) {
      collectUnknownGlobalsInExpr(dot.getObject(), locals, unknowns);
      // GSM §Mechanism U2: validate sys.* property accesses (non-call)
      if (dot.getObject() instanceof Identifier obj && "sys".equals(obj.getName())) {
        String field = dot.getField().getName();
        if (!SYS_METHODS.contains(field) && !SYS_PROPERTIES.contains(field)) {
          throw RuleViolationException.of(
              RuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
              "Unknown sys property: sys."
                  + field
                  + ". Allowed methods: "
                  + SYS_METHODS
                  + ", allowed properties: "
                  + SYS_PROPERTIES,
              "field",
              field);
        }
      }
    } else if (expr instanceof ListExpression list) {
      for (Expression elem : list.getElements()) {
        collectUnknownGlobalsInExpr(elem, locals, unknowns);
      }
    } else if (expr instanceof DictExpression dict) {
      for (DictExpression.Entry entry : dict.getEntries()) {
        collectUnknownGlobalsInExpr(entry.getKey(), locals, unknowns);
        collectUnknownGlobalsInExpr(entry.getValue(), locals, unknowns);
      }
    }
  }

  // ======================================================================
  // Execution budget (V14)
  // ======================================================================

  private int countStatements(StarlarkFile file) {
    int count = 0;
    for (Statement stmt : file.getStatements()) {
      count++;
      if (stmt instanceof ForStatement fs) {
        count += fs.getBody().size();
      }
    }
    return count;
  }

  // ======================================================================
  // sys.* archetype name collection (V10)
  // ======================================================================

  private void collectSysArchetypeNames(Statement stmt, Set<String> names) {
    if (stmt instanceof ExpressionStatement es) {
      collectSysArchetypeNamesInExpr(es.getExpression(), names);
    } else if (stmt instanceof AssignmentStatement as) {
      collectSysArchetypeNamesInExpr(as.getRHS(), names);
    }
  }

  private void collectSysArchetypeNamesInExpr(Expression expr, Set<String> names) {
    if (!(expr instanceof CallExpression call)) return;

    if (call.getFunction() instanceof DotExpression dot
        && dot.getObject() instanceof Identifier obj
        && "sys".equals(obj.getName())) {
      String method = dot.getField().getName();
      if (SYS_METHODS.contains(method)) {
        List<Argument> args = call.getArguments();
        if (!args.isEmpty()) {
          Expression firstArg = args.get(0).getValue();
          if (firstArg instanceof StringLiteral sl) {
            names.add(sl.getValue());
          }
        }
        // Check for response= keyword arg (sys.emit with response archetype)
        for (Argument arg : args) {
          if (arg instanceof Argument.Keyword kw && "response".equals(kw.getName())) {
            if (kw.getValue() instanceof StringLiteral sl) {
              names.add(sl.getValue());
            }
          }
        }
      }
    }
  }

  // ======================================================================
  // Dict literal schema conformance (V11 — best-effort)
  // ======================================================================

  private void validateDictLiteralConformance(Statement stmt) {
    if (stmt instanceof ExpressionStatement es) {
      validateDictLiteralInSysCall(es.getExpression());
    } else if (stmt instanceof AssignmentStatement as) {
      validateDictLiteralInSysCall(as.getRHS());
    }
  }

  private void validateDictLiteralInSysCall(Expression expr) {
    if (!(expr instanceof CallExpression call)) return;

    if (call.getFunction() instanceof DotExpression dot
        && dot.getObject() instanceof Identifier obj
        && "sys".equals(obj.getName())) {
      String method = dot.getField().getName();
      if (SYS_METHODS.contains(method)) {
        List<Argument> args = call.getArguments();
        if (args.size() < 2) return;

        // First arg = archetype name; second arg = data dict (for emit/create/modify)
        Expression firstArg = args.get(0).getValue();
        if (!(firstArg instanceof StringLiteral sl)) return;
        String archetypeName = sl.getValue();

        Expression secondArg = args.get(1).getValue();
        if (!(secondArg instanceof DictExpression dict)) return;

        ArchetypeEntity archetype = archetypeService.findInEffectBySchemaTitle(archetypeName);
        if (archetype == null) return; // Archetype not yet in-effect; can't validate

        JsonNode schema = archetype.getStatement();
        if (schema == null || !schema.has("properties")) return;

        JsonNode properties = schema.get("properties");
        Set<String> schemaKeys = new HashSet<>();
        properties.fieldNames().forEachRemaining(schemaKeys::add);

        for (DictExpression.Entry entry : dict.getEntries()) {
          if (entry.getKey() instanceof StringLiteral keyLit) {
            String key = keyLit.getValue();
            if (!schemaKeys.contains(key)) {
              LOG.warn(
                  "sys.{}(\"{}\", ...): dict key '{}' not in Archetype schema properties {}",
                  method,
                  archetypeName,
                  key,
                  schemaKeys);
            }
          }
        }
      }
    }
  }

  // ======================================================================
  // Port auto-derivation (U3/U4 + U12)
  // ======================================================================

  /**
   * A derived port signature from Starlark AST analysis. direction: "effector" or "receptor"
   * archetypeName: the data archetype schema.title
   */
  record PortSignature(String direction, String archetypeName) {}

  @Override
  protected void afterCreate(AscriptionEntity saved) {
    MechanismEntity mechanism = (MechanismEntity) saved;
    String rule = mechanism.getStatement().get("rule").asText();
    StarlarkFile file = parseStarlark(rule);
    List<PortSignature> signatures = collectPortSignatures(file);
    if (signatures.isEmpty()) {
      return;
    }

    derivePortEntities(mechanism, signatures);
  }

  /**
   * GSM §Mechanism U3/U4: collect port signatures from Starlark AST.
   *
   * <ul>
   *   <li>on("X") → Receptor for X (trigger)
   *   <li>sys.emit/create/modify/delete/acquire("Y") unassigned → Effector for Y
   *   <li>var = sys.emit/create/modify/delete/acquire("Y") assigned → Effector for Y + Receptor for
   *       Y (closed-loop)
   *   <li>sys.emit("Y", data, response="R") → Effector for Y + Receptor for R
   * </ul>
   */
  List<PortSignature> collectPortSignatures(StarlarkFile file) {
    List<PortSignature> signatures = new ArrayList<>();

    for (Statement stmt : file.getStatements()) {
      // on("X") → Receptor
      if (stmt instanceof ExpressionStatement es
          && es.getExpression() instanceof CallExpression call) {
        if (isGlobalCall(call, "on")) {
          String name = extractFirstStringArg(call);
          if (name != null) {
            signatures.add(new PortSignature("receptor", name));
          }
        }
      }
      if (stmt instanceof AssignmentStatement as && as.getRHS() instanceof CallExpression call) {
        if (isGlobalCall(call, "on")) {
          String name = extractFirstStringArg(call);
          if (name != null) {
            signatures.add(new PortSignature("receptor", name));
          }
        }
      }

      collectPortSignaturesFromStatement(stmt, signatures);
      if (stmt instanceof ForStatement fs) {
        for (Statement body : fs.getBody()) {
          collectPortSignaturesFromStatement(body, signatures);
        }
      }
    }

    return signatures;
  }

  private void collectPortSignaturesFromStatement(Statement stmt, List<PortSignature> signatures) {
    boolean assigned = false;
    Expression expr = null;

    if (stmt instanceof ExpressionStatement es) {
      expr = es.getExpression();
      assigned = false;
    } else if (stmt instanceof AssignmentStatement as) {
      expr = as.getRHS();
      assigned = true;
    }

    if (expr == null) return;

    if (expr instanceof CallExpression call
        && call.getFunction() instanceof DotExpression dot
        && dot.getObject() instanceof Identifier obj
        && "sys".equals(obj.getName())) {
      String method = dot.getField().getName();
      if (!SYS_METHODS.contains(method)) return;

      String archetypeName = extractFirstStringArg(call);
      if (archetypeName == null) return;

      // Effector for the output
      signatures.add(new PortSignature("effector", archetypeName));

      // U4 closed-loop: assigned → Receptor feedback
      if (assigned) {
        signatures.add(new PortSignature("receptor", archetypeName));
      }

      // U3/U4: response= keyword → Receptor for response archetype
      for (Argument arg : call.getArguments()) {
        if (arg instanceof Argument.Keyword kw && "response".equals(kw.getName())) {
          if (kw.getValue() instanceof StringLiteral sl) {
            signatures.add(new PortSignature("receptor", sl.getValue()));
          }
        }
      }
    }
  }

  private String extractFirstStringArg(CallExpression call) {
    List<Argument> args = call.getArguments();
    if (args.isEmpty()) return null;
    Expression first = args.get(0).getValue();
    return (first instanceof StringLiteral sl) ? sl.getValue() : null;
  }

  /**
   * GSM §Mechanism U12: derive port entities with Definition reuse. Match existing Definitions by
   * (Mechanism Definition, data Archetype, direction).
   */
  private void derivePortEntities(MechanismEntity mechanism, List<PortSignature> signatures) {
    // Resolve base typing archetypes
    ArchetypeEntity effectorArchetype =
        archetypeService.findInEffectBySchemaTitle("EffectorArchetype");
    ArchetypeEntity receptorArchetype =
        archetypeService.findInEffectBySchemaTitle("ReceptorArchetype");
    if (effectorArchetype == null || receptorArchetype == null) {
      LOG.warn("Base EffectorArchetype/ReceptorArchetype not in-effect; skipping auto-derivation");
      return;
    }

    UUID mechanismDefId = mechanism.getDefinition().getId();
    ObjectMapper mapper = new ObjectMapper();

    // Deduplicate signatures
    Set<PortSignature> unique = new HashSet<>(signatures);

    for (PortSignature sig : unique) {
      ArchetypeEntity dataArchetype =
          archetypeService.findInEffectBySchemaTitle(sig.archetypeName());
      if (dataArchetype == null) {
        LOG.warn(
            "Auto-derivation: data Archetype '{}' not in-effect; skipping port",
            sig.archetypeName());
        continue;
      }

      if ("effector".equals(sig.direction())) {
        deriveEffector(mechanism, mechanismDefId, effectorArchetype, dataArchetype, mapper);
      } else {
        deriveReceptor(mechanism, mechanismDefId, receptorArchetype, dataArchetype, mapper);
      }
    }
  }

  private void deriveEffector(
      MechanismEntity mechanism,
      UUID mechanismDefId,
      ArchetypeEntity effectorArchetype,
      ArchetypeEntity dataArchetype,
      ObjectMapper mapper) {
    // U12: reuse existing Definition by matching (mechanism def, data archetype,
    // direction)
    DefinitionEntity definition =
        findOrCreatePortDefinition(
            mechanismDefId,
            dataArchetype.getDefinition().getId(),
            effectorRepo.findAllByMechanismDefinitionId(mechanismDefId),
            e ->
                ((EffectorEntity) e)
                    .getOutputArchetype()
                    .getDefinition()
                    .getId()
                    .equals(dataArchetype.getDefinition().getId()),
            DefinitionSubjectType.EFFECTOR);

    ObjectNode statement = mapper.createObjectNode();
    statement.put("mechanism", mechanismDefId.toString());
    statement.put("archetype", dataArchetype.getDefinition().getId().toString());

    EffectorEntity effector =
        new EffectorEntity(definition, effectorArchetype, statement, mechanism, dataArchetype);
    EffectorEntity saved = effectorRepo.save(effector);
    getTransitionService().recordTransition(saved, null, AscriptionStatusType.DRAFT);
    getEntityManager().refresh(saved);
    LOG.debug(
        "Auto-derived Effector {} for data archetype {}",
        saved.getId(),
        dataArchetype.getStatement().get("title").asText());
  }

  private void deriveReceptor(
      MechanismEntity mechanism,
      UUID mechanismDefId,
      ArchetypeEntity receptorArchetype,
      ArchetypeEntity dataArchetype,
      ObjectMapper mapper) {
    // U12: reuse existing Definition by matching (mechanism def, data archetype,
    // direction)
    DefinitionEntity definition =
        findOrCreatePortDefinition(
            mechanismDefId,
            dataArchetype.getDefinition().getId(),
            receptorRepo.findAllByMechanismDefinitionId(mechanismDefId),
            e ->
                ((ReceptorEntity) e)
                    .getInputArchetype()
                    .getDefinition()
                    .getId()
                    .equals(dataArchetype.getDefinition().getId()),
            DefinitionSubjectType.RECEPTOR);

    ObjectNode statement = mapper.createObjectNode();
    statement.put("mechanism", mechanismDefId.toString());
    statement.put("archetype", dataArchetype.getDefinition().getId().toString());

    ReceptorEntity receptor =
        new ReceptorEntity(definition, receptorArchetype, statement, mechanism, dataArchetype);
    ReceptorEntity saved = receptorRepo.save(receptor);
    getTransitionService().recordTransition(saved, null, AscriptionStatusType.DRAFT);
    getEntityManager().refresh(saved);
    LOG.debug(
        "Auto-derived Receptor {} for data archetype {}",
        saved.getId(),
        dataArchetype.getStatement().get("title").asText());
  }

  /**
   * GSM §Mechanism U12: find existing port Definition matching (mechanism def, data archetype), or
   * create a new one.
   */
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
    return getDefinitionService().create(portType);
  }
}
