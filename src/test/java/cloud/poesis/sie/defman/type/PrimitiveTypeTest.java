package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PrimitiveTypeTest {

  @ParameterizedTest
  @EnumSource(PrimitiveType.class)
  void getValue_returnsNonNull(PrimitiveType type) {
    assertNotNull(type.getValue());
    assertFalse(type.getValue().isBlank());
  }

  @ParameterizedTest
  @EnumSource(PrimitiveType.class)
  void getLabel_returnsNonNull(PrimitiveType type) {
    assertNotNull(type.getLabel());
    assertFalse(type.getLabel().isBlank());
  }

  @ParameterizedTest
  @EnumSource(PrimitiveType.class)
  void fromValue_resolvesAllConstants(PrimitiveType type) {
    assertEquals(type, PrimitiveType.fromValue(type.getValue()));
  }

  @Test
  void fromValue_caseInsensitive() {
    assertEquals(PrimitiveType.DEFINITION, PrimitiveType.fromValue("DEFINITION"));
    assertEquals(PrimitiveType.DEFINITION, PrimitiveType.fromValue("Definition"));
    assertEquals(PrimitiveType.ASCRIPTION, PrimitiveType.fromValue("ASCRIPTION"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "foo", ""})
  void fromValue_throwsOnUnknown(String value) {
    var ex = assertThrows(IllegalArgumentException.class, () -> PrimitiveType.fromValue(value));
    assertEquals("Unknown primitive_type: " + value, ex.getMessage());
  }

  @Nested
  class SpecificConstants {

    @Test
    void definition() {
      assertEquals("definition", PrimitiveType.DEFINITION.getValue());
      assertEquals("Definition", PrimitiveType.DEFINITION.getLabel());
    }

    @Test
    void ascription() {
      assertEquals("ascription", PrimitiveType.ASCRIPTION.getValue());
      assertEquals("Ascription", PrimitiveType.ASCRIPTION.getLabel());
    }

    @Test
    void ascriptionStatusTransition() {
      assertEquals(
          "ascription-status-transition", PrimitiveType.ASCRIPTION_STATUS_TRANSITION.getValue());
      assertEquals(
          "AscriptionStatusTransition", PrimitiveType.ASCRIPTION_STATUS_TRANSITION.getLabel());
    }
  }
}
