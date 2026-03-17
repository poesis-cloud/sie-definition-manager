package cloud.poesis.sie.defman.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.NormRepository;
import cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;

@Service
public class NormService extends AbstractAscriptionService {

    // ======================================================================
    // CEL profile constants (from CelProfileValidator)
    // ======================================================================

    private static final Set<String> GUARD_COMPARISON_OPS = Set.of(
            "_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_", "@in");
    private static final Set<String> GUARD_ALLOWED_FUNCTIONS = Set.of("matches");
    private static final Set<String> GUARD_ARITHMETIC_OPS = Set.of(
            "_+_", "_-_", "_*_", "_%_", "_/_");
    private static final Set<String> PREDICATE_FORBIDDEN_FUNCTIONS = Set.of("now", "uuid");

    private final NormRepository normRepo;
    private final StructureService structureService;
    private final ArchetypeService archetypeService;
    private final CelCompiler guardCompiler;
    private final CelCompiler predicateCompiler;

    public NormService(
            NormRepository normRepo,
            StructureService structureService,
            ArchetypeService archetypeService) {
        this.normRepo = normRepo;
        this.structureService = structureService;
        this.archetypeService = archetypeService;
        this.guardCompiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("self", SimpleType.DYN)
                .build();
        this.predicateCompiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("self", SimpleType.DYN)
                .build();
    }

    @Override
    public DefinitionSubjectType getSubjectType() {
        return DefinitionSubjectType.NORM;
    }

    @Override
    public AscriptionEntity buildEntity(DefinitionEntity definition, ArchetypeEntity archetypeRef, JsonNode statement) {
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

        return new NormEntity(
                definition,
                archetypeRef,
                statement,
                structure,
                qualifier);
    }

    @Override
    public AscriptionEntity save(AscriptionEntity entity) {
        return normRepo.save((NormEntity) entity);
    }

    @Override
    public Page<? extends AscriptionEntity> findAll(Pageable pageable) {
        return normRepo.findAll(pageable);
    }

    @Override
    public Page<? extends AscriptionEntity> findAllByStatus(AscriptionStatusType status, Pageable pageable) {
        return normRepo.findAllByStatus(status, pageable);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionId(UUID definitionId) {
        return normRepo.findAllByDefinitionIdOrderByTimestampDesc(definitionId);
    }

    @Override
    public List<? extends AscriptionEntity> findAllByDefinitionIdAndStatus(
            UUID definitionId,
            Collection<AscriptionStatusType> statuses) {
        return normRepo.findAllByDefinitionIdAndStatusIn(definitionId, statuses);
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
        CelExpr ast = parseCel(guardCompiler, guard, "Guard");
        Set<String> axes = new HashSet<>();
        validateGuardExpr(ast, axes, true);
    }

    /** Package-private for test access. */
    void validatePredicate(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            throw new IllegalArgumentException("Predicate must not be empty");
        }
        CelExpr ast = parseCel(predicateCompiler, predicate, "Predicate");
        validatePredicateExpr(ast);
    }

