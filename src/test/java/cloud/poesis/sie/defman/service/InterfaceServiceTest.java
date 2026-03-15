package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.entity.StructureEntity;
import cloud.poesis.sie.defman.repository.InterfaceRepository;

/**
 * Tests Interface-specific validation rules:
 * - Effector/Receptor must belong to Mechanisms within the same Structure
 * (GSM Interface boundary validation)
 * - Referee references and identity-bound values
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterfaceServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private StructureService structureService;

    @Mock
    private EffectorService effectorService;

    @Mock
    private ReceptorService receptorService;

    private InterfaceService service;

    @BeforeEach
    void setUp() {
        service = new InterfaceService(
                mock(InterfaceRepository.class),
                structureService,
                effectorService,
                receptorService);
    }

    // ========================================================================
    // Boundary validation (GSM Interface validation rules)
    // ========================================================================

    @Nested
    class BoundaryValidation {

        @Test
        void effectorInSameStructure_valid() {
            UUID structureDefId = UUID.randomUUID();
            UUID structureId = UUID.randomUUID();
            UUID effectorId = UUID.randomUUID();

            StructureEntity structure = stubStructure(structureId, structureDefId);
            when(structureService.findEntityById(structureId)).thenReturn(structure);

            EffectorEntity effector = stubEffectorInStructure(effectorId, structureDefId);
            when(effectorService.findEntityById(effectorId)).thenReturn(effector);

            ObjectNode statement = MAPPER.createObjectNode();
            statement.put("structure", structureId.toString());
            ArrayNode effectors = statement.putArray("effectorIds");
            effectors.add(effectorId.toString());
            statement.putArray("receptorIds");

            DefinitionEntity definition = mock(DefinitionEntity.class);
            ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

            assertNotNull(service.buildEntity(definition, archetypeRef, statement));
        }

        @Test
        void effectorInDifferentStructure_rejected() {
            UUID structureDefId = UUID.randomUUID();
            UUID otherStructureDefId = UUID.randomUUID();
            UUID structureId = UUID.randomUUID();
            UUID effectorId = UUID.randomUUID();

            StructureEntity structure = stubStructure(structureId, structureDefId);
            when(structureService.findEntityById(structureId)).thenReturn(structure);

            EffectorEntity effector = stubEffectorInStructure(effectorId, otherStructureDefId);
            when(effectorService.findEntityById(effectorId)).thenReturn(effector);

            ObjectNode statement = MAPPER.createObjectNode();
            statement.put("structure", structureId.toString());
            ArrayNode effectors = statement.putArray("effectorIds");
            effectors.add(effectorId.toString());
            statement.putArray("receptorIds");

            DefinitionEntity definition = mock(DefinitionEntity.class);
            ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.buildEntity(definition, archetypeRef, statement));
            assertTrue(ex.getMessage().contains("boundary violation"));
        }

        @Test
        void receptorInDifferentStructure_rejected() {
            UUID structureDefId = UUID.randomUUID();
            UUID otherStructureDefId = UUID.randomUUID();
            UUID structureId = UUID.randomUUID();
            UUID receptorId = UUID.randomUUID();

            StructureEntity structure = stubStructure(structureId, structureDefId);
            when(structureService.findEntityById(structureId)).thenReturn(structure);

            ReceptorEntity receptor = stubReceptorInStructure(receptorId, otherStructureDefId);
            when(receptorService.findEntityById(receptorId)).thenReturn(receptor);

            ObjectNode statement = MAPPER.createObjectNode();
            statement.put("structure", structureId.toString());
            statement.putArray("effectorIds");
            ArrayNode receptors = statement.putArray("receptorIds");
            receptors.add(receptorId.toString());

            DefinitionEntity definition = mock(DefinitionEntity.class);
            ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.buildEntity(definition, archetypeRef, statement));
            assertTrue(ex.getMessage().contains("boundary violation"));
        }

        @Test
        void emptyPorts_valid() {
            UUID structureDefId = UUID.randomUUID();
            UUID structureId = UUID.randomUUID();

            StructureEntity structure = stubStructure(structureId, structureDefId);
            when(structureService.findEntityById(structureId)).thenReturn(structure);

            ObjectNode statement = MAPPER.createObjectNode();
            statement.put("structure", structureId.toString());
            statement.putArray("effectorIds");
            statement.putArray("receptorIds");

            DefinitionEntity definition = mock(DefinitionEntity.class);
            ArchetypeEntity archetypeRef = mock(ArchetypeEntity.class);

            assertNotNull(service.buildEntity(definition, archetypeRef, statement));
        }
    }

    // ========================================================================
    // Referee references
    // ========================================================================

    @Nested
    class RefereeReferences {

        @Test
        void referencesStructureAndPorts() {
            StructureEntity structure = mock(StructureEntity.class);
            EffectorEntity effector = mock(EffectorEntity.class);
            ReceptorEntity receptor = mock(ReceptorEntity.class);

            var entity = mock(cloud.poesis.sie.defman.entity.InterfaceEntity.class);
            when(entity.getStructure()).thenReturn(structure);
            when(entity.getEffectors()).thenReturn(java.util.List.of(effector));
            when(entity.getReceptors()).thenReturn(java.util.List.of(receptor));

            var refs = service.getRefereeReferences(entity);

            // structure + 1 effector + 1 receptor = 3
            assertTrue(refs.size() == 3);
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("structure")));
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("effector")));
            assertTrue(refs.stream().anyMatch(r -> r.label().equals("receptor")));
        }
    }

    // ========================================================================
    // Cascade target roles
    // ========================================================================

    @Nested
    class CascadeRoles {

        @Test
        void hasGoverningFromStructureAndDependentFromPorts() {
            var roles = service.getCascadeTargetRoles();

            assertTrue(roles.containsKey(
                    cloud.poesis.sie.defman.type.DefinitionSubjectType.STRUCTURE));
            assertTrue(roles.containsKey(
                    cloud.poesis.sie.defman.type.DefinitionSubjectType.EFFECTOR));
            assertTrue(roles.containsKey(
                    cloud.poesis.sie.defman.type.DefinitionSubjectType.RECEPTOR));

            assertTrue(roles.get(
                    cloud.poesis.sie.defman.type.DefinitionSubjectType.STRUCTURE) == cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType.GOVERNING);
            assertTrue(roles.get(
                    cloud.poesis.sie.defman.type.DefinitionSubjectType.EFFECTOR) == cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType.DEPENDENT);
            assertTrue(roles.get(
                    cloud.poesis.sie.defman.type.DefinitionSubjectType.RECEPTOR) == cloud.poesis.sie.defman.type.AscriptionStatusTransitionCascadeType.DEPENDENT);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private StructureEntity stubStructure(UUID structureId, UUID structureDefId) {
        DefinitionEntity structureDef = mock(DefinitionEntity.class);
        when(structureDef.getId()).thenReturn(structureDefId);

        StructureEntity structure = mock(StructureEntity.class);
        when(structure.getId()).thenReturn(structureId);
        when(structure.getDefinition()).thenReturn(structureDef);

        return structure;
    }

    private EffectorEntity stubEffectorInStructure(UUID effectorId, UUID structureDefId) {
        DefinitionEntity structureDef = mock(DefinitionEntity.class);
        when(structureDef.getId()).thenReturn(structureDefId);

        StructureEntity structure = mock(StructureEntity.class);
        when(structure.getDefinition()).thenReturn(structureDef);

        MechanismEntity mechanism = mock(MechanismEntity.class);
        when(mechanism.getStructure()).thenReturn(structure);

        EffectorEntity effector = mock(EffectorEntity.class);
        when(effector.getId()).thenReturn(effectorId);
        when(effector.getMechanism()).thenReturn(mechanism);

        return effector;
    }

    private ReceptorEntity stubReceptorInStructure(UUID receptorId, UUID structureDefId) {
        DefinitionEntity structureDef = mock(DefinitionEntity.class);
        when(structureDef.getId()).thenReturn(structureDefId);

        StructureEntity structure = mock(StructureEntity.class);
        when(structure.getDefinition()).thenReturn(structureDef);

        MechanismEntity mechanism = mock(MechanismEntity.class);
        when(mechanism.getStructure()).thenReturn(structure);

        ReceptorEntity receptor = mock(ReceptorEntity.class);
        when(receptor.getId()).thenReturn(receptorId);
        when(receptor.getMechanism()).thenReturn(mechanism);

        return receptor;
    }
}
