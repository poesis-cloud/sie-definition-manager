package cloud.poesis.sie.defman.testbeans;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

/**
 * Test-only {@code @Repository} bean.
 *
 * <p>Used by {@code BroadInstrumentationAspectIT} to verify that the broad pointcut wraps a
 * {@code @Repository} stereotype method (AC-8 structural shape).
 *
 * <p>The companion agent-emitted JDBC {@code CLIENT} child span (the {@code db.*} span that would
 * be the AOP span's child in the real trace tree) is NOT asserted here: the OTel Java agent is a
 * runtime agent, not a test-classpath dependency. The end-to-end JDBC trace shape is verified by
 * S-015 at deployment. This bean intentionally keeps state in-memory so the test does not depend on
 * schema fixtures.
 */
@Repository
public class AopProbeRepository {

  private final ConcurrentMap<String, String> store = new ConcurrentHashMap<>();

  public String findByKey(String key) {
    return store.get(key);
  }
}
