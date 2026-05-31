package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * Slice context test for {@link TenantMdcFilterConfiguration}.
 *
 * <p>Uses {@link ApplicationContextRunner} so we boot only the configuration under test (no full
 * Spring Boot context, no database, no Kafka). Asserts the {@link FilterRegistrationBean} is
 * present, named correctly, pinned to {@link Ordered#HIGHEST_PRECEDENCE}, and bound to {@code /*}.
 */
class TenantMdcFilterConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of())
          .withUserConfiguration(TenantMdcFilterConfiguration.class);

  @Test
  @DisplayName("registration bean is present, named, ordered HIGHEST_PRECEDENCE, bound to /*")
  void registrationBeanProperties() {
    contextRunner.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(TenantMdcFilter.class);

          @SuppressWarnings("unchecked")
          FilterRegistrationBean<TenantMdcFilter> reg =
              ctx.getBean("tenantMdcFilterRegistration", FilterRegistrationBean.class);

          assertThat(reg.getFilter()).isInstanceOf(TenantMdcFilter.class);
          assertThat(reg.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
          assertThat(reg.getUrlPatterns()).containsExactly("/*");
          // Bean name surfaced in actuator/filters endpoint
          assertThat(reg.getFilterName()).isEqualTo(TenantMdcFilterConfiguration.FILTER_NAME);
        });
  }
}
