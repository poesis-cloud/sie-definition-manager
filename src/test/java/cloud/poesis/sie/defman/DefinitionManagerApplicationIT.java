package cloud.poesis.sie.defman;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Smoke test: validates context startup + Flyway migration against real PostgreSQL
 * (Testcontainers). H2 is incompatible with TABLE_PER_CLASS + NAMED_ENUM.
 */
@SpringBootTest
@ActiveProfiles("tc")
class DefinitionManagerApplicationIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registerIsolatedDatabase(registry);
  }

  @Test
  void contextLoads() {
    // Context startup validates Flyway migration + Hibernate schema validation.
  }
}
