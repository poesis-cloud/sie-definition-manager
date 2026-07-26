package cloud.poesis.sie.defman.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CelCompilerConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(CelCompilerConfig.class);

  @Test
  void celCompiler_isRegisteredAsSpringBean() {
    contextRunner.run(
        context -> {
          assertTrue(context.containsBean("celCompiler"));
          assertNotNull(context.getBean("celCompiler"));
        });
  }
}
