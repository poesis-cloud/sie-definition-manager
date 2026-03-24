package cloud.poesis.sie.defman.config;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.service.AbstractAscriptionService;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the service registry bean that maps each {@link DefinitionSubjectType} to its {@link
 * AbstractAscriptionService} implementation. Spring auto-collects all concrete {@code
 * AbstractAscriptionService} beans via {@code List<AbstractAscriptionService>}.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Configuration
public class AscriptionServiceConfig {

  /**
   * Builds an immutable, exhaustive map from every {@link DefinitionSubjectType} to its service
   * implementation.
   *
   * @param services the list of all concrete {@link AbstractAscriptionService} beans
   * @return an unmodifiable enum-keyed service registry
   * @throws IllegalStateException if a subject type is served by more than one service or if any
   *     subject type has no service
   */
  @Bean
  public Map<DefinitionSubjectType, AbstractAscriptionService<? extends AscriptionEntity>>
      ascriptionServiceRegistry(
          List<AbstractAscriptionService<? extends AscriptionEntity>> services) {
    Map<DefinitionSubjectType, AbstractAscriptionService<? extends AscriptionEntity>> map =
        new EnumMap<>(DefinitionSubjectType.class);
    for (AbstractAscriptionService<? extends AscriptionEntity> service : services) {
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
