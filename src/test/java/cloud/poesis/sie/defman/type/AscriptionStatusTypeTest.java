package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AscriptionStatusTypeTest {

  @ParameterizedTest
  @EnumSource(AscriptionStatusType.class)
  void valueOf_resolvesAllConstants(AscriptionStatusType type) {
    assertEquals(type, AscriptionStatusType.valueOf(type.name()));
  }

  @ParameterizedTest
  @EnumSource(AscriptionStatusType.class)
  void values_containsAllConstants(AscriptionStatusType type) {
    assertEquals(9, AscriptionStatusType.values().length);
  }
}
