package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AscriptionConsistencyRuleTypeTest {

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