    private static CelExpr parseCel(CelCompiler compiler, String expression, String profileName) {
        CelValidationResult result = compiler.parse(expression);
        if (result.hasError()) {
            throw new IllegalArgumentException(
                    profileName + " CEL parse error: " + result.getErrorString());
        }
        try {
            CelAbstractSyntaxTree ast = result.getAst();
            return ast.getExpr();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(
                    profileName + " CEL validation error: " + e.getMessage(), e);
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
                    throw new IllegalArgumentException(
                            "Guard profile violation: '||' (OR) is forbidden. "
                                    + "The guard must be a pure conjunction. "
                                    + "Use 'in [...]' for set membership instead of OR.");
                }
                if ("_?_:_".equals(fn)) {
                    throw new IllegalArgumentException(
                            "Guard profile violation: ternary operator (?:) is forbidden in guard expressions.");
                }
                if (GUARD_ARITHMETIC_OPS.contains(fn)) {
                    throw new IllegalArgumentException(
                            "Guard profile violation: arithmetic operators are forbidden in guard expressions.");
                }
                if ("!_".equals(fn) || "_!_".equals(fn)) {
                    for (CelExpr arg : call.args()) {
                        validateGuardExpr(arg, axes, topLevel);
                    }
                    return;
                }
                if (GUARD_COMPARISON_OPS.contains(fn)) {
                    if (topLevel) {
                        validateSingleAxisPredicate(call, axes);
                    }
                    return;
                }
                if (call.target().isPresent()) {
                    if (!GUARD_ALLOWED_FUNCTIONS.contains(fn)) {
                        throw new IllegalArgumentException(
                                "Guard profile violation: only .matches() is allowed as a function call. "
                                        + "Found: ." + fn + "()");
                    }
                    if (topLevel) {
                        String axis = extractAxis(call.target().get());
                        if (axis != null && !axes.add(axis)) {
                            throw new IllegalArgumentException(
                                    "Guard profile violation: duplicate axis '" + axis
                                            + "'. At most one predicate per (Archetype, propertyPath).");
                        }
                    }
                    return;
                }
                throw new IllegalArgumentException(
                        "Guard profile violation: function call '" + fn
                                + "' is forbidden. Only comparison operators and .matches() are allowed.");
            }
            case SELECT, IDENT, CONSTANT, LIST -> {
                /* leaf nodes */ }
            default -> {
                /* comprehension, map, struct */ }
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
            throw new IllegalArgumentException(
                    "Guard profile violation: cross-property comparison detected. "
                            + "Each predicate must compare a single property to a literal. "
                            + "Found axes: " + predAxes);
        }
        for (String axis : predAxes) {
            if (!axes.add(axis)) {
                throw new IllegalArgumentException(
                        "Guard profile violation: duplicate axis '" + axis
                                + "'. At most one predicate per (Archetype, propertyPath).");
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
                    throw new IllegalArgumentException(
                            "Guard profile violation: arithmetic operators are forbidden in guard expressions.");
                }
                throw new IllegalArgumentException(
                        "Guard profile violation: function call '" + fn
                                + "' is forbidden in guard comparison operands. "
                                + "Only property references and literals are allowed. "
                                + "(Only .matches() is allowed as a standalone predicate.)");
            }
            case SELECT -> rejectForbiddenInGuardOperand(kind.select().operand());
            case IDENT, CONSTANT, LIST -> {
                /* valid */ }
            default -> {
                /* not expected */ }
        }
    }

    private static void collectAxes(CelExpr expr, Set<String> axes) {
        CelExpr.ExprKind kind = expr.exprKind();
        switch (kind.getKind()) {
            case SELECT -> {
                String axis = extractAxis(expr);
                if (axis != null)
                    axes.add(axis);
            }
            case CALL -> {
                CelExpr.CelCall call = kind.call();
                call.target().ifPresent(t -> collectAxes(t, axes));
                for (CelExpr arg : call.args()) {
                    collectAxes(arg, axes);
                }
            }
            default -> {
                /* IDENT, CONSTANT, LIST — no axis */ }
        }
    }

    private static String extractAxis(CelExpr expr) {
        if (expr.exprKind().getKind() != CelExpr.ExprKind.Kind.SELECT)
            return null;
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
                    && firstSelect.exprKind().select().operand().exprKind().getKind() != CelExpr.ExprKind.Kind.IDENT) {
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
                    throw new IllegalArgumentException(
                            "Predicate profile violation: non-deterministic functions (now(), uuid()) are forbidden. "
                                    + "Predicate must be deterministic and side-effect-free.");
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
                        throw new IllegalArgumentException(
                                "Predicate profile violation: use 'self' as implicit root, "
                                        + "not an explicit Archetype name. "
                                        + "Example: 'self.encryptionLevel' instead of '"
                                        + rootName + "." + sel.field() + "'.");
                    }
                }
                validatePredicateExpr(operand);
            }
            case IDENT, CONSTANT -> {
                /* OK */ }
            case LIST -> {
                CelExpr.CelList list = kind.list();
                for (CelExpr el : list.elements()) {
                    validatePredicateExpr(el);
                }
            }
            default -> {
                /* comprehension, map, struct — allowed */ }
        }
    }
}
