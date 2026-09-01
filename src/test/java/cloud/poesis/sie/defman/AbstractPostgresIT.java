package cloud.poesis.sie.defman;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL backend for integration tests.
 *
 * <p>One container is started per JVM and every test class gets its own freshly created database
 * inside it, so container startup is paid once while data isolation between test classes is
 * preserved.
 *
 * <p>Subclasses must declare their own {@code @DynamicPropertySource} method delegating to {@link
 * #registerIsolatedDatabase(DynamicPropertyRegistry)}. Declaring it per class (rather than
 * inheriting a single method) is required: Spring keys its context cache on the set of
 * {@code @DynamicPropertySource} methods, so an inherited method would make all subclasses share
 * one application context — and therefore one database.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public abstract class AbstractPostgresIT {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.3-alpine");

  private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

  static {
    POSTGRES.start();
  }

  /**
   * Creates a dedicated database in the shared container and binds the datasource properties of the
   * calling test class to it.
   *
   * @param registry the dynamic property registry of the calling test class
   */
  protected static void registerIsolatedDatabase(DynamicPropertyRegistry registry) {
    String database = "defman_it_" + DATABASE_SEQUENCE.incrementAndGet();
    createDatabase(database);
    registry.add("spring.datasource.url", () -> jdbcUrl(database));
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  private static void createDatabase(String database) {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    } catch (SQLException e) {
      throw new IllegalStateException("Unable to create isolated test database " + database, e);
    }
  }

  private static String jdbcUrl(String database) {
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + POSTGRES.getFirstMappedPort()
        + "/"
        + database;
  }
}
