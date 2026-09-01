package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AscriptionConsistencyRuleTypeTest {

  @Test
  void archetypeStemUniqueness_hasStablePublicMetadata() {
    AscriptionConsistencyRuleType rule =
        AscriptionConsistencyRuleType.ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS;

    assertEquals("gsm:rules/archetype/stem/uniqueness-across-definitions", rule.getType());
    assertEquals("Archetype stem uniqueness across definitions", rule.getTitle());
    assertTrue(rule.getDescription().contains("one Definition"));
  }

  @Test
  void archetypeUriResolutionUniqueness_hasStablePublicMetadata() {
    AscriptionConsistencyRuleType rule =
        AscriptionConsistencyRuleType.ARCHETYPE_URI_RESOLUTION_UNIQUENESS;

    assertEquals("gsm:rules/archetype/uri/resolution-uniqueness", rule.getType());
    assertEquals("Archetype URI resolution uniqueness", rule.getTitle());
    assertTrue(rule.getDescription().contains("version > 0"));
  }

  @Test
  void archetypeAllOfPropertyDisjointness_hasStablePublicMetadata() {
    AscriptionConsistencyRuleType rule =
        AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS;

    assertEquals("gsm:rules/archetype/allof/property-disjointness", rule.getType());
    assertEquals("Archetype allOf property disjointness", rule.getTitle());
    assertTrue(rule.getDescription().contains("mount properties"));
  }

  @Test
  void archetypeAllOfPropertyTypeStability_hasStablePublicMetadata() {
    AscriptionConsistencyRuleType rule =
        AscriptionConsistencyRuleType.ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY;

    assertEquals("gsm:rules/archetype/allof/property-type-stability", rule.getType());
    assertEquals("Archetype allOf property type stability", rule.getTitle());
    assertTrue(rule.getDescription().contains("type set"));
  }

  @ParameterizedTest
  @EnumSource(AscriptionConsistencyRuleType.class)
  void getType_returnsNonBlank(AscriptionConsistencyRuleType rule) {
    assertNotNull(rule.getType());
    assertFalse(rule.getType().isBlank());
  }

  @ParameterizedTest
  @EnumSource(AscriptionConsistencyRuleType.class)
  void getType_startsWithGsmRulesPrefix(AscriptionConsistencyRuleType rule) {
    assertTrue(rule.getType().startsWith("gsm:rules/"));
  }

  @ParameterizedTest
  @EnumSource(AscriptionConsistencyRuleType.class)
  void getTitle_returnsNonBlank(AscriptionConsistencyRuleType rule) {
    assertNotNull(rule.getTitle());
    assertFalse(rule.getTitle().isBlank());
  }

  @ParameterizedTest
  @EnumSource(AscriptionConsistencyRuleType.class)
  void getDescription_returnsNonBlank(AscriptionConsistencyRuleType rule) {
    assertNotNull(rule.getDescription());
    assertFalse(rule.getDescription().isBlank());
  }
}
