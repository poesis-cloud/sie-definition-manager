package cloud.poesis.sie.defman.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AscriptionCreationDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void constructorAndGetters() {
        UUID archetypeId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        ObjectNode stmt = MAPPER.createObjectNode().put("purpose", "test");
        AscriptionCreationDto dto = new AscriptionCreationDto(archetypeId, stmt, definitionId);

        assertEquals(archetypeId, dto.getArchetypeId());
        assertEquals("test", dto.getStatement().get("purpose").asText());
        assertEquals(definitionId, dto.getDefinitionId());
    }

    @Test
    void nullDefinitionId() {
        UUID archetypeId = UUID.randomUUID();
        ObjectNode stmt = MAPPER.createObjectNode();
        AscriptionCreationDto dto = new AscriptionCreationDto(archetypeId, stmt, null);

        assertNull(dto.getDefinitionId());
        assertEquals(archetypeId, dto.getArchetypeId());
    }
}
