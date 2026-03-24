package cloud.poesis.sie.defman.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cloud.poesis.sie.defman.type.PrimitiveType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceNotFoundExceptionTest {

  @Test
  void constructor_setsMessageFromTypeAndId() {
    UUID id = UUID.randomUUID();
    var ex = new ResourceNotFoundException(PrimitiveType.DEFINITION, id);
    assertEquals("Definition " + id + " not found", ex.getMessage());
  }

  @Test
  void getResourceType_returnsProvidedType() {
    var ex = new ResourceNotFoundException(PrimitiveType.ASCRIPTION, UUID.randomUUID());
    assertEquals(PrimitiveType.ASCRIPTION, ex.getResourceType());
  }

  @Test
  void getResourceId_returnsProvidedId() {
    UUID id = UUID.randomUUID();
    var ex = new ResourceNotFoundException(PrimitiveType.MECHANISM, id);
    assertEquals(id, ex.getResourceId());
  }

  @Test
  void getType_returnsResourceNotFoundUri() {
    var ex = new ResourceNotFoundException(PrimitiveType.STRUCTURE, UUID.randomUUID());
    assertEquals("gsm:exceptions/resource-not-found", ex.getType());
  }

  @Test
  void getTitle_returnsNotFound() {
    var ex = new ResourceNotFoundException(PrimitiveType.ARCHETYPE, UUID.randomUUID());
    assertEquals("Not found", ex.getTitle());
  }

  @Test
  void getExtensions_containsResourceTypeLabel() {
    UUID id = UUID.randomUUID();
    var ex = new ResourceNotFoundException(PrimitiveType.NORM, id);
    Map<String, Object> ext = ex.getExtensions();
    assertEquals("Norm", ext.get("resourceType"));
    assertEquals(id, ext.get("resourceId"));
  }

  @Test
  void getExtensions_nullResourceId_omitsKey() {
    var ex = new ResourceNotFoundException(PrimitiveType.EFFECTOR, null);
    Map<String, Object> ext = ex.getExtensions();
    assertEquals("Effector", ext.get("resourceType"));
    assertNull(ext.get("resourceId"));
  }

  @Test
  void getExtensions_isImmutable() {
    var ex = new ResourceNotFoundException(PrimitiveType.RECEPTOR, UUID.randomUUID());
    assertThrows(
        UnsupportedOperationException.class, () -> ex.getExtensions().put("newKey", "newVal"));
  }
}
