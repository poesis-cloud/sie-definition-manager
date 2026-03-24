package cloud.poesis.sie.defman.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InternalExceptionTest {

  @Test
  void singleArgConstructor_setsMessage() {
    var ex = new InternalException("something broke");
    assertEquals("something broke", ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  void twoArgConstructor_setsMessageAndCause() {
    Throwable cause = new RuntimeException("root");
    var ex = new InternalException("wrapper", cause);
    assertEquals("wrapper", ex.getMessage());
    assertEquals(cause, ex.getCause());
  }

  @Test
  void getType_returnsInternalErrorUri() {
    var ex = new InternalException("detail");
    assertEquals("gsm:exceptions/internal-error", ex.getType());
  }

  @Test
  void getTitle_returnsInternalServerError() {
    var ex = new InternalException("detail");
    assertEquals("Internal server error", ex.getTitle());
  }

  @Test
  void getExtensions_returnsEmptyMap() {
    var ex = new InternalException("detail");
    assertTrue(ex.getExtensions().isEmpty());
  }
}
