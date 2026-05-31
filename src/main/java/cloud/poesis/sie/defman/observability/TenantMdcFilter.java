package cloud.poesis.sie.defman.observability;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps the per-request observability correlation MDC keys {@code gsm.tenant.id} and {@code
 * sie.component}, and mirrors them onto the OTel agent-created inbound server span (when one is
 * active). See ADR-001 D-3 for the attribute vocabulary contract.
 *
 * <p><strong>Cohabitation contract</strong>: this filter is the SOLE writer of {@code
 * gsm.tenant.id} and {@code sie.component} in MDC. {@link BroadInstrumentationAspect} treats both
 * keys as read-only and only mirrors them onto AOP-created INTERNAL spans. No other code path in
 * {@code cloud.poesis.sie.defman.**} may write these MDC keys.
 *
 * <p><strong>Cleanup discipline</strong>: a prior MDC value (set by an outer context, e.g. a test
 * harness or an upstream filter) is captured before write and restored in a {@code finally} block.
 * The filter NEVER calls {@code MDC.clear()} — that would wipe keys owned by other filters or by
 * {@link BroadInstrumentationAspect}.
 *
 * <p><strong>Async dispatch limitation (S-005 R2)</strong>: {@link OncePerRequestFilter} populates
 * MDC on the request thread. If a controller returns a {@code DeferredResult} or {@code
 * CompletableFuture} whose completion runs on a different thread, MDC will not propagate to that
 * thread. Definition Manager currently has no async controllers; address when first async
 * controller lands.
 *
 * <p><strong>SECURITY</strong>: {@code X-Tenant-Id} is PI-1 observability correlation metadata
 * only. It is <strong>NOT</strong> an authentication or authorization source. It MUST NOT be used
 * by any code path to grant access, scope queries, or enforce tenant isolation. A later Story will
 * bind {@code gsm.tenant.id} to the authenticated tenant identity derived from the OAuth2
 * resource-server JWT; until then this header is client-supplied and untrusted.
 */
public class TenantMdcFilter extends OncePerRequestFilter {

  /** HTTP header carrying the client-supplied tenant correlation identifier. */
  static final String HEADER_TENANT_ID = "X-Tenant-Id";

  /** Sentinel value used when the header is absent, blank, malformed, or oversized. */
  static final String UNKNOWN_TENANT = "unknown";

  /**
   * Maximum permitted header value length (chars). Over-length values map to {@link
   * #UNKNOWN_TENANT}.
   */
  static final int MAX_LENGTH = 128;

  /**
   * Allowed character set for sanitized tenant identifiers: ASCII alphanumerics plus {@code .},
   * {@code _}, {@code :}, {@code -}. Covers UUIDs, slugs, and namespaced ids while blocking
   * whitespace, control characters, non-ASCII, and log-injection vectors.
   */
  static final Pattern ALLOWED_PATTERN = Pattern.compile("^[A-Za-z0-9._:\\-]{1,128}$");

  /** MDC key for the tenant correlation id, per ADR-001 D-3. */
  static final String MDC_TENANT_ID = "gsm.tenant.id";

  /** MDC key for the component identifier, per ADR-001 D-3. */
  static final String MDC_COMPONENT = "sie.component";

  /** Compile-time component identifier for this service, per ADR-001 D-3 enum. */
  static final String COMPONENT_VALUE = "definition-manager";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String sanitizedTenant = sanitize(request.getHeader(HEADER_TENANT_ID));

    String priorTenant = MDC.get(MDC_TENANT_ID);
    String priorComponent = MDC.get(MDC_COMPONENT);

    MDC.put(MDC_TENANT_ID, sanitizedTenant);
    MDC.put(MDC_COMPONENT, COMPONENT_VALUE);

    enrichActiveSpan(sanitizedTenant);

    try {
      chain.doFilter(request, response);
    } finally {
      restore(MDC_TENANT_ID, priorTenant);
      restore(MDC_COMPONENT, priorComponent);
    }
  }

  /**
   * Maps a raw header value to a sanitized tenant id or {@link #UNKNOWN_TENANT}. Absent, null,
   * blank, oversized, or charset-violating values all collapse to the sentinel. Over-length values
   * are NEVER truncated — a truncated tenant id silently mis-correlates traces, which is worse than
   * dropping to {@code unknown}.
   */
  static String sanitize(String raw) {
    if (raw == null) {
      return UNKNOWN_TENANT;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return UNKNOWN_TENANT;
    }
    if (trimmed.length() > MAX_LENGTH) {
      return UNKNOWN_TENANT;
    }
    if (!ALLOWED_PATTERN.matcher(trimmed).matches()) {
      return UNKNOWN_TENANT;
    }
    return trimmed;
  }

  /**
   * Mirrors the sanitized tenant id and the {@code sie.component} constant onto the currently
   * active OTel span (typically the agent-created inbound SERVER span). No-op when no valid span is
   * in context — log correlation via MDC still works.
   *
   * <p>Implemented in {@link #doFilterInternal} via {@link Span#current()}; deferred to Unit 2 of
   * the S-005 DRIVE plan when this method is wired in. Kept here as the documented extension point.
   */
  private void enrichActiveSpan(String sanitizedTenant) {
    // Unit 2 will populate this method body with Span.current() enrichment.
  }

  /**
   * Puts {@code prior} back into MDC under {@code key} if non-null; otherwise removes the key. Used
   * in the request-scoped {@code finally} block to prevent leakage into subsequent requests while
   * preserving any value an outer context had set.
   */
  private static void restore(String key, String prior) {
    if (prior == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, prior);
    }
  }
}
