package io.poesis.sie.defman.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.poesis.sie.defman.entity.ArchetypeEntity;
import io.poesis.sie.defman.entity.AscriptionEntity;
import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.MechanismEntity;
import io.poesis.sie.defman.entity.StructureEntity;
import io.poesis.sie.defman.repository.EffectorRepository;
import io.poesis.sie.defman.repository.MechanismRepository;
import io.poesis.sie.defman.repository.ReceptorRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;
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

    private static final Collection<AscriptionStatusType> MODE_IN_EFFECT = List.of(AscriptionStatusType.ACTIVE,
            AscriptionStatusType.DEPRECATED);

    private final MechanismRepository mechanismRepo;
    private final StructureService structureService;
    private final EffectorRepository effectorRepo;
    private final ReceptorRepository receptorRepo;

    public MechanismService(
            MechanismRepository mechanismRepo,
            StructureService structureService,
            EffectorRepository effectorRepo,
            ReceptorRepository receptorRepo) {
        this.mechanismRepo = mechanismRepo;
        this.structureService = structureService;
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
                .orElseThrow(() -> new IllegalArgumentException("Mechanism not found: " + id));
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
        return mechanismRepo.findAllByDefinition_IdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return mechanismRepo.findAllByDefinition_IdAndStatusIn(definitionId, statuses);
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
            return mechanismRepo.findAllByStructure_Id(sourceAscriptionId);
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
            throw new IllegalArgumentException("Mechanism function must not be empty");
        }
        UUID structureDefId = m.getStructure().getDefinition().getId();
        UUID thisDefId = m.getDefinition().getId();
        List<MechanismEntity> inEffect = mechanismRepo.findAllByStructure_Definition_IdAndStatusIn(
                structureDefId,
                List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED));
        for (MechanismEntity sibling : inEffect) {
            if (sibling.getDefinition().getId().equals(thisDefId))
                continue;
            String sibFunc = sibling.getStatement().has("function")
                    ? sibling.getStatement().get("function").asText()
                    : null;
            if (function.equals(sibFunc)) {
                throw new IllegalArgumentException(
                        "Mechanism function '" + function + "' duplicates in-effect Mechanism "
                                + sibling.getId() + " within Structure definition "
                                + structureDefId);
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
            throw new IllegalArgumentException("Mechanism rule must not be null or blank");
        }

        StarlarkFile file = parseStarlark(rule);

        for (Statement stmt : file.getStatements()) {
            if (stmt instanceof LoadStatement) {
                throw new IllegalArgumentException("load() statements are not allowed in Mechanism rules");
            }
        }

        String triggerArchetype = validateOnTrigger(file);

        if (countOnCalls(file) > 1) {
            throw new IllegalArgumentException("Mechanism rule must have exactly one on() trigger declaration");
        }

        Set<String> locals = collectLocals(file);
        Set<String> unknowns = collectUnknownGlobals(file, locals);
        if (!unknowns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown globals in Mechanism rule: " + unknowns
                            + ". Allowed: " + ALLOWED_GLOBALS + " + Starlark built-ins");
        }

        for (Statement stmt : file.getStatements()) {
            if (stmt instanceof ExpressionStatement es) {
                validateSysCallsInExpr(es.getExpression());
            } else if (stmt instanceof AssignmentStatement as) {
                validateSysCallsInExpr(as.getRHS());
            } else if (stmt instanceof ForStatement fs) {
                for (Statement body : fs.getBody()) {
                    if (body instanceof ExpressionStatement es) {
                        validateSysCallsInExpr(es.getExpression());
                    } else if (body instanceof AssignmentStatement as2) {
                        validateSysCallsInExpr(as2.getRHS());
                    }
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
            throw new IllegalArgumentException(sb.toString());
        }
        return file;
    }

    private String validateOnTrigger(StarlarkFile file) {
        CallExpression onCall = extractOnCall(file);
        if (onCall == null) {
            throw new IllegalArgumentException(
                    "Mechanism rule must begin with on(\"ArchetypeName\") as its first executable statement");
        }

        List<Argument> args = onCall.getArguments();
        if (args.size() != 1 || !(args.get(0) instanceof Argument.Positional)) {
            throw new IllegalArgumentException("on() must have exactly one positional string argument");
        }

        Expression argExpr = args.get(0).getValue();
        if (!(argExpr instanceof StringLiteral sl)) {
            throw new IllegalArgumentException("on() argument must be a string literal");
        }

        String archetype = sl.getValue();
        if (archetype == null || archetype.isBlank()) {
            throw new IllegalArgumentException("on() argument must be a non-empty string literal");
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
                throw new IllegalArgumentException(
                        "Unknown sys method: sys." + method + ". Allowed: " + SYS_METHODS);
            }

            List<Argument> args = call.getArguments();
            if (args.isEmpty()) {
                throw new IllegalArgumentException("sys." + method + "() requires at least one argument");
            }

            Expression firstArg = args.get(0).getValue();
            if (!(firstArg instanceof StringLiteral)) {
                throw new IllegalArgumentException(
                        "sys." + method + "() first argument must be a string literal (archetype name)");
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
    // Mode validation (inlined from MechanismModeValidator)
    // ======================================================================

    void validateModeCreation(MechanismEntity entity) {
        boolean hasRule = hasRule(entity);
        UUID mechanismDefId = entity.getDefinition().getId();

        if (hasRule) {
            boolean hasEffectors = !effectorRepo.findAllByMechanism_Definition_Id(mechanismDefId).isEmpty();
            if (hasEffectors) {
                throw new IllegalArgumentException(
                        "Generative mode conflict: explicitly authored Effector Ascriptions exist "
                                + "for Mechanism definition " + mechanismDefId);
            }
            boolean hasReceptors = !receptorRepo.findAllByMechanism_Definition_Id(mechanismDefId).isEmpty();
            if (hasReceptors) {
                throw new IllegalArgumentException(
                        "Generative mode conflict: explicitly authored Receptor Ascriptions exist "
                                + "for Mechanism definition " + mechanismDefId);
            }
        }
    }

    void validateModeActivation(MechanismEntity entity) {
        boolean hasRule = hasRule(entity);
        if (hasRule)
            return;

        UUID mechanismDefId = entity.getDefinition().getId();
        boolean hasEffectors = !effectorRepo.findAllByMechanism_Definition_IdAndStatusIn(
                mechanismDefId, MODE_IN_EFFECT).isEmpty();
        if (!hasEffectors) {
            throw new IllegalArgumentException(
                    "Declarative mode: at least 1 in-effect Effector required "
                            + "for Mechanism definition " + mechanismDefId);
        }
        boolean hasReceptors = !receptorRepo.findAllByMechanism_Definition_IdAndStatusIn(
                mechanismDefId, MODE_IN_EFFECT).isEmpty();
        if (!hasReceptors) {
            throw new IllegalArgumentException(
                    "Declarative mode: at least 1 in-effect Receptor required "
                            + "for Mechanism definition " + mechanismDefId);
        }
    }

    private boolean hasRule(MechanismEntity entity) {
        JsonNode stmt = entity.getStatement();
        return stmt != null && stmt.has("rule") && !stmt.get("rule").isNull()
                && !stmt.get("rule").asText().isBlank();
    }
}
