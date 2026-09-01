package cloud.poesis.sie.defman.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UnsupportedProtectionMeasureExceptionTest {

  @Test
  void constructor_setsMessageFromPhaseMeasureAndProperty() {
    var ex = new UnsupportedProtectionMeasureException("atRest", "encryption", "card");
    assertEquals(
        "$gsm:dataProtection.atRest.encryption on property 'card' "
            + "is not implemented by this processor",
        ex.getMessage());
  }

  @Test
  void getPropertyName_returnsProvidedProperty() {
    var ex = new UnsupportedProtectionMeasureException("inTransit", "encryption", "ssn");
    assertEquals("ssn", ex.getPropertyName());
  }

  @Test
  void getMeasure_returnsQualifiedMeasure() {
    var ex = new UnsupportedProtectionMeasureException("inTransit", "encryption", "ssn");
    assertEquals("inTransit.encryption", ex.getMeasure());
  }

  @Test
  void getType_isOutsideTheRulesNamespace() {
    var ex = new UnsupportedProtectionMeasureException("atRest", "encryption", "card");
    assertEquals("gsm:exceptions/unsupported-protection-measure", ex.getType());
  }

  @Test
  void getTitle_returnsCapabilityTitle() {
    var ex = new UnsupportedProtectionMeasureException("atRest", "encryption", "card");
    assertEquals("Protection measure not implemented", ex.getTitle());
  }

  @Test
  void getExtensions_carriesPropertyAndMeasure() {
    var ex = new UnsupportedProtectionMeasureException("atRest", "encryption", "card");
    Map<String, Object> extensions = ex.getExtensions();
    assertEquals("card", extensions.get("property"));
    assertEquals("atRest.encryption", extensions.get("measure"));
  }

  @Test
  void getExtensions_isImmutable() {
    var ex = new UnsupportedProtectionMeasureException("atRest", "encryption", "card");
    Map<String, Object> extensions = ex.getExtensions();
    assertThrows(UnsupportedOperationException.class, () -> extensions.put("x", "y"));
  }
}
