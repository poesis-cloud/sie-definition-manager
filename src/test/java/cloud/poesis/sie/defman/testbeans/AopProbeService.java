package cloud.poesis.sie.defman.testbeans;

import org.springframework.stereotype.Service;

/**
 * Test-only {@code @Service} bean for {@code BroadInstrumentationAspect} integration tests.
 *
 * <p>Lives under {@code cloud.poesis.sie.defman.**} (so the broad pointcut catches it) but outside
 * {@code cloud.poesis.sie.defman.observability.**} (which the aspect self-excludes).
 */
@Service
public class AopProbeService {

  /** No-op no-arg method; used to verify AOP wrapping fires. */
  public String greet() {
    return "hello";
  }

  /** Method that throws; exercises the AOP exception path. */
  public String boom() {
    throw new IllegalStateException("AopProbeService boom");
  }

  /** Method with one arg; used to verify {@code sie.aop.args.summary} never echoes values. */
  public String echo(Object input) {
    return input == null ? "null" : input.getClass().getSimpleName();
  }
}
