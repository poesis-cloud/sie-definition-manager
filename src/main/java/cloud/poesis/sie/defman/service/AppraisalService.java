package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AppraisalRuleType;
import cloud.poesis.sie.defman.type.AscriptionStatusType;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Governance appraisal service. Implements ONLY {@link AppraisalRuleType} rules.
 *
 * <p>Evaluates cross-ascription governance compatibility at activation time:
 *
 * <ul>
 *   <li>{@link AppraisalRuleType#DIRECTIVE_COMPATIBILITY_ON_VERB} — contradictory verb detection
 *   <li>{@link AppraisalRuleType#DIRECTIVE_COMPATIBILITY_ON_MODAL} — contradictory modal detection
 *   <li>{@link AppraisalRuleType#NORM_DIRECTED} — directive backing validation
 *   <li>{@link AppraisalRuleType#NORM_COMPATIBILITY} — overlapping norm detection
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class AppraisalService {

  private static final Logger LOG = LoggerFactory.getLogger(AppraisalService.class);

  private static final Collection<AscriptionStatusType> IN_EFFECT =
      List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

  private static final Set<Set<String>> CONTRADICTORY_VERB_PAIRS =
      Set.of(Set.of("ENSURE", "PREVENT"));

  /** Modal precedence tiers: higher value = higher precedence. */
  public static final Map<String, Integer> MODAL_PRECEDENCE =
      Map.of(
          "MUST", 3,
          "MUST_NOT", 3,
          "SHOULD", 2,
          "SHOULD_NOT", 2,
          "MAY", 1);

  private final DirectiveService directiveService;
  private final NormService normService;
  private final ArchetypeService archetypeService;
  private final CelCompiler celParser;

  public AppraisalService(
      DirectiveService directiveService,
      NormService normService,
      ArchetypeService archetypeService) {
    this.directiveService = directiveService;
    this.normService = normService;
    this.archetypeService = archetypeService;
    this.celParser = CelCompilerFactory.standardCelCompilerBuilder().build();
  }

  // ======================================================================
  // DIRECTIVE_COMPATIBILITY_ON_VERB / DIRECTIVE_COMPATIBILITY_ON_MODAL
  // ======================================================================

  /**
   * Validates that a directive being activated does not contradict existing in-effect directives on
   * the same qualifier + purpose axis.
   *
   * @param directive the directive being activated
   * @throws RuleViolationException if verb or modal contradictions are found
   */
  public void validateDirectiveCompatibility(DirectiveEntity directive) {
    UUID qualifierDefId = directive.getQualifier().getDefinition().getId();
    UUID purposeDefId = directive.getPurpose().getDefinition().getId();
    UUID thisDefId = directive.getDefinition().getId();

    String verb = directive.getStatement().get("verb").asText();
    String modal = directive.getStatement().get("modal").asText();

    List<DirectiveEntity> siblings =
        directiveService.findAllByQualifierDefinitionIdAndPurposeDefinitionIdAndStatusIn(
            qualifierDefId, purposeDefId, IN_EFFECT);

    for (DirectiveEntity sibling : siblings) {
      if (sibling.getDefinition().getId().equals(thisDefId)) {
        continue;
      }

      String sibVerb = sibling.getStatement().get("verb").asText();
      String sibModal = sibling.getStatement().get("modal").asText();

      if (!verb.equals(sibVerb) && CONTRADICTORY_VERB_PAIRS.contains(Set.of(verb, sibVerb))) {
        throw RuleViolationException.of(
            AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_VERB,
            "Directive contradiction: "
                + verb
                + " and "
                + sibVerb
                + " on same qualifier (definition "
                + qualifierDefId
                + ") and purpose (definition "
                + purposeDefId
                + "). Conflicting directive: "
                + sibling.getId(),
            "verb",
            verb,
            "siblingVerb",
            sibVerb,
            "qualifierDefinitionId",
            qualifierDefId,
            "purposeDefinitionId",
            purposeDefId,
            "conflictingDirectiveId",
            sibling.getId());
      }

      if (verb.equals(sibVerb) && areModalContradictions(modal, sibModal)) {
        throw RuleViolationException.of(
            AppraisalRuleType.DIRECTIVE_COMPATIBILITY_ON_MODAL,
            "Directive modal contradiction: "
                + modal
                + " "
                + verb
                + " vs "
                + sibModal
                + " "
                + sibVerb
                + " on same qualifier (definition "
                + qualifierDefId
                + ") and purpose (definition "
                + purposeDefId
                + "). Conflicting directive: "
                + sibling.getId(),
            "verb",
            verb,
            "siblingVerb",
            sibVerb,
            "modal",
            modal,
            "siblingModal",
            sibModal,
            "qualifierDefinitionId",
            qualifierDefId,
            "purposeDefinitionId",
            purposeDefId,
            "conflictingDirectiveId",
            sibling.getId());
      }

      // GSM §DirectiveModal: modal precedence — higher tier overrides lower tier
      if (verb.equals(sibVerb) && !modal.equals(sibModal)) {
        int thisTier = MODAL_PRECEDENCE.getOrDefault(modal, 0);
        int sibTier = MODAL_PRECEDENCE.getOrDefault(sibModal, 0);
        if (thisTier != sibTier) {
          String winner = thisTier > sibTier ? modal : sibModal;
          String loser = thisTier > sibTier ? sibModal : modal;
          LOG.warn(
              "Directive modal precedence: {} {} overrides {} {} on verb {} "
                  + "for qualifier (definition {}) and purpose (definition {}). "
                  + "Overridden directive: {}",
              winner,
              verb,
              loser,
              verb,
              verb,
              qualifierDefId,
              purposeDefId,
              sibling.getId());
        }
      }
    }
  }

  /**
   * Returns {@code true} if two modals form a contradiction (e.g. MUST vs MUST_NOT).
   *
   * @param a first modal
   * @param b second modal
   * @return true if the pair is contradictory
   */
  public static boolean areModalContradictions(String a, String b) {
    if (a.equals(b)) return false;
    String norm1 = a.replace("_NOT", "");
    String norm2 = b.replace("_NOT", "");
    if (!norm1.equals(norm2)) return false;
    boolean oneIsNot = a.endsWith("_NOT") ^ b.endsWith("_NOT");
    return oneIsNot;
  }

  // ======================================================================
  // NORM_DIRECTED: Directive backing validation
  // ======================================================================

  /**
   * Validates that a norm being activated has directive backing (governance chain).
   *
   * @param norm the norm being activated
   * @throws RuleViolationException if no in-effect directive legitimates the norm
   */
  public void validateGovernanceChain(NormEntity norm) {
    UUID structureDefId = norm.getStructure().getDefinition().getId();
    UUID qualifierId = norm.getQualifier().getId();

    List<DirectiveEntity> directives =
        directiveService.findAllByPurposeDefinitionIdAndStatusIn(structureDefId, IN_EFFECT);

    if (directives.isEmpty()) {
      throw RuleViolationException.of(
          AppraisalRuleType.NORM_DIRECTED,
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
                  for (String da : directiveAncestors) {
                    if (normAncestors.contains(da)) {
                      return true;
                    }
                  }
                  return false;
                });

    if (!hasLegitimatingDirective) {
      throw RuleViolationException.of(
          AppraisalRuleType.NORM_DIRECTED,
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
  // NORM_COMPATIBILITY: Overlapping Norm detection
  // ======================================================================

  /**
   * Detects potential conflicts between a norm being activated and existing in-effect norms on the
   * same structure.
   *
   * @param norm the norm being activated
   */
  public void validateNormCompatibility(NormEntity norm) {
    UUID structureDefId = norm.getStructure().getDefinition().getId();
    UUID thisDefId = norm.getDefinition().getId();
    UUID qualifierId = norm.getQualifier().getId();

    Set<String> normAncestors = archetypeService.getAncestorTitles(qualifierId);
    String normAssertion =
        norm.getStatement().has("assertion") ? norm.getStatement().get("assertion").asText() : "";

    List<NormEntity> siblings =
        normService.findAllByStructureDefinitionIdAndStatusIn(structureDefId, IN_EFFECT);

    for (NormEntity sibling : siblings) {
      if (sibling.getDefinition().getId().equals(thisDefId)) {
        continue;
      }

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

      String siblingAssertion =
          sibling.getStatement().has("assertion")
              ? sibling.getStatement().get("assertion").asText()
              : "";
      if (normAssertion.equals(siblingAssertion)) {
        continue;
      }

      Set<String> normProps = extractAssertionProperties(normAssertion);
      Set<String> siblingProps = extractAssertionProperties(siblingAssertion);
      Set<String> commonProps = new HashSet<>(normProps);
      commonProps.retainAll(siblingProps);

      if (!commonProps.isEmpty()) {
        LOG.warn(
            "[{}] Potential Norm conflict: Norm (definition {}) and sibling {} "
                + "(definition {}) target overlapping qualifier lineage and "
                + "assert on common properties {}",
            AppraisalRuleType.NORM_COMPATIBILITY.getType(),
            thisDefId,
            sibling.getId(),
            sibling.getDefinition().getId(),
            commonProps);
      }
    }
  }

  /**
   * Extracts bare property identifiers from a CEL assertion expression.
   *
   * @param assertion the CEL assertion expression
   * @return the set of property identifiers referenced
   */
  public Set<String> extractAssertionProperties(String assertion) {
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
  // CEL AST utilities (private)
  // ======================================================================

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
}
