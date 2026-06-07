package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

class AopLogLevelEnvAliasBindingTest {

  @Test
  void aopLogLevelBindsFromLegacyEnvAlias() throws IOException {
    String resolved = resolveAopLogLevel(Map.of("OBSERVABILITY_AOP_LOGLEVEL", "DEBUG"));

    assertThat(resolved).isEqualTo("DEBUG");
  }

  @Test
  void aopLogLevelBindsFromUnderscoreEnvAlias() throws IOException {
    String resolved = resolveAopLogLevel(Map.of("OBSERVABILITY_AOP_LOG_LEVEL", "DEBUG"));

    assertThat(resolved).isEqualTo("DEBUG");
  }

  private static String resolveAopLogLevel(Map<String, String> envLikeValues) throws IOException {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    List<PropertySource<?>> yamlSources =
        loader.load("application", new ClassPathResource("application.yaml"));

    MutablePropertySources sources = new MutablePropertySources();
    Map<String, Object> envMap = new HashMap<>(envLikeValues);
    sources.addFirst(new MapPropertySource("test-env", envMap));
    for (PropertySource<?> source : yamlSources) {
      sources.addLast(source);
    }

    PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
    return resolver.getProperty("observability.aop.logLevel");
  }
}
