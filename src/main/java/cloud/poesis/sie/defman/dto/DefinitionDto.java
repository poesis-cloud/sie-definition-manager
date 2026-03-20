package cloud.poesis.sie.defman.dto;

import java.util.UUID;

import org.springframework.hateoas.server.core.Relation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response for a GSM Definition (stable identity of a governed subject).
 *
 * @param id          Definition ID (UUIDv7)
 * @param subjectType GSM structural role
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Relation(value = "definition", collectionRelation = "definitions")
@Schema(description = "Stable identity of a governed subject")
public record DefinitionDto(
        @Schema(description = "Definition ID (UUIDv7)") UUID id,
        @Schema(description = "GSM structural role (STRUCTURE, MECHANISM, EFFECTOR, RECEPTOR, INTERACTION, ARCHETYPE, NORM, DIRECTIVE)") String subjectType) {
}
