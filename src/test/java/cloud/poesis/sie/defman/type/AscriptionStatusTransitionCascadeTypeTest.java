package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AscriptionStatusTransitionCascadeTypeTest {

  @ParameterizedTest
  @EnumSource(AscriptionStatusTransitionCascadeType.class)
  void valueOf_resolvesAllConstants(AscriptionStatusTransitionCascadeType type) {
    assertEquals(type, AscriptionStatusTransitionCascadeType.valueOf(type.name()));
  }

  @ParameterizedTest
  @EnumSource(AscriptionStatusTransitionCascadeType.class)
  void values_containsAllConstants(AscriptionStatusTransitionCascadeType type) {
    assertEquals(3, AscriptionStatusTransitionCascadeType.values().length);
  }
}
