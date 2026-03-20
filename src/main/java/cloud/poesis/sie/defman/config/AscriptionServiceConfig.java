package cloud.poesis.sie.defman.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cloud.poesis.sie.defman.service.AbstractAscriptionService;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;

/**
 * Builds the service registry bean that maps each
 * {@link DefinitionSubjectType} to its {@link AbstractAscriptionService}
 * implementation. Spring auto-collects all concrete
 * {@code AbstractAscriptionService} beans via
 * {@code List<AbstractAscriptionService>}.
 *
 * @author Clément Cazaud
 * @since 0.1.0
 */
@Configuration
public class AscriptionServiceConfig {

    /**
     * Builds an immutable, exhaustive map from every {@link DefinitionSubjectType}
     * to its service implementation.
     *
     * @param services the list of all concrete {@link AbstractAscriptionService}
     *                 beans
     * @return an unmodifiable enum-keyed service registry
     * @throws IllegalStateException if a subject type is served by more than one
     *                               service or if any subject type has no service
     */
    @Bean
    public Map<DefinitionSubjectType, AbstractAscriptionService> ascriptionServiceRegistry(
            List<AbstractAscriptionService> services) {
        Map<DefinitionSubjectType, AbstractAscriptionService> map = new EnumMap<>(DefinitionSubjectType.class);
        for (AbstractAscriptionService service : services) {
            if (null != map.put(service.getSubjectType(), service)) {
                throw new IllegalStateException("Duplicate service for " + service.getSubjectType());
            }
        }
        for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
            if (!map.containsKey(type)) {
                throw new IllegalStateException("Missing service for " + type);
            }
        }
        return Map.copyOf(map);
    }
}
