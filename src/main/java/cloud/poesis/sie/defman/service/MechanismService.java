package cloud.poesis.sie.defman.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.GsmRuleViolationException;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.EffectorRepository;
import cloud.poesis.sie.defman.repository.MechanismRepository;
import cloud.poesis.sie.defman.repository.ReceptorRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import cloud.poesis.sie.defman.type.GsmRuleType;
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

@Service
public class MechanismService extends AbstractAscriptionService {

    // ======================================================================
    // Starlark validation constants (from StarlarkRuleValidator)
    // ======================================================================

    private static final Set<String> ALLOWED_GLOBALS = Set.of(
            "sys", "on", "now", "uuid7", "fullmatch", "search");

    private static final Set<String> STARLARK_BUILTINS = Set.of(
            "True", "False", "None",
            "bool", "dict", "float", "int", "list", "str", "tuple", "type",
            "abs", "all", "any", "dir", "enumerate", "fail", "getattr",
            "hasattr", "hash", "len", "max", "min", "print", "range",
            "repr", "reversed", "sorted", "zip", "map", "filter", "struct");

    private static final Set<String> SYS_METHODS = Set.of(
            "emit", "create", "modify", "delete", "acquire");

    /** Valid read-only properties on the sys namespace object. */
    private static final Set<String> SYS_PROPERTIES = Set.of("id");

    /**
     * GSM §Mechanism V14: maximum number of top-level statements allowed in a
     * Mechanism rule.
     */
    static final int MAX_RULE_STATEMENTS = 200;

    private static final Logger LOG = LoggerFactory.getLogger(MechanismService.class);

    private static final Collection<AscriptionStatusType> MODE_IN_EFFECT = List.of(AscriptionStatusType.ACTIVE,
            AscriptionStatusType.DEPRECATED);

    private final MechanismRepository mechanismRepo;
    private final StructureService structureService;
    private final ArchetypeService archetypeService;
    private final EffectorRepository effectorRepo;
    private final ReceptorRepository receptorRepo;

    public MechanismService(
            MechanismRepository mechanismRepo,
            StructureService structureService,
            ArchetypeService archetypeService,
            EffectorRepository effectorRepo,
            ReceptorRepository receptorRepo) {
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
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
        UUID structureId = extractRequiredUuid(statement, "structure");
        StructureEntity structure = structureService.findEntityById(structureId);

        MechanismEntity entity = new MechanismEntity(
                definition,
                archetypeRef,
                statement,
                structure);

        // GSM: generative/declarative mutual exclusivity at creation time
        validateModeCreation(entity);

        // GSM: Starlark rule structural validation (generative mode)
        if (statement.has("rule") && !statement.get("rule").isNull()) {
            String rule = statement.get("rule").asText();
            if (!rule.isBlank()) {
                validateStarlarkRule(rule);
            }
        }

        return entity;
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return mechanismRepo.save((MechanismEntity) entity);
    }

