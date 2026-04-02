package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.DirectiveEntity;
import cloud.poesis.sie.defman.entity.NormEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AppraisalRuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests AppraisalService governance appraisal rules:
 *
 * <ul>
 *   <li>DIRECTIVE_VERB_COMPATIBILITY — contradictory verb detection
 *   <li>DIRECTIVE_MODAL_COMPATIBILITY — contradictory modal detection
 *   <li>NORM_DIRECTIVE_BACKING — directive backing validation (governance chain)
 *   <li>NORM_ASSERTION_COMPATIBILITY — overlapping norm detection
 * </ul>
 *
 * <p>These rules enforce inter-ascription governance coherence at activation time, which is
 * fundamental to GSM's DNA governance grammar integrity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppraisalServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private DirectiveService directiveService;
  @Mock private NormService normService;
  @Mock private ArchetypeService archetypeService;

  private AppraisalService service;

  @BeforeEach
  void setUp() {
    service =
        new AppraisalService(
            directiveService,
            normService,
            archetypeService,
            dev.cel.compiler.CelCompilerFactory.standardCelCompilerBuilder().build());
  }

  // ========================================================================
  // areModalContradictions — static utility
  // ========================================================================

  @Nested
  class AreModalContradictions {

    @Test
    void sameModal_notContradictory() {
      assertFalse(AppraisalService.areModalContradictions("MUST", "MUST"));
      assertFalse(AppraisalService.areModalContradictions("SHOULD", "SHOULD"));
      assertFalse(AppraisalService.areModalContradictions("MAY", "MAY"));
    }

    @Test
    void mustVsMustNot_contradictory() {
      assertTrue(AppraisalService.areModalContradictions("MUST", "MUST_NOT"));
      assertTrue(AppraisalService.areModalContradictions("MUST_NOT", "MUST"));
    }

    @Test
    void shouldVsShouldNot_contradictory() {
      assertTrue(AppraisalService.areModalContradictions("SHOULD", "SHOULD_NOT"));
      assertTrue(AppraisalService.areModalContradictions("SHOULD_NOT", "SHOULD"));
    }

    @Test
    void differentRoots_notContradictory() {
      // MUST vs SHOULD are different modals, not contradictions
      assertFalse(AppraisalService.areModalContradictions("MUST", "SHOULD"));
      assertFalse(AppraisalService.areModalContradictions("MUST_NOT", "SHOULD_NOT"));
    }

    @Test
    void mayHasNoNegation_notContradictory() {
      assertFalse(AppraisalService.areModalContradictions("MAY", "MUST"));
      assertFalse(AppraisalService.areModalContradictions("MAY", "SHOULD"));
    }
  }

  // ========================================================================
  // MODAL_PRECEDENCE — tier structure
  // ========================================================================

  @Nested
  class ModalPrecedence {

    @Test
    void mustAndMustNot_tier3() {
      assertEquals(3, AppraisalService.MODAL_PRECEDENCE.get("MUST"));
      assertEquals(3, AppraisalService.MODAL_PRECEDENCE.get("MUST_NOT"));
    }

    @Test
    void shouldAndShouldNot_tier2() {
      assertEquals(2, AppraisalService.MODAL_PRECEDENCE.get("SHOULD"));
      assertEquals(2, AppraisalService.MODAL_PRECEDENCE.get("SHOULD_NOT"));
    }

    @Test
    void may_tier1() {
      assertEquals(1, AppraisalService.MODAL_PRECEDENCE.get("MAY"));
    }
  }

  // ========================================================================
  // validateDirectiveCompatibility — VERB contradictions
  // ========================================================================

  @Nested
  class DirectiveCompatibilityVerb {

    @Test
    void noSiblings_noConflict() {
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose");
      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }

    @Test
    void sameSelfDefinition_skipped() {
      UUID defId = UUID.randomUUID();
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose", defId);
      DirectiveEntity sibling = stubDirective("PREVENT", "MUST", "test-purpose", defId);
      copyQualifierDef(sibling, directive);

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }

    @Test
    void ensureVsPrevent_contradicts() {
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "PREVENT", "MUST", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateDirectiveCompatibility(directive));
      assertEquals(AppraisalRuleType.DIRECTIVE_VERB_COMPATIBILITY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("ENSURE"));
      assertTrue(ex.getMessage().contains("PREVENT"));
    }

    @Test
    void preventVsEnsure_contradicts() {
      DirectiveEntity directive = stubDirective("PREVENT", "MUST", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "ENSURE", "MUST", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateDirectiveCompatibility(directive));
      assertEquals(AppraisalRuleType.DIRECTIVE_VERB_COMPATIBILITY, ex.getRuleType());
    }

    @Test
    void nonContradictoryVerbs_passes() {
      // ENSURE vs MAINTAIN — not in CONTRADICTORY_VERB_PAIRS
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "MAINTAIN", "MUST", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }

    @Test
    void differentQualifier_noConflict() {
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose");
      // Sibling has a different qualifier definition ID
      DirectiveEntity sibling = stubDirective("PREVENT", "MUST", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }
  }

  // ========================================================================
  // validateDirectiveCompatibility — MODAL contradictions
  // ========================================================================

  @Nested
  class DirectiveCompatibilityModal {

    @Test
    void sameVerbMustVsMustNot_contradicts() {
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "ENSURE", "MUST_NOT", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateDirectiveCompatibility(directive));
      assertEquals(AppraisalRuleType.DIRECTIVE_MODAL_COMPATIBILITY, ex.getRuleType());
      assertTrue(ex.getMessage().contains("MUST"));
      assertTrue(ex.getMessage().contains("MUST_NOT"));
    }

    @Test
    void sameVerbShouldVsShouldNot_contradicts() {
      DirectiveEntity directive = stubDirective("OPTIMIZE", "SHOULD", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "OPTIMIZE", "SHOULD_NOT", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      RuleViolationException ex =
          assertThrows(
              RuleViolationException.class,
              () -> service.validateDirectiveCompatibility(directive));
      assertEquals(AppraisalRuleType.DIRECTIVE_MODAL_COMPATIBILITY, ex.getRuleType());
    }

    @Test
    void sameVerbDifferentTier_logsWarning_noException() {
      // MUST ENSURE vs MAY ENSURE — different tiers, no contradiction, just
      // precedence warning
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "ENSURE", "MAY", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }

    @Test
    void sameVerbSameTierSameModal_noConflict() {
      DirectiveEntity directive = stubDirective("ENSURE", "MUST", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "ENSURE", "MUST", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      // Same verb, same modal — no conflict at all
      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }

    @Test
    void sameVerbDifferentModal_shouldVsMay_logsWarning() {
      // SHOULD ENSURE vs MAY ENSURE — different modal, both not contradictions, tier
      // differs
      DirectiveEntity directive = stubDirective("ENSURE", "SHOULD", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "ENSURE", "MAY", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }

    @Test
    void sameVerbDifferentModal_mayVsMust_reverseWinnerPath() {
      // MAY ENSURE vs MUST ENSURE — "this" has lower precedence than sibling
      DirectiveEntity directive = stubDirective("ENSURE", "MAY", "test-purpose");
      DirectiveEntity sibling =
          stubDirectiveWithSameQualifier(directive, "ENSURE", "MUST", "test-purpose");

      when(directiveService.findAllInEffectByPurpose("test-purpose")).thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateDirectiveCompatibility(directive));
    }
  }

  // ========================================================================
  // validateGovernanceChain — NORM_DIRECTIVE_BACKING
  // ========================================================================

  @Nested
  class GovernanceChain {

    @Test
    void noDirectivesForPurpose_fails() {
      NormEntity norm = stubNorm("structure-purpose");
      when(directiveService.findAllInEffectByPurpose("structure-purpose")).thenReturn(List.of());

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateGovernanceChain(norm));
      assertEquals(AppraisalRuleType.NORM_DIRECTIVE_BACKING, ex.getRuleType());
      assertTrue(ex.getMessage().contains("No in-effect Directive"));
    }

    @Test
    void directiveWithOverlappingQualifierLineage_passes() {
      UUID normQualId = UUID.randomUUID();
      NormEntity norm = stubNorm("structure-purpose", normQualId);
      DirectiveEntity directive = stubDirectiveForGovernanceChain("structure-purpose");

      when(directiveService.findAllInEffectByPurpose("structure-purpose"))
          .thenReturn(List.of(directive));

      // Both share a common ancestor "QualityDimension"
      when(archetypeService.getAncestorTitles(normQualId))
          .thenReturn(Set.of("Availability", "QualityDimension"));
      when(archetypeService.getAncestorTitles(directive.getQualifier().getId()))
          .thenReturn(Set.of("Performance", "QualityDimension"));

      assertDoesNotThrow(() -> service.validateGovernanceChain(norm));
    }

    @Test
    void directiveWithNoOverlap_fails() {
      UUID normQualId = UUID.randomUUID();
      NormEntity norm = stubNorm("structure-purpose", normQualId);
      DirectiveEntity directive = stubDirectiveForGovernanceChain("structure-purpose");

      when(directiveService.findAllInEffectByPurpose("structure-purpose"))
          .thenReturn(List.of(directive));

      // No common ancestor
      when(archetypeService.getAncestorTitles(normQualId)).thenReturn(Set.of("Availability"));
      when(archetypeService.getAncestorTitles(directive.getQualifier().getId()))
          .thenReturn(Set.of("Security"));

      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.validateGovernanceChain(norm));
      assertEquals(AppraisalRuleType.NORM_DIRECTIVE_BACKING, ex.getRuleType());
      assertTrue(ex.getMessage().contains("no overlap"));
    }

    @Test
    void multipleDirectives_oneOverlaps_passes() {
      UUID normQualId = UUID.randomUUID();
      NormEntity norm = stubNorm("structure-purpose", normQualId);

      DirectiveEntity d1 = stubDirectiveForGovernanceChain("structure-purpose");
      DirectiveEntity d2 = stubDirectiveForGovernanceChain("structure-purpose");

      when(directiveService.findAllInEffectByPurpose("structure-purpose"))
          .thenReturn(List.of(d1, d2));

      when(archetypeService.getAncestorTitles(normQualId)).thenReturn(Set.of("Availability"));
      // d1: no overlap; d2: overlap
      when(archetypeService.getAncestorTitles(d1.getQualifier().getId()))
          .thenReturn(Set.of("Security"));
      when(archetypeService.getAncestorTitles(d2.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));

      assertDoesNotThrow(() -> service.validateGovernanceChain(norm));
    }
  }

  // ========================================================================
  // validateNormCompatibility — NORM_ASSERTION_COMPATIBILITY
  // ========================================================================

  @Nested
  class NormCompatibility {

    @Test
    void noSiblings_noConflict() {
      NormEntity norm = stubNormForCompatibility("score > 0.9", UUID.randomUUID());
      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of());

      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }

    @Test
    void sameSelfDefinition_skipped() {
      UUID defId = UUID.randomUUID();
      NormEntity norm = stubNormForCompatibility("score > 0.9", defId);
      NormEntity sibling = stubNormForCompatibility("score < 0.5", defId);

      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of(sibling));

      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }

    @Test
    void noQualifierOverlap_noConflict() {
      NormEntity norm = stubNormForCompatibility("score > 0.9", UUID.randomUUID());
      NormEntity sibling = stubNormForCompatibility("score < 0.5", UUID.randomUUID());
      setSameStructureDef(norm, sibling);

      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of(sibling));
      when(archetypeService.getAncestorTitles(norm.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));
      when(archetypeService.getAncestorTitles(sibling.getQualifier().getId()))
          .thenReturn(Set.of("Security"));

      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }

    @Test
    void overlappingQualifier_sameAssertion_noConflict() {
      NormEntity norm = stubNormForCompatibility("score > 0.9", UUID.randomUUID());
      NormEntity sibling = stubNormForCompatibility("score > 0.9", UUID.randomUUID());
      setSameStructureDef(norm, sibling);

      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of(sibling));
      when(archetypeService.getAncestorTitles(norm.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));
      when(archetypeService.getAncestorTitles(sibling.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));

      // Same assertion — no conflict (norms are compatible)
      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }

    @Test
    void overlappingQualifier_differentAssertion_commonProperties_warns() {
      NormEntity norm = stubNormForCompatibility("score > 0.9", UUID.randomUUID());
      NormEntity sibling = stubNormForCompatibility("score < 0.5", UUID.randomUUID());
      setSameStructureDef(norm, sibling);

      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of(sibling));
      when(archetypeService.getAncestorTitles(norm.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));
      when(archetypeService.getAncestorTitles(sibling.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));

      // Different assertion, common property "score" — warns but doesn't throw
      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }

    @Test
    void overlappingQualifier_differentAssertion_noCommonProperties_noWarning() {
      NormEntity norm = stubNormForCompatibility("score > 0.9", UUID.randomUUID());
      NormEntity sibling = stubNormForCompatibility("latency < 100", UUID.randomUUID());
      setSameStructureDef(norm, sibling);

      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of(sibling));
      when(archetypeService.getAncestorTitles(norm.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));
      when(archetypeService.getAncestorTitles(sibling.getQualifier().getId()))
          .thenReturn(Set.of("Availability"));

      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }

    @Test
    void normWithEmptyAssertion_noError() {
      NormEntity norm = stubNormForCompatibility("", UUID.randomUUID());
      NormEntity sibling = stubNormForCompatibility("score > 0.5", UUID.randomUUID());
      setSameStructureDef(norm, sibling);

      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of(sibling));
      when(archetypeService.getAncestorTitles(norm.getQualifier().getId()))
          .thenReturn(Set.of("QD"));
      when(archetypeService.getAncestorTitles(sibling.getQualifier().getId()))
          .thenReturn(Set.of("QD"));

      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }

    @Test
    void siblingWithMissingAssertionField_noError() {
      // Sibling has no "assertion" key in statement → exercises ternary fallback to
      // ""
      NormEntity norm = stubNormForCompatibility("score > 0.5", UUID.randomUUID());
      NormEntity sibling = stubNormForCompatibility("", UUID.randomUUID());
      setSameStructureDef(norm, sibling);

      when(normService.findAllByStructureDefinitionIdAndStatusIn(any(), any()))
          .thenReturn(List.of(sibling));
      when(archetypeService.getAncestorTitles(norm.getQualifier().getId()))
          .thenReturn(Set.of("QD"));
      when(archetypeService.getAncestorTitles(sibling.getQualifier().getId()))
          .thenReturn(Set.of("QD"));

      assertDoesNotThrow(() -> service.validateNormCompatibility(norm));
    }
  }

  // ========================================================================
  // extractAssertionProperties — CEL property extraction
  // ========================================================================

  @Nested
  class ExtractAssertionProperties {

    @Test
    void nullInput_emptySet() {
      assertEquals(Set.of(), service.extractAssertionProperties(null));
    }

    @Test
    void blankInput_emptySet() {
      assertEquals(Set.of(), service.extractAssertionProperties("   "));
    }

    @Test
    void invalidCel_emptySet() {
      assertEquals(Set.of(), service.extractAssertionProperties("!!! invalid !!!"));
    }

    @Test
    void simpleIdent_extracted() {
      Set<String> props = service.extractAssertionProperties("score > 0.9");
      assertTrue(props.contains("score"));
    }

    @Test
    void selectExpression_extractsRoot() {
      Set<String> props = service.extractAssertionProperties("metrics.latency < 100");
      assertTrue(props.contains("metrics"));
    }

    @Test
    void deeplyNestedSelect_extractsRoot() {
      Set<String> props = service.extractAssertionProperties("a.b.c.d > 0");
      assertTrue(props.contains("a"));
    }

    @Test
    void functionCall_extractsArgs() {
      Set<String> props = service.extractAssertionProperties("size(items) > 0");
      assertTrue(props.contains("items"));
    }

    @Test
    void listExpression_extractsElements() {
      Set<String> props = service.extractAssertionProperties("[a, b, c].size() > 0");
      assertTrue(props.contains("a"));
      assertTrue(props.contains("b"));
      assertTrue(props.contains("c"));
    }

    @Test
    void macroCall_extractsIterableAndBoundVar() {
      // CEL parse() does not expand macros — exists() stays as a CALL node.
      // Both 'items' (target) and 'x' (arg ident) appear as properties.
      Set<String> props = service.extractAssertionProperties("items.exists(x, x > 0)");
      assertTrue(props.contains("items"));
      assertTrue(props.contains("x"));
    }

    @Test
    void multipleProperties() {
      Set<String> props = service.extractAssertionProperties("score > 0.5 && latency < 200");
      assertTrue(props.contains("score"));
      assertTrue(props.contains("latency"));
    }

    @Test
    void constantOnly_extractedAsEmptyOrConstName() {
      // "true" is a constant — the CEL parser treats it as a constant, not an ident
      Set<String> props = service.extractAssertionProperties("true");
      // Constants don't produce idents
      assertFalse(props.contains("true"));
    }
  }

  // ========================================================================
  // Stub helpers
  // ========================================================================

  private DirectiveEntity stubDirective(String verb, String modal, String purpose) {
    return stubDirective(verb, modal, purpose, UUID.randomUUID());
  }

  private DirectiveEntity stubDirective(String verb, String modal, String purpose, UUID defId) {
    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    UUID qualDefId = UUID.randomUUID();
    DefinitionEntity qualDef = mock(DefinitionEntity.class);
    when(qualDef.getId()).thenReturn(qualDefId);
    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getDefinition()).thenReturn(qualDef);
    when(qualifier.getId()).thenReturn(UUID.randomUUID());

    ObjectNode stmt = MAPPER.createObjectNode();
    stmt.put("verb", verb);
    stmt.put("modal", modal);
    stmt.put("purpose", purpose);

    DirectiveEntity d = mock(DirectiveEntity.class);
    when(d.getDefinition()).thenReturn(def);
    when(d.getStatement()).thenReturn(stmt);
    when(d.getQualifier()).thenReturn(qualifier);
    when(d.getId()).thenReturn(UUID.randomUUID());

    return d;
  }

  private DirectiveEntity stubDirectiveWithSameQualifier(
      DirectiveEntity ref, String verb, String modal, String purpose) {
    // Extract qualifier reference BEFORE any when() to avoid Mockito state
    // confusion
    ArchetypeEntity sharedQualifier = ref.getQualifier();

    UUID sibDefId = UUID.randomUUID();
    DefinitionEntity sibDef = mock(DefinitionEntity.class);
    when(sibDef.getId()).thenReturn(sibDefId);

    ObjectNode stmt = MAPPER.createObjectNode();
    stmt.put("verb", verb);
    stmt.put("modal", modal);
    stmt.put("purpose", purpose);

    DirectiveEntity sibling = mock(DirectiveEntity.class);
    when(sibling.getDefinition()).thenReturn(sibDef);
    when(sibling.getStatement()).thenReturn(stmt);
    when(sibling.getQualifier()).thenReturn(sharedQualifier);
    when(sibling.getId()).thenReturn(UUID.randomUUID());

    return sibling;
  }

  private void copyQualifierDef(DirectiveEntity target, DirectiveEntity source) {
    ArchetypeEntity sharedQualifier = source.getQualifier();
    when(target.getQualifier()).thenReturn(sharedQualifier);
  }

  private NormEntity stubNorm(String structurePurpose) {
    return stubNorm(structurePurpose, UUID.randomUUID());
  }

  private NormEntity stubNorm(String structurePurpose, UUID qualifierId) {
    ObjectNode structStmt = MAPPER.createObjectNode().put("purpose", structurePurpose);
    StructureEntity structure = mock(StructureEntity.class);
    when(structure.getStatement()).thenReturn(structStmt);

    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getId()).thenReturn(qualifierId);

    NormEntity norm = mock(NormEntity.class);
    when(norm.getStructure()).thenReturn(structure);
    when(norm.getQualifier()).thenReturn(qualifier);

    return norm;
  }

  private DirectiveEntity stubDirectiveForGovernanceChain(String purpose) {
    UUID qualId = UUID.randomUUID();
    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getId()).thenReturn(qualId);

    DirectiveEntity d = mock(DirectiveEntity.class);
    when(d.getQualifier()).thenReturn(qualifier);

    return d;
  }

  private NormEntity stubNormForCompatibility(String assertion, UUID defId) {
    UUID structDefId = UUID.randomUUID();
    DefinitionEntity structDef = mock(DefinitionEntity.class);
    when(structDef.getId()).thenReturn(structDefId);
    StructureEntity structure = mock(StructureEntity.class);
    when(structure.getDefinition()).thenReturn(structDef);

    DefinitionEntity def = mock(DefinitionEntity.class);
    when(def.getId()).thenReturn(defId);

    UUID qualId = UUID.randomUUID();
    ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
    when(qualifier.getId()).thenReturn(qualId);

    ObjectNode stmt = MAPPER.createObjectNode();
    if (assertion != null && !assertion.isEmpty()) {
      stmt.put("assertion", assertion);
    }

    NormEntity norm = mock(NormEntity.class);
    when(norm.getDefinition()).thenReturn(def);
    when(norm.getStructure()).thenReturn(structure);
    when(norm.getQualifier()).thenReturn(qualifier);
    when(norm.getStatement()).thenReturn(stmt);
    when(norm.getId()).thenReturn(UUID.randomUUID());

    return norm;
  }

  private void setSameStructureDef(NormEntity norm, NormEntity sibling) {
    // Extract structDef BEFORE when() to avoid Mockito state confusion
    DefinitionEntity structDef = norm.getStructure().getDefinition();
    when(sibling.getStructure().getDefinition()).thenReturn(structDef);
  }
}
