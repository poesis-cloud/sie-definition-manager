package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Structural compile-time-equivalent guard for ADR-002 D-8 / S-004b AC-2.
 *
 * <p>Spring AOP is proxy-based: {@code final} classes and {@code public final} methods on
 * stereotype beans are <em>silently</em> skipped by the broad instrumentation pointcut. The
 * "no-opt-out" invariant (ADR-002) must therefore be preserved structurally rather than degraded
 * silently at runtime.
 *
 * <p>This test enumerates every {@code @Component / @Service / @Repository / @Controller /
 * @RestController / @Configuration} class under {@code cloud.poesis.sie.defman.**} (excluding the
 * observability package — the aspect self-excludes there) and fails the build if any such class is
 * {@code final} or declares a {@code public final} method.
 */
class FinalStereotypeMethodTest {

  private static final String BASE_PACKAGE = "cloud.poesis.sie.defman";
  private static final String OBSERVABILITY_PACKAGE = "cloud.poesis.sie.defman.observability";

  private static final List<Class<? extends java.lang.annotation.Annotation>> STEREOTYPES =
      List.of(
          org.springframework.stereotype.Component.class,
          org.springframework.stereotype.Service.class,
          org.springframework.stereotype.Repository.class,
          org.springframework.stereotype.Controller.class,
          org.springframework.web.bind.annotation.RestController.class,
          org.springframework.context.annotation.Configuration.class);

  @Test
  @DisplayName("No Defman stereotype class is final and no public method on one is final")
  void noFinalStereotypeClassesOrMethods() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    for (Class<? extends java.lang.annotation.Annotation> stereotype : STEREOTYPES) {
      scanner.addIncludeFilter(new AnnotationTypeFilter(stereotype));
    }

    List<String> finalClassViolations = new ArrayList<>();
    List<String> finalMethodViolations = new ArrayList<>();

    var beanDefinitions = scanner.findCandidateComponents(BASE_PACKAGE);
    assertThat(beanDefinitions)
        .as(
            "scanner must find at least one Defman stereotype bean (sanity check; if 0,"
                + " the scan is mis-configured)")
        .isNotEmpty();

    for (var bd : beanDefinitions) {
      String className = bd.getBeanClassName();
      if (className == null) {
        continue;
      }
      if (className.startsWith(OBSERVABILITY_PACKAGE + ".")) {
        // The aspect itself (and helpers) live here; the pointcut self-excludes them.
        continue;
      }

      Class<?> clazz = Class.forName(className);

      if (Modifier.isFinal(clazz.getModifiers())) {
        finalClassViolations.add(className);
      }

      for (Method m : clazz.getDeclaredMethods()) {
        int mods = m.getModifiers();
        if (Modifier.isPublic(mods) && Modifier.isFinal(mods) && !Modifier.isStatic(mods)) {
          finalMethodViolations.add(className + "#" + m.getName());
        }
      }
    }

    assertThat(finalClassViolations)
        .as(
            "ADR-002 D-8 / S-004b AC-2: stereotype classes under %s MUST NOT be final. "
                + "Spring AOP cannot proxy final classes; the broad-instrumentation "
                + "pointcut would silently skip them, breaking the no-opt-out invariant.",
            BASE_PACKAGE)
        .isEmpty();

    assertThat(finalMethodViolations)
        .as(
            "ADR-002 D-8 / S-004b AC-2: public methods on stereotype classes under %s MUST"
                + " NOT be final. Spring AOP cannot proxy final methods; the broad-"
                + "instrumentation pointcut would silently skip them, breaking the "
                + "no-opt-out invariant.",
            BASE_PACKAGE)
        .isEmpty();
  }
}
