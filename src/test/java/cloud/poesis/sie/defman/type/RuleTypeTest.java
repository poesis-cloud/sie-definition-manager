package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RuleTypeTest {

  @ParameterizedTest
  @EnumSource(RuleType.class)
  void getType_returnsNonBlank(RuleType rule) {
    assertNotNull(rule.getType());
    assertFalse(rule.getType().isBlank());
  }

  @ParameterizedTest
  @EnumSource(RuleType.class)
  void getType_startsWithGsmRulesPrefix(RuleType rule) {
    assertTrue(rule.getType().startsWith("gsm:rules/"));
  }

  @ParameterizedTest
  @EnumSource(RuleType.class)
  void getTitle_returnsNonBlank(RuleType rule) {
    assertNotNull(rule.getTitle());
    assertFalse(rule.getTitle().isBlank());
  }

  @ParameterizedTest
  @EnumSource(RuleType.class)
  void getDescription_returnsNonBlank(RuleType rule) {
    assertNotNull(rule.getDescription());
    assertFalse(rule.getDescription().isBlank());
  }
}
