package cloud.poesis.sie.defman.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class SecurityConfigTest {

  private static final ObjectPostProcessor<Object> OBJECT_POST_PROCESSOR =
      new ObjectPostProcessor<>() {
        @Override
        public <O> O postProcess(O object) {
          return object;
        }
      };

  @Test
  void securityFilterChain_buildsForDesignPhaseDefaults() throws Exception {
    SecurityConfig config = new SecurityConfig();
    GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.registerBean(JwtDecoder.class, () -> mock(JwtDecoder.class));
    applicationContext.refresh();
    Map<Class<?>, Object> sharedObjects = new HashMap<>();
    sharedObjects.put(ApplicationContext.class, applicationContext);
    HttpSecurity http =
        new HttpSecurity(
            OBJECT_POST_PROCESSOR,
            new AuthenticationManagerBuilder(OBJECT_POST_PROCESSOR),
            sharedObjects);

    assertNotNull(config.securityFilterChain(http, false));
  }
}