    public MechanismEntity findEntityById(UUID id) {
        return mechanismRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanism", id));
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return mechanismRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return mechanismRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return mechanismRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return mechanismRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
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

        // GSM: declarative mode must have in-effect ports at activation
        validateModeActivation(m);

        String function = m.getStatement().has("function") ? m.getStatement().get("function").asText() : null;
        if (function == null || function.isBlank()) {
            throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_REQUIREMENT,
                    "Mechanism function must not be empty",
                    "property", "function");
        }
        UUID structureDefId = m.getStructure().getDefinition().getId();
        UUID thisDefId = m.getDefinition().getId();
        List<MechanismEntity> inEffect = mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                structureDefId,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        for (MechanismEntity sibling : inEffect) {
            if (sibling.getDefinition().getId().equals(thisDefId))
                continue;
            String sibFunc = sibling.getStatement().has("function")
                    ? sibling.getStatement().get("function").asText()
                    : null;
            if (function.equals(sibFunc)) {
                throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
                        "Mechanism function '" + function + "' already in-effect for another definition",
                        "property", "function", "value", function,
                        "conflictingAscriptionId", sibling.getId(),
                        "conflictingDefinitionId", sibling.getDefinition().getId());
            }
        }
    }

    // ======================================================================
    // Starlark rule validation (inlined from StarlarkRuleValidator)
    // ======================================================================

    /**
     * Validates a Starlark rule and returns the trigger archetype name.
     * Package-private for test access.
     */
    String validateStarlarkRule(String rule) {
        if (rule == null || rule.isBlank()) {
            throw GsmRuleViolationException.of(GsmRuleType.ASCRIPTION_PROPERTY_FORMATTING,
                    "Mechanism rule must not be null or blank");
        }

        StarlarkFile file = parseStarlark(rule);

        // GSM §Mechanism V14: execution budget — reject rules exceeding statement limit
        int stmtCount = countStatements(file);
        if (stmtCount > MAX_RULE_STATEMENTS) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_BUDGET_EXCEEDED,
                    "Mechanism rule exceeds execution budget: " + stmtCount
                            + " statements (max " + MAX_RULE_STATEMENTS + ")",
                    "statementCount", stmtCount, "maxStatements", MAX_RULE_STATEMENTS);
        }

        for (Statement stmt : file.getStatements()) {
            if (stmt instanceof LoadStatement) {
                throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_LOAD_FORBIDDEN,
                        "load() statements are not allowed in Mechanism rules");
            }
        }

        String triggerArchetype = validateOnTrigger(file);

        if (countOnCalls(file) > 1) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_MULTIPLE_TRIGGERS,
                    "Mechanism rule must have exactly one on() trigger declaration");
        }

        Set<String> locals = collectLocals(file);
        Set<String> unknowns = collectUnknownGlobals(file, locals);
        if (!unknowns.isEmpty()) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_UNKNOWN_GLOBAL,
                    "Unknown globals in Mechanism rule: " + unknowns
                            + ". Allowed: " + ALLOWED_GLOBALS + " + Starlark built-ins",
                    "unknownGlobals", unknowns.toString());
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
                LOG.warn("Mechanism rule references undeclared Archetype: '{}'. "
                        + "The Archetype must be in-effect before Mechanism activation.", archetypeName);
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
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_PARSE_ERROR, sb.toString());
        }
        return file;
    }

    private String validateOnTrigger(StarlarkFile file) {
        CallExpression onCall = extractOnCall(file);
        if (onCall == null) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_MISSING_TRIGGER,
                    "Mechanism rule must begin with on(\"ArchetypeName\") as its first executable statement");
        }

        List<Argument> args = onCall.getArguments();
        if (args.size() != 1 || !(args.get(0) instanceof Argument.Positional)) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT,
                    "on() must have exactly one positional string argument");
        }

        Expression argExpr = args.get(0).getValue();
        if (!(argExpr instanceof StringLiteral sl)) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT,
                    "on() argument must be a string literal");
        }

        String archetype = sl.getValue();
        if (archetype == null || archetype.isBlank()) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT,
                    "on() argument must be a non-empty string literal");
        }
        return archetype;
    }

    private CallExpression extractOnCall(StarlarkFile file) {
        for (Statement stmt : file.getStatements()) {
            if (stmt instanceof ExpressionStatement es && es.getExpression() instanceof CallExpression call) {
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
            if (stmt instanceof ExpressionStatement es && es.getExpression() instanceof CallExpression call) {
                if (isGlobalCall(call, "on"))
                    count++;
            }
            if (stmt instanceof AssignmentStatement as && as.getRHS() instanceof CallExpression call) {
                if (isGlobalCall(call, "on"))
                    count++;
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
        if (!(expr instanceof CallExpression call))
            return;

        if (call.getFunction() instanceof DotExpression dot
                && dot.getObject() instanceof Identifier obj
                && "sys".equals(obj.getName())) {

            String method = dot.getField().getName();
            if (!SYS_METHODS.contains(method)) {
                throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_UNKNOWN_SYS_METHOD,
                        "Unknown sys method: sys." + method + ". Allowed: " + SYS_METHODS,
                        "method", method);
            }

            List<Argument> args = call.getArguments();
            if (args.isEmpty()) {
                throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_SYS_ARGUMENT,
                        "sys." + method + "() requires at least one argument",
                        "method", method);
            }

            Expression firstArg = args.get(0).getValue();
            if (!(firstArg instanceof StringLiteral)) {
                throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_NON_LITERAL_ARCHETYPE,
                        "sys." + method + "() first argument must be a string literal (archetype name)",
                        "method", method);
            }
        }
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

    private void collectUnknownGlobalsInExpr(Expression expr, Set<String> locals, Set<String> unknowns) {
        if (expr instanceof Identifier id) {
            String name = id.getName();
            if (!ALLOWED_GLOBALS.contains(name) && !STARLARK_BUILTINS.contains(name) && !locals.contains(name)) {
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
                    throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_UNKNOWN_SYS_PROPERTY,
                            "Unknown sys property: sys." + field
                                    + ". Allowed methods: " + SYS_METHODS
                                    + ", allowed properties: " + SYS_PROPERTIES,
                            "field", field);
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
        if (!(expr instanceof CallExpression call))
            return;

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
        if (!(expr instanceof CallExpression call))
            return;

        if (call.getFunction() instanceof DotExpression dot
                && dot.getObject() instanceof Identifier obj
                && "sys".equals(obj.getName())) {
            String method = dot.getField().getName();
            if (SYS_METHODS.contains(method)) {
                List<Argument> args = call.getArguments();
                if (args.size() < 2)
                    return;

                // First arg = archetype name; second arg = data dict (for emit/create/modify)
                Expression firstArg = args.get(0).getValue();
                if (!(firstArg instanceof StringLiteral sl))
                    return;
                String archetypeName = sl.getValue();

                Expression secondArg = args.get(1).getValue();
                if (!(secondArg instanceof DictExpression dict))
                    return;

                ArchetypeEntity archetype = archetypeService.findInEffectBySchemaTitle(archetypeName);
                if (archetype == null)
                    return; // Archetype not yet in-effect; can't validate

                JsonNode schema = archetype.getStatement();
                if (schema == null || !schema.has("properties"))
                    return;

                JsonNode properties = schema.get("properties");
                Set<String> schemaKeys = new HashSet<>();
                properties.fieldNames().forEachRemaining(schemaKeys::add);

                for (DictExpression.Entry entry : dict.getEntries()) {
                    if (entry.getKey() instanceof StringLiteral keyLit) {
                        String key = keyLit.getValue();
                        if (!schemaKeys.contains(key)) {
                            LOG.warn("sys.{}(\"{}\", ...): dict key '{}' not in Archetype schema properties {}",
                                    method, archetypeName, key, schemaKeys);
                        }
                    }
                }
            }
        }
    }

    // ======================================================================
    // Mode validation (inlined from MechanismModeValidator)
    // ======================================================================

    void validateModeCreation(MechanismEntity entity) {
        boolean hasRule = hasRule(entity);
        UUID mechanismDefId = entity.getDefinition().getId();

        if (hasRule) {
            boolean hasEffectors = !effectorRepo.findAllByMechanismDefinitionId(mechanismDefId).isEmpty();
            if (hasEffectors) {
                throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_DECLARATION_MODE_EXCLUSIVITY,
                        "Generative mode conflict: explicitly authored Effector Ascriptions exist "
                                + "for Mechanism definition " + mechanismDefId,
                        "mechanismDefinitionId", mechanismDefId, "conflictingPort", "effector");
            }
            boolean hasReceptors = !receptorRepo.findAllByMechanismDefinitionId(mechanismDefId).isEmpty();
            if (hasReceptors) {
                throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_DECLARATION_MODE_EXCLUSIVITY,
                        "Generative mode conflict: explicitly authored Receptor Ascriptions exist "
                                + "for Mechanism definition " + mechanismDefId,
                        "mechanismDefinitionId", mechanismDefId, "conflictingPort", "receptor");
            }
        }
    }

    void validateModeActivation(MechanismEntity entity) {
        boolean hasRule = hasRule(entity);
        if (hasRule)
            return;

        UUID mechanismDefId = entity.getDefinition().getId();
        boolean hasEffectors = !effectorRepo.findAllByMechanismDefinitionIdAndStatusIn(
                mechanismDefId, MODE_IN_EFFECT).isEmpty();
        if (!hasEffectors) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_DECLARATION_MIN_PORT,
                    "Declarative mode: at least 1 in-effect Effector required "
                            + "for Mechanism definition " + mechanismDefId,
                    "mechanismDefinitionId", mechanismDefId, "missingPort", "effector");
        }
        boolean hasReceptors = !receptorRepo.findAllByMechanismDefinitionIdAndStatusIn(
                mechanismDefId, MODE_IN_EFFECT).isEmpty();
        if (!hasReceptors) {
            throw GsmRuleViolationException.of(GsmRuleType.MECHANISM_RULE_DECLARATION_MIN_PORT,
                    "Declarative mode: at least 1 in-effect Receptor required "
                            + "for Mechanism definition " + mechanismDefId,
                    "mechanismDefinitionId", mechanismDefId, "missingPort", "receptor");
        }
    }

    private boolean hasRule(MechanismEntity entity) {
        JsonNode stmt = entity.getStatement();
        return stmt != null && stmt.has("rule") && !stmt.get("rule").isNull()
                && !stmt.get("rule").asText().isBlank();
    }

    // ======================================================================
    // Port auto-derivation (U3/U4 + U12)
    // ======================================================================

    /**
     * A derived port signature from Starlark AST analysis.
     * direction: "effector" or "receptor"
     * archetypeName: the data archetype schema.title
     */
    record PortSignature(String direction, String archetypeName) {
    }

    @Override
    protected void afterCreate(AscriptionEntity saved) {
        MechanismEntity mechanism = (MechanismEntity) saved;
        if (!hasRule(mechanism)) {
            return; // Declarative mode — no auto-derivation
        }

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
     * <ul>
     * <li>on("X") → Receptor for X (trigger)</li>
     * <li>sys.emit/create/modify/delete/acquire("Y") unassigned → Effector for
     * Y</li>
     * <li>var = sys.emit/create/modify/delete/acquire("Y") assigned → Effector for
     * Y + Receptor for Y (closed-loop)</li>
     * <li>sys.emit("Y", data, response="R") → Effector for Y + Receptor for R</li>
     * </ul>
     */
    List<PortSignature> collectPortSignatures(StarlarkFile file) {
        List<PortSignature> signatures = new ArrayList<>();

        for (Statement stmt : file.getStatements()) {
            // on("X") → Receptor
            if (stmt instanceof ExpressionStatement es && es.getExpression() instanceof CallExpression call) {
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

        if (expr == null)
            return;

        if (expr instanceof CallExpression call
                && call.getFunction() instanceof DotExpression dot
                && dot.getObject() instanceof Identifier obj
                && "sys".equals(obj.getName())) {
            String method = dot.getField().getName();
            if (!SYS_METHODS.contains(method))
                return;

            String archetypeName = extractFirstStringArg(call);
            if (archetypeName == null)
                return;

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
        if (args.isEmpty())
            return null;
        Expression first = args.get(0).getValue();
        return (first instanceof StringLiteral sl) ? sl.getValue() : null;
    }

    /**
     * GSM §Mechanism U12: derive port entities with Definition reuse.
     * Match existing Definitions by (Mechanism Definition, data Archetype,
     * direction).
     */
    private void derivePortEntities(MechanismEntity mechanism, List<PortSignature> signatures) {
        // Resolve base typing archetypes
        ArchetypeEntity effectorArchetype = archetypeService.findInEffectBySchemaTitle("EffectorArchetype");
        ArchetypeEntity receptorArchetype = archetypeService.findInEffectBySchemaTitle("ReceptorArchetype");
        if (effectorArchetype == null || receptorArchetype == null) {
            LOG.warn("Base EffectorArchetype/ReceptorArchetype not in-effect; skipping auto-derivation");
            return;
        }

        UUID mechanismDefId = mechanism.getDefinition().getId();
        ObjectMapper mapper = new ObjectMapper();

        // Deduplicate signatures
        Set<PortSignature> unique = new HashSet<>(signatures);

        for (PortSignature sig : unique) {
            ArchetypeEntity dataArchetype = archetypeService.findInEffectBySchemaTitle(sig.archetypeName());
            if (dataArchetype == null) {
                LOG.warn("Auto-derivation: data Archetype '{}' not in-effect; skipping port", sig.archetypeName());
                continue;
            }

            if ("effector".equals(sig.direction())) {
                deriveEffector(mechanism, mechanismDefId, effectorArchetype, dataArchetype, mapper);
            } else {
                deriveReceptor(mechanism, mechanismDefId, receptorArchetype, dataArchetype, mapper);
            }
        }
    }

    private void deriveEffector(MechanismEntity mechanism, UUID mechanismDefId,
            ArchetypeEntity effectorArchetype, ArchetypeEntity dataArchetype, ObjectMapper mapper) {
        // U12: reuse existing Definition by matching (mechanism def, data archetype,
        // direction)
        DefinitionEntity definition = findOrCreatePortDefinition(
                mechanismDefId, dataArchetype.getDefinition().getId(),
                effectorRepo.findAllByMechanismDefinitionId(mechanismDefId),
                e -> ((EffectorEntity) e).getOutputArchetype().getDefinition().getId().equals(
                        dataArchetype.getDefinition().getId()),
                DefinitionSubjectType.EFFECTOR);

        ObjectNode statement = mapper.createObjectNode();
        statement.put("mechanism", mechanismDefId.toString());
        statement.put("archetype", dataArchetype.getDefinition().getId().toString());

        EffectorEntity effector = new EffectorEntity(
                definition, effectorArchetype, statement, mechanism, dataArchetype);
        EffectorEntity saved = effectorRepo.save(effector);
        getTransitionService().recordTransition(saved, null, AscriptionStatusType.DRAFT);
        getEntityManager().refresh(saved);
        LOG.debug("Auto-derived Effector {} for data archetype {}", saved.getId(),
                dataArchetype.getStatement().get("title").asText());
    }

    private void deriveReceptor(MechanismEntity mechanism, UUID mechanismDefId,
            ArchetypeEntity receptorArchetype, ArchetypeEntity dataArchetype, ObjectMapper mapper) {
        // U12: reuse existing Definition by matching (mechanism def, data archetype,
        // direction)
        DefinitionEntity definition = findOrCreatePortDefinition(
                mechanismDefId, dataArchetype.getDefinition().getId(),
                receptorRepo.findAllByMechanismDefinitionId(mechanismDefId),
                e -> ((ReceptorEntity) e).getInputArchetype().getDefinition().getId().equals(
                        dataArchetype.getDefinition().getId()),
                DefinitionSubjectType.RECEPTOR);

        ObjectNode statement = mapper.createObjectNode();
        statement.put("mechanism", mechanismDefId.toString());
        statement.put("archetype", dataArchetype.getDefinition().getId().toString());

        ReceptorEntity receptor = new ReceptorEntity(
                definition, receptorArchetype, statement, mechanism, dataArchetype);
        ReceptorEntity saved = receptorRepo.save(receptor);
        getTransitionService().recordTransition(saved, null, AscriptionStatusType.DRAFT);
        getEntityManager().refresh(saved);
        LOG.debug("Auto-derived Receptor {} for data archetype {}", saved.getId(),
                dataArchetype.getStatement().get("title").asText());
    }

    /**
     * GSM §Mechanism U12: find existing port Definition matching (mechanism def,
     * data archetype),
     * or create a new one.
     */
    private DefinitionEntity findOrCreatePortDefinition(
            UUID mechanismDefId, UUID dataArchetypeDefId,
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
