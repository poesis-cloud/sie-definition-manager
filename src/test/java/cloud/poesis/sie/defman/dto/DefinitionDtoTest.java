package cloud.poesis.sie.defman.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefinitionDtoTest {

    @Test
    void constructorAndGetters() {
        UUID id = UUID.randomUUID();
        DefinitionDto dto = new DefinitionDto(id, DefinitionSubjectType.ARCHETYPE);

        assertEquals(id, dto.getId());
        assertEquals(DefinitionSubjectType.ARCHETYPE, dto.getSubjectType());
    }
}
