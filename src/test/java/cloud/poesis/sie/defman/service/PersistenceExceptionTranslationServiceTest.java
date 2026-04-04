package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.exception.InternalException;
import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PersistenceExceptionTranslationTest {

  @Nested
  class ExtractConstraintName {

    @Test
    void extractsFromHibernateConstraintViolationException() {
      var cve = new ConstraintViolationException("violation", null, "uq_structure_purpose");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      assertEquals(
          "uq_structure_purpose",
          PersistenceExceptionTranslationService.extractConstraintName(dive));
    }

    @Test
    void returnsNullWhenCauseIsNotConstraintViolation() {
      var dive =
          new DataIntegrityViolationException(
              "no hibernate cause", new RuntimeException("something else"));

      assertNull(PersistenceExceptionTranslationService.extractConstraintName(dive));
    }

    @Test
    void returnsNullWhenNoCause() {
      var dive = new DataIntegrityViolationException("no cause");

      assertNull(PersistenceExceptionTranslationService.extractConstraintName(dive));
    }
  }

  @Nested
  class Translate {

    @Test
    void mappedConstraint_returnsRuleViolation() {
      var cve = new ConstraintViolationException("violation", null, "uq_structure_purpose");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = PersistenceExceptionTranslationService.translate(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_PROPERTY_UNIQUENESS_ACROSS_DEFINITIONS,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void archetypeIdFkeySuffix_returnsArchetypeReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "ascription_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = PersistenceExceptionTranslationService.translate(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.ASCRIPTION_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void outputArchetypeIdFkeySuffix_returnsEffectorArchetypeReferenceIntegrity() {
      var cve =
          new ConstraintViolationException("violation", null, "effector_output_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = PersistenceExceptionTranslationService.translate(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.EFFECTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void inputArchetypeIdFkeySuffix_returnsReceptorArchetypeReferenceIntegrity() {
      var cve =
          new ConstraintViolationException("violation", null, "receptor_input_archetype_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = PersistenceExceptionTranslationService.translate(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.RECEPTOR_ARCHETYPE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void mechanismStructureIdFkey_returnsMechanismStructureReferenceIntegrity() {
      var cve = new ConstraintViolationException("violation", null, "mechanism_structure_id_fkey");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = PersistenceExceptionTranslationService.translate(dive);

      assertInstanceOf(RuleViolationException.class, result);
      assertEquals(
          AscriptionConsistencyRuleType.MECHANISM_STRUCTURE_REFERENCE_INTEGRITY,
          ((RuleViolationException) result).getRuleType());
    }

    @Test
    void unmappedConstraint_returnsInternal() {
      var cve = new ConstraintViolationException("violation", null, "some_unknown_constraint");
      var dive = new DataIntegrityViolationException("wrapped", cve);

      RuntimeException result = PersistenceExceptionTranslationService.translate(dive);

      assertInstanceOf(InternalException.class, result);
      assertTrue(result.getMessage().contains("some_unknown_constraint"));
    }

    @Test
    void noConstraintName_returnsInternal() {
      var dive = new DataIntegrityViolationException("no cause");

      RuntimeException result = PersistenceExceptionTranslationService.translate(dive);

      assertInstanceOf(InternalException.class, result);
    }
  }
}
