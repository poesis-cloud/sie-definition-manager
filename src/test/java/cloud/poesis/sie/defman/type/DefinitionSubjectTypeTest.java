package cloud.poesis.sie.defman.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DefinitionSubjectTypeTest {

    @ParameterizedTest
    @EnumSource(DefinitionSubjectType.class)
    void getPrimitiveType_neverNull(DefinitionSubjectType type) {
        assertNotNull(type.getPrimitiveType());
    }

    @ParameterizedTest
    @EnumSource(DefinitionSubjectType.class)
    void getValue_matchesPrimitiveType(DefinitionSubjectType type) {
        assertEquals(type.getPrimitiveType().getValue(), type.getValue());
    }

    @ParameterizedTest
    @EnumSource(DefinitionSubjectType.class)
    void fromValue_resolvesAllConstants(DefinitionSubjectType type) {
        assertEquals(type, DefinitionSubjectType.fromValue(type.getValue()));
    }

    @Nested
    class FromValueEdgeCases {

        @Test
        void resolvesArchetype() {
            assertEquals(DefinitionSubjectType.ARCHETYPE, DefinitionSubjectType.fromValue("archetype"));
        }

        @Test
        void resolvesNorm() {
            assertEquals(DefinitionSubjectType.NORM, DefinitionSubjectType.fromValue("norm"));
        }

        @Test
        void throwsForDefinition_primitivePresentButNoSubjectType() {
            // "definition" resolves as PrimitiveType but has no DefinitionSubjectType
            var ex = assertThrows(
                    IllegalArgumentException.class, () -> DefinitionSubjectType.fromValue("definition"));
            assertEquals("Unknown definition_subject_type: definition", ex.getMessage());
        }

        @Test
        void throwsForAscription_primitivePresentButNoSubjectType() {
            var ex = assertThrows(
                    IllegalArgumentException.class, () -> DefinitionSubjectType.fromValue("ascription"));
            assertEquals("Unknown definition_subject_type: ascription", ex.getMessage());
        }

        @Test
        void throwsForAscriptionStatusTransition() {
            var ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> DefinitionSubjectType.fromValue("ascription-status-transition"));
            assertEquals(
                    "Unknown definition_subject_type: ascription-status-transition", ex.getMessage());
        }

        @Test
        void throwsForUnknown() {
            assertThrows(IllegalArgumentException.class, () -> DefinitionSubjectType.fromValue("bogus"));
        }
    }
}
