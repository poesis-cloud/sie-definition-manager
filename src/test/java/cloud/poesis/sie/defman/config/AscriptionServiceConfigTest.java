package cloud.poesis.sie.defman.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.service.AbstractAscriptionService;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AscriptionServiceConfigTest {

  private AscriptionServiceConfig config;

  @BeforeEach
  void setUp() {
    config = new AscriptionServiceConfig();
  }

  private static AbstractAscriptionService<? extends AscriptionEntity> mockService(
      DefinitionSubjectType type) {
    @SuppressWarnings("unchecked")
    AbstractAscriptionService<AscriptionEntity> svc = mock(AbstractAscriptionService.class);
    when(svc.getSubjectType()).thenReturn(type);
    return svc;
  }

  private static List<AbstractAscriptionService<? extends AscriptionEntity>> allServices() {
    List<AbstractAscriptionService<? extends AscriptionEntity>> list = new ArrayList<>();
    for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
      list.add(mockService(type));
    }
    return list;
  }

  @Nested
  class HappyPath {

    @Test
    void registryContainsAllSubjectTypes() {
      Map<DefinitionSubjectType, AbstractAscriptionService<? extends AscriptionEntity>> registry =
          config.ascriptionServiceRegistry(allServices());

      assertThat(registry).hasSize(DefinitionSubjectType.values().length);
      for (DefinitionSubjectType type : DefinitionSubjectType.values()) {
        assertThat(registry).containsKey(type);
        assertThat(registry.get(type).getSubjectType()).isEqualTo(type);
      }
    }

    @Test
    void registryIsUnmodifiable() {
      Map<DefinitionSubjectType, AbstractAscriptionService<? extends AscriptionEntity>> registry =
          config.ascriptionServiceRegistry(allServices());

      assertThat(registry).isUnmodifiable();
    }
  }

  @Nested
  class DuplicateService {

    @Test
    void throwsOnDuplicateSubjectType() {
      List<AbstractAscriptionService<? extends AscriptionEntity>> services = allServices();
      services.add(mockService(DefinitionSubjectType.STRUCTURE));

      assertThatIllegalStateException()
          .isThrownBy(() -> config.ascriptionServiceRegistry(services))
          .withMessageContaining("Duplicate service for STRUCTURE");
    }
  }

  @Nested
  class MissingService {

    @Test
    void throwsWhenSubjectTypeHasNoService() {
      List<AbstractAscriptionService<? extends AscriptionEntity>> services = allServices();
      services.removeIf(s -> s.getSubjectType() == DefinitionSubjectType.NORM);

      assertThatIllegalStateException()
          .isThrownBy(() -> config.ascriptionServiceRegistry(services))
          .withMessageContaining("Missing service for NORM");
    }
  }
}
