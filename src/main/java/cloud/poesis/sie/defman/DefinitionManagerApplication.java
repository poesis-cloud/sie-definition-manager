package cloud.poesis.sie.defman;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Spring Boot entry point for the SIE Definition Manager service.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@OpenAPIDefinition(info = @Info(title = "SIE Definition Manager API", version = "v1", description = "Governance API for GSM definitions and ascriptions. "
        + "The `statement` payload in ascriptions is typed by "
        + "the referenced Archetype's JSON Schema \u2014 query active "
        + "Archetype ascriptions to discover statement structure."))
@SpringBootApplication
public class DefinitionManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DefinitionManagerApplication.class, args);
    }
}
