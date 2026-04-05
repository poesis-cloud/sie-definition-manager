package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cloud.poesis.sie.defman.type.AscriptionStatusType;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AscriptionStatusTransitionEntityTest {

  @Test
  void constructorSetsFields() {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    AscriptionStatusTransitionEntity entity =
        new AscriptionStatusTransitionEntity(
            asc, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);
    assertEquals(asc, entity.getAscription());
    assertEquals(AscriptionStatusType.DRAFT, entity.getPreStatus());
    assertEquals(AscriptionStatusType.PROPOSED, entity.getPostStatus());
  }

  @Test
  void constructorRejectsNullPreStatus() {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    assertThrows(
        NullPointerException.class,
        () -> new AscriptionStatusTransitionEntity(asc, null, AscriptionStatusType.DRAFT));
  }

  @Test
  void constructorRejectsNullAscription() {
    assertThrows(
        NullPointerException.class,
        () ->
            new AscriptionStatusTransitionEntity(
                null, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED));
  }

  @Test
  void constructorRejectsNullPostStatus() {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    assertThrows(
        NullPointerException.class,
        () -> new AscriptionStatusTransitionEntity(asc, AscriptionStatusType.DRAFT, null));
  }

  @Test
  void equalsAndHashCode_sameId() throws Exception {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    AscriptionStatusTransitionEntity a =
        new AscriptionStatusTransitionEntity(
            asc, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);
    AscriptionStatusTransitionEntity b =
        new AscriptionStatusTransitionEntity(
            asc, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);
    UUID id = UUID.randomUUID();
    Field idField = AscriptionStatusTransitionEntity.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(a, id);
    idField.set(b, id);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void equals_bothNullId_notEqual() {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    AscriptionStatusTransitionEntity a =
        new AscriptionStatusTransitionEntity(
            asc, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);
    AscriptionStatusTransitionEntity b =
        new AscriptionStatusTransitionEntity(
            asc, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);
    assertFalse(a.equals(b));
  }

  @Test
  void equals_sameInstance() {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    AscriptionStatusTransitionEntity a =
        new AscriptionStatusTransitionEntity(
            asc, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);
    assertTrue(a.equals(a));
  }

  @Test
  void equals_null() {
    AscriptionEntity asc = mock(AscriptionEntity.class);
    AscriptionStatusTransitionEntity a =
        new AscriptionStatusTransitionEntity(
            asc, AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED);
    assertFalse(a.equals(null));
  }
}
