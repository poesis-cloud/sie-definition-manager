package cloud.poesis.sie.defman.dto;

import java.util.UUID;

import org.springframework.hateoas.server.core.Relation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lightweight projection of a Definition for HAL {@code _embedded} use.
 */
@Relation(value = "definition", collectionRelation = "definitions")
@Schema(description = "Embedded projection of the governing Definition (stable identity)")
public record EmbeddedDefinitionDto(
        UUID id,
        String subjectType) {
}
