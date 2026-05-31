package cloud.poesis.sie.defman.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Spring configuration that registers {@link TenantMdcFilter} at {@link Ordered#HIGHEST_PRECEDENCE}
 * and binds it to all URL patterns.
 *
 * <p><strong>Filter ordering rationale (HUDDLE §1)</strong>: pinning to {@code HIGHEST_PRECEDENCE}
 * ensures the filter runs before the Spring Security filter chain (default order {@code -100}), so
 * unauthenticated requests that Spring Security rejects with 401/403 still receive correlated MDC
 * and span attributes for observability (AC-2 spirit). If a future Story adds another filter at the
 * same precedence, registration order becomes well-defined but fragile — flag in that Story's PR
 * description.
 *
 * <p>The registration uses {@link FilterRegistrationBean} (rather than relying on auto-registration
 * of the bare {@code @Component} filter) so the URL pattern is pinned to {@code /*} explicitly and
 * the precedence is set deterministically. The filter bean itself is declared here (not via
 * {@code @Component} on {@link TenantMdcFilter}) to avoid Spring Boot also auto-registering it,
 * which would yield two filter chain entries.
 */
@Configuration
public class TenantMdcFilterConfiguration {

  /** Logical bean name used by Spring's filter registry and surfaced in actuator endpoints. */
  static final String FILTER_NAME = "tenantMdcFilter";

  @Bean
  public TenantMdcFilter tenantMdcFilter() {
    return new TenantMdcFilter();
  }

  @Bean
  public FilterRegistrationBean<TenantMdcFilter> tenantMdcFilterRegistration(
      TenantMdcFilter filter) {
    FilterRegistrationBean<TenantMdcFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setName(FILTER_NAME);
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
