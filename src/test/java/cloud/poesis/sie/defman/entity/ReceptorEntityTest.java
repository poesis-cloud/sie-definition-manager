package cloud.poesis.sie.defman.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReceptorEntityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void constructorSetsFields() {
        DefinitionEntity def = mock(DefinitionEntity.class);
        ArchetypeEntity arch = mock(ArchetypeEntity.class);
        MechanismEntity mech = mock(MechanismEntity.class);
        ArchetypeEntity inputArch = mock(ArchetypeEntity.class);

        ReceptorEntity entity = new ReceptorEntity(def, arch, MAPPER.createObjectNode(), mech, inputArch);
        assertEquals(mech, entity.getMechanism());
        assertEquals(inputArch, entity.getInputArchetype());
    }

    @Test
    void constructorRejectsNullMechanism() {
        DefinitionEntity def = mock(DefinitionEntity.class);
        ArchetypeEntity arch = mock(ArchetypeEntity.class);
        assertThrows(
                NullPointerException.class,
                () -> new ReceptorEntity(
                        def, arch, MAPPER.createObjectNode(), null, mock(ArchetypeEntity.class)));
    }

    @Test
    void constructorRejectsNullInputArchetype() {
        DefinitionEntity def = mock(DefinitionEntity.class);
        ArchetypeEntity arch = mock(ArchetypeEntity.class);
        assertThrows(
                NullPointerException.class,
                () -> new ReceptorEntity(
                        def, arch, MAPPER.createObjectNode(), mock(MechanismEntity.class), null));
    }
}
