package cloud.poesis.sie.defman.dto;

import java.util.UUID;

import org.springframework.hateoas.server.core.Relation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lightweight projection of an Archetype for HAL {@code _embedded} use.
 *
 * <p>
 * Carries the Archetype's identity ({@code title} from the JSON Schema
 * document) so consumers can discriminate statement types without
 * dereferencing the archetype link.
 *
 * @param id           Archetype Ascription ID
 * @param definitionId Archetype Definition ID (stable identity)
 * @param title        the JSON Schema {@code title} field
 * @author Clément Cazaud
 * @since 0.1.0
 */
@Relation(value = "archetype", collectionRelation = "archetypes")
@Schema(description = "Embedded projection of the typing Archetype (schema identity)")
public record EmbeddedArchetypeDto(
        @Schema(description = "Archetype Ascription ID") UUID id,
        @Schema(description = "Archetype Definition ID (stable identity)") UUID definitionId,
        @Schema(description = "Archetype title — the JSON Schema 'title' field (e.g. StructureArchetype, SecurityProperties)") String title) {
}
