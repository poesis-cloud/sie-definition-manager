package cloud.poesis.sie.defman.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SIE Definition Manager API")
                        .version("v1")
                        .description(
                                "Governance API for GSM definitions and ascriptions. "
                                        + "The `statement` payload in ascriptions is typed by "
                                        + "the referenced Archetype's JSON Schema — query active "
                                        + "Archetype ascriptions to discover statement structure."));
    }
}
