package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DirectiveEntityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void constructorSetsFields() {
        DefinitionEntity def = mock(DefinitionEntity.class);
        ArchetypeEntity arch = mock(ArchetypeEntity.class);
        StructureEntity struct = mock(StructureEntity.class);
        ArchetypeEntity qualifier = mock(ArchetypeEntity.class);
        StructureEntity purpose = mock(StructureEntity.class);

        DirectiveEntity entity = new DirectiveEntity(def, arch, MAPPER.createObjectNode(), struct, qualifier, purpose);
        assertEquals(struct, entity.getStructure());
        assertEquals(qualifier, entity.getQualifier());
        assertEquals(purpose, entity.getPurpose());
    }

    @Test
    void constructorRejectsNullStructure() {
        DefinitionEntity def = mock(DefinitionEntity.class);
        ArchetypeEntity arch = mock(ArchetypeEntity.class);
        assertThrows(
                NullPointerException.class,
                () -> new DirectiveEntity(
                        def,
                        arch,
                        MAPPER.createObjectNode(),
                        null,
                        mock(ArchetypeEntity.class),
                        mock(StructureEntity.class)));
    }

    @Test
    void constructorRejectsNullQualifier() {
        DefinitionEntity def = mock(DefinitionEntity.class);
        ArchetypeEntity arch = mock(ArchetypeEntity.class);
        assertThrows(
                NullPointerException.class,
                () -> new DirectiveEntity(
                        def,
                        arch,
                        MAPPER.createObjectNode(),
                        mock(StructureEntity.class),
                        null,
                        mock(StructureEntity.class)));
    }

    @Test
    void constructorRejectsNullPurpose() {
        DefinitionEntity def = mock(DefinitionEntity.class);
        ArchetypeEntity arch = mock(ArchetypeEntity.class);
        assertThrows(
                NullPointerException.class,
                () -> new DirectiveEntity(
                        def,
                        arch,
                        MAPPER.createObjectNode(),
                        mock(StructureEntity.class),
                        mock(ArchetypeEntity.class),
                        null));
    }
}
