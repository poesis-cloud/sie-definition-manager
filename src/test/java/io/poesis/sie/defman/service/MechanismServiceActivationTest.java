package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.MechanismEntity;
import io.poesis.sie.defman.entity.StructureEntity;
import io.poesis.sie.defman.repository.EffectorRepository;
import io.poesis.sie.defman.repository.MechanismRepository;
import io.poesis.sie.defman.repository.ReceptorRepository;
import io.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType;
import io.poesis.sie.defman.type.AscriptionStatusType;
import io.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Tests Mechanism activation uniqueness (function uniqueness within Structure)
 * and Mechanism identity-bound values.
 * Complements MechanismServiceModeTest (mode validation) and
 * MechanismServiceStarlarkTest (Starlark rule validation).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MechanismServiceActivationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private MechanismRepository mechanismRepo;

    @Mock
    private EffectorRepository effectorRepo;

    @Mock
    private ReceptorRepository receptorRepo;

    private MechanismService service;

    @BeforeEach
    void setUp() {
        service = new MechanismService(
                mechanismRepo,
                mock(StructureService.class),
                mock(ArchetypeService.class),
                effectorRepo,
                receptorRepo);
    }

    // ========================================================================
    // Activation uniqueness: function (GSM Mechanism validation rules)
    // ========================================================================

    @Nested
    class FunctionUniqueness {

        @Test
        void uniqueFunction_valid() {
            UUID structureDefId = UUID.randomUUID();
            UUID thisDefId = UUID.randomUUID();
            MechanismEntity entity = stubMechanism("UserValidation", structureDefId, thisDefId);

            // Generative mode — stub effector/receptor repos for mode check
            stubGenerativeModeValid(thisDefId);

            when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                    structureDefId,
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
        }

        @Test
        void duplicateFunction_differentDefinition_rejected() {
            UUID structureDefId = UUID.randomUUID();
            UUID thisDefId = UUID.randomUUID();
            UUID otherDefId = UUID.randomUUID();

            MechanismEntity entity = stubMechanism("UserValidation", structureDefId, thisDefId);
            MechanismEntity existing = stubMechanism("UserValidation", structureDefId, otherDefId);

            stubGenerativeModeValid(thisDefId);

            when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                    structureDefId,
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of(existing));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateActivationUniqueness(entity));
            assertTrue(ex.getMessage().contains("UserValidation"));
            assertTrue(ex.getMessage().contains("duplicates"));
        }

        @Test
        void sameFunction_sameDefinition_valid() {
            UUID structureDefId = UUID.randomUUID();
            UUID defId = UUID.randomUUID();

            MechanismEntity entity = stubMechanism("UserValidation", structureDefId, defId);
            MechanismEntity existing = stubMechanism("UserValidation", structureDefId, defId);

            stubGenerativeModeValid(defId);

            when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                    structureDefId,
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of(existing));

            assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
        }

        @Test
        void emptyFunction_rejected() {
            UUID structureDefId = UUID.randomUUID();
            UUID thisDefId = UUID.randomUUID();
            MechanismEntity entity = stubMechanism("", structureDefId, thisDefId);

            stubGenerativeModeValid(thisDefId);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateActivationUniqueness(entity));
            assertTrue(ex.getMessage().contains("must not be empty"));
        }

        @Test
        void differentFunction_valid() {
            UUID structureDefId = UUID.randomUUID();
            UUID thisDefId = UUID.randomUUID();
            UUID otherDefId = UUID.randomUUID();

            MechanismEntity entity = stubMechanism("UserValidation", structureDefId, thisDefId);
            MechanismEntity existing = stubMechanism("PaymentRouting", structureDefId, otherDefId);

            stubGenerativeModeValid(thisDefId);

            when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                    structureDefId,
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of(existing));

            assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
        }

        @Test
        void differentStructure_sameFunctionAllowed() {
            UUID structureDefId1 = UUID.randomUUID();
            UUID thisDefId = UUID.randomUUID();

            MechanismEntity entity = stubMechanism("UserValidation", structureDefId1, thisDefId);

            stubGenerativeModeValid(thisDefId);

            // No siblings in the same structure
            when(mechanismRepo.findAllByStructureDefinitionIdAndStatusIn(
                    structureDefId1,
                    List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED)))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateActivationUniqueness(entity));
        }
    }

    // ========================================================================
    // Lifecycle descriptors (identity-bound, referee, cascade)
    // ========================================================================

    @Nested
    class LifecycleDescriptors {

        @Test
        void identityBound_structureAndFunction() {
            UUID structureDefId = UUID.randomUUID();
            UUID defId = UUID.randomUUID();
            MechanismEntity entity = stubMechanism("UserValidation", structureDefId, defId);

            var values = service.getIdentityBoundValues(entity);

            assertEquals(structureDefId, values.get("structure"));
            assertEquals("UserValidation", values.get("function"));
        }

        @Test
        void refereeReferences_structure() {
            UUID structureDefId = UUID.randomUUID();
            UUID defId = UUID.randomUUID();
            MechanismEntity entity = stubMechanism("UserValidation", structureDefId, defId);

            var refs = service.getRefereeReferences(entity);

            assertEquals(1, refs.size());
            assertEquals("structure", refs.get(0).label());
        }

        @Test
        void cascadeRoles_governingFromStructure() {
            var roles = service.getCascadeTargetRoles();

            assertEquals(1, roles.size());
            assertTrue(roles.containsKey(DefinitionSubjectType.STRUCTURE));
            assertEquals(AscriptionStatusTransitionCascadeType.GOVERNING,
                    roles.get(DefinitionSubjectType.STRUCTURE));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void stubGenerativeModeValid(UUID mechanismDefId) {
        // Generative mode: has rule → skip port check
        // Nothing specific to mock beyond the statement having a rule
    }

    private MechanismEntity stubMechanism(String function, UUID structureDefId, UUID defId) {
        DefinitionEntity def = mock(DefinitionEntity.class);
        when(def.getId()).thenReturn(defId);

        DefinitionEntity structureDef = mock(DefinitionEntity.class);
        when(structureDef.getId()).thenReturn(structureDefId);

        StructureEntity structure = mock(StructureEntity.class);
        when(structure.getDefinition()).thenReturn(structureDef);

        ObjectNode stmt = MAPPER.createObjectNode();
        stmt.put("function", function);
        // Add a rule to satisfy generative mode (avoids port check)
        stmt.put("rule", "on(\"X\")\nsys.emit(\"Y\", {})");

        MechanismEntity entity = mock(MechanismEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getDefinition()).thenReturn(def);
        when(entity.getStatement()).thenReturn(stmt);
        when(entity.getStructure()).thenReturn(structure);

        return entity;
    }
}
