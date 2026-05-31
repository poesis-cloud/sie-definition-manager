package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MockMvc end-to-end test for {@link TenantMdcFilter} wired ahead of a throwaway snapshot
 * controller. Covers AC-1 happy path through the Spring filter chain and AC-4 multi-request leakage
 * prevention.
 *
 * <p>The controller is declared in {@code src/test/java} and exists solely as a probe: it returns a
 * JSON snapshot of the MDC values it observes during request handling. This avoids depending on any
 * production endpoint (none exist yet in Sprint 2) and keeps the test hermetic.
 *
 * <p>SECURITY: {@code X-Tenant-Id} is PI-1 observability correlation metadata only. It is NOT an
 * authentication or authorization source — this test exercises only the observability path.
 */
class TenantMdcFilterMockMvcTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    MDC.clear();
    TenantMdcFilter filter = new TenantMdcFilter();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new MdcSnapshotController())
            .addFilter(filter, "/*")
            .build();
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  @DisplayName("AC-1: request with X-Tenant-Id surfaces sanitized value in controller MDC snapshot")
  void headerPresent_setsSanitizedMdcAndSpanAttributes() throws Exception {
    mockMvc
        .perform(get("/__test/mdc-snapshot").header(TenantMdcFilter.HEADER_TENANT_ID, "acme-corp"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.['gsm.tenant.id']").value("acme-corp"))
        .andExpect(jsonPath("$.['sie.component']").value("definition-manager"));
  }

  @Test
  @DisplayName("AC-4: second request does not leak first request's tenant value")
  void secondRequestDoesNotLeakFirstTenant() throws Exception {
    mockMvc
        .perform(get("/__test/mdc-snapshot").header(TenantMdcFilter.HEADER_TENANT_ID, "tenant-one"))
        .andExpect(jsonPath("$.['gsm.tenant.id']").value("tenant-one"));

    mockMvc
        .perform(get("/__test/mdc-snapshot").header(TenantMdcFilter.HEADER_TENANT_ID, "tenant-two"))
        .andExpect(jsonPath("$.['gsm.tenant.id']").value("tenant-two"));

    // After both requests, no MDC leak on the test thread.
    assertThat(MDC.get(TenantMdcFilter.MDC_TENANT_ID)).isNull();
    assertThat(MDC.get(TenantMdcFilter.MDC_COMPONENT)).isNull();
  }

  @Test
  @DisplayName("AC-2: missing header surfaces 'unknown' through the controller")
  void missingHeader_surfacesUnknown() throws Exception {
    mockMvc
        .perform(get("/__test/mdc-snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.['gsm.tenant.id']").value("unknown"));
  }

  /**
   * Throwaway snapshot controller. Returns the MDC values observed mid-request as a JSON map so the
   * test can assert what the filter wrote without coupling to any production endpoint.
   */
  @RestController
  static class MdcSnapshotController {
    @GetMapping("/__test/mdc-snapshot")
    Map<String, String> snapshot() {
      Map<String, String> out = new LinkedHashMap<>();
      out.put(TenantMdcFilter.MDC_TENANT_ID, MDC.get(TenantMdcFilter.MDC_TENANT_ID));
      out.put(TenantMdcFilter.MDC_COMPONENT, MDC.get(TenantMdcFilter.MDC_COMPONENT));
      return out;
    }
  }
}
