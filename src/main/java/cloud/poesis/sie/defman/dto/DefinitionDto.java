package cloud.poesis.sie.defman.dto;

import java.util.UUID;

import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response for a GSM Definition (stable identity of a governed subject).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Schema(name = "Definition", description = "Stable identity of a governed subject")
public class DefinitionDto {

    @Schema(description = "Definition ID (UUIDv7)")
    private final UUID id;

    @Schema(description = "GSM structural role")
    private final DefinitionSubjectType subjectType;

    public DefinitionDto(UUID id, DefinitionSubjectType subjectType) {
        this.id = id;
        this.subjectType = subjectType;
    }

    public UUID getId() {
        return id;
    }

    public DefinitionSubjectType getSubjectType() {
        return subjectType;
    }
}
