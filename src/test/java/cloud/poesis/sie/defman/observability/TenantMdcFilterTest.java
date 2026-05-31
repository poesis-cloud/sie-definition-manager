package cloud.poesis.sie.defman.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Pure-unit tests for {@link TenantMdcFilter} covering header parsing branches (charset, length,
 * blank, multi-header), the MDC capture-and-restore invariant, and exception-path cleanup. No
 * Spring context, no OTel SDK, no MockMvc — those concerns live in dedicated tests added in Units
 * 2-4 of the S-005 DRIVE plan.
 */
class TenantMdcFilterTest {

  private TenantMdcFilter filter;

  @BeforeEach
  void setUp() {
    filter = new TenantMdcFilter();
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  // --- AC-1: header parsing ---------------------------------------------------------------

  @Test
  @DisplayName("AC-1: present, well-formed header → MDC carries sanitized value during chain")
  void headerPresent_setsSanitizedMdc() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "acme-corp");

    SnapshotChain chain = new SnapshotChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.tenantSeen).isEqualTo("acme-corp");
    assertThat(chain.componentSeen).isEqualTo("definition-manager");
  }

  @ParameterizedTest(name = "AC-1 charset: \"{0}\" → unknown")
  @ValueSource(
      strings = {
        "has whitespace",
        "has\nnewline",
        "has\ttab",
        "has,comma",
        "has/slash",
        "has@at",
        "café", // non-ASCII
        "b\u0007ell" // embedded control char (BEL — not trimmed because not leading/trailing)
      })
  void headerWithDisallowedCharset_mapsToUnknown(String raw) throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, raw);

    SnapshotChain chain = new SnapshotChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.tenantSeen).isEqualTo(TenantMdcFilter.UNKNOWN_TENANT);
  }

  @Test
  @DisplayName("AC-1 length: 129-char header → unknown (no truncation)")
  void headerOverCap_mapsToUnknown() throws Exception {
    String oversized = "a".repeat(TenantMdcFilter.MAX_LENGTH + 1);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, oversized);

    SnapshotChain chain = new SnapshotChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.tenantSeen).isEqualTo(TenantMdcFilter.UNKNOWN_TENANT);
  }

  @Test
  @DisplayName("AC-1 length: exactly 128-char header → accepted verbatim")
  void headerExactlyAtCap_isAccepted() throws Exception {
    String atCap = "a".repeat(TenantMdcFilter.MAX_LENGTH);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, atCap);

    SnapshotChain chain = new SnapshotChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.tenantSeen).isEqualTo(atCap);
  }

  @Test
  @DisplayName("AC-1: charset allows dot, underscore, colon, hyphen plus alphanumerics")
  void headerWithAllowedPunctuation_isAccepted() throws Exception {
    String value = "tenant:acme-corp.prod_eu-west-1";
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, value);

    SnapshotChain chain = new SnapshotChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.tenantSeen).isEqualTo(value);
  }

  @Test
  @DisplayName("AC-1 multi-header: first value wins via servlet getHeader(...)")
  void multipleHeaders_firstValueWins() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "first");
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "second");

    SnapshotChain chain = new SnapshotChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.tenantSeen).isEqualTo("first");
  }

  // --- AC-2: missing / blank --------------------------------------------------------------

  @Test
  @DisplayName("AC-2: header missing → MDC populated with 'unknown', request not rejected")
  void headerMissing_setsUnknown() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();

    SnapshotChain chain = new SnapshotChain();
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);

    assertThat(chain.tenantSeen).isEqualTo(TenantMdcFilter.UNKNOWN_TENANT);
    assertThat(chain.invoked).isTrue();
    assertThat(res.getStatus()).isEqualTo(200);
  }

  @ParameterizedTest(name = "AC-2 blank: \"{0}\" → unknown")
  @ValueSource(strings = {"", "   ", "\t", "\n\n"})
  void headerBlank_setsUnknown(String raw) throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, raw);

    SnapshotChain chain = new SnapshotChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);

    assertThat(chain.tenantSeen).isEqualTo(TenantMdcFilter.UNKNOWN_TENANT);
  }

  // --- AC-4: cleanup invariant ------------------------------------------------------------

  @Test
  @DisplayName("AC-4: MDC restored to prior values after successful request")
  void mdcRestoredAfterSuccessfulRequest() throws Exception {
    MDC.put(TenantMdcFilter.MDC_TENANT_ID, "outer-tenant");
    MDC.put(TenantMdcFilter.MDC_COMPONENT, "outer-component");

    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "inner-tenant");

    filter.doFilter(req, new MockHttpServletResponse(), new SnapshotChain());

    assertThat(MDC.get(TenantMdcFilter.MDC_TENANT_ID)).isEqualTo("outer-tenant");
    assertThat(MDC.get(TenantMdcFilter.MDC_COMPONENT)).isEqualTo("outer-component");
  }

  @Test
  @DisplayName("AC-4: MDC keys removed (not nulled) when prior values were absent")
  void mdcRemovedWhenNoPriorValue() throws Exception {
    assertThat(MDC.get(TenantMdcFilter.MDC_TENANT_ID)).isNull();

    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "acme");

    filter.doFilter(req, new MockHttpServletResponse(), new SnapshotChain());

    assertThat(MDC.get(TenantMdcFilter.MDC_TENANT_ID)).isNull();
    assertThat(MDC.get(TenantMdcFilter.MDC_COMPONENT)).isNull();
  }

  @Test
  @DisplayName("AC-4: MDC cleanup runs even when chain throws ServletException")
  void mdcRestoredWhenChainThrows() {
    MDC.put(TenantMdcFilter.MDC_TENANT_ID, "prior");

    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "acme");

    FilterChain throwing =
        (r, s) -> {
          throw new ServletException("boom");
        };

    assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), throwing))
        .isInstanceOf(ServletException.class);

    assertThat(MDC.get(TenantMdcFilter.MDC_TENANT_ID)).isEqualTo("prior");
    assertThat(MDC.get(TenantMdcFilter.MDC_COMPONENT)).isNull();
  }

  @Test
  @DisplayName("AC-4: MDC cleanup runs even when chain throws RuntimeException")
  void mdcRestoredWhenChainThrowsRuntime() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(TenantMdcFilter.HEADER_TENANT_ID, "acme");

    FilterChain throwing =
        (r, s) -> {
          throw new IllegalStateException("nope");
        };

    assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), throwing))
        .isInstanceOf(IllegalStateException.class);

    assertThat(MDC.get(TenantMdcFilter.MDC_TENANT_ID)).isNull();
    assertThat(MDC.get(TenantMdcFilter.MDC_COMPONENT)).isNull();
  }

  // --- sanitize() pure helper -------------------------------------------------------------

  @Test
  @DisplayName("sanitize(null) returns the unknown sentinel")
  void sanitize_nullInput() {
    assertThat(TenantMdcFilter.sanitize(null)).isEqualTo(TenantMdcFilter.UNKNOWN_TENANT);
  }

  // --- helpers ----------------------------------------------------------------------------

  /**
   * Captures the MDC values observed inside the filter chain. Pure-unit equivalent of the snapshot
   * endpoint used by the MockMvc tests in Unit 4.
   */
  private static final class SnapshotChain implements FilterChain {
    boolean invoked;
    String tenantSeen;
    String componentSeen;
    final List<Map<String, String>> snapshots = new ArrayList<>();

    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
        throws IOException, ServletException {
      invoked = true;
      tenantSeen = MDC.get(TenantMdcFilter.MDC_TENANT_ID);
      componentSeen = MDC.get(TenantMdcFilter.MDC_COMPONENT);
      Map<String, String> snap = MDC.getCopyOfContextMap();
      if (snap != null) {
        snapshots.add(snap);
      }
      // mimic a 200 OK by leaving the response untouched (MockHttpServletResponse defaults to 200).
      if (response instanceof HttpServletResponse httpRes
          && request instanceof HttpServletRequest) {
        httpRes.setStatus(200);
      }
    }
  }
}
