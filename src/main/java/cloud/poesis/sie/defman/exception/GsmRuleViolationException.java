package cloud.poesis.sie.defman.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import cloud.poesis.sie.defman.type.GsmRuleType;

/**
 * Generic GSM rule-violation exception. The {@link GsmRuleType} carries all
 * semantic weight (which rule was violated); this class is a thin carrier of
 * {@code ruleType} + contextual {@code site} data describing the violating
 * input instances.
 */
public class GsmRuleViolationException extends RuntimeException {

    private final GsmRuleType ruleType;
    private final Map<String, Object> site;

    public GsmRuleViolationException(GsmRuleType ruleType, String detail, Map<String, Object> site) {
        super(detail);
        this.ruleType = ruleType;
        this.site = site != null ? Map.copyOf(site) : Map.of();
    }

    public GsmRuleViolationException(GsmRuleType ruleType, String detail) {
        this(ruleType, detail, Map.of());
    }

    public GsmRuleViolationException(GsmRuleType ruleType, String detail, Map<String, Object> site, Throwable cause) {
        super(detail, cause);
        this.ruleType = ruleType;
        this.site = site != null ? Map.copyOf(site) : Map.of();
    }

    /** The violated rule. */
    public GsmRuleType getRuleType() {
        return ruleType;
    }

    /** Contextual data about the violating input instances. */
    public Map<String, Object> getSite() {
        return site;
    }

    /** GSM rule type URI — stable, machine-readable identifier. */
    public String getType() {
        return ruleType.getType();
    }

    /** Short human-readable summary of the rule category. */
    public String getTitle() {
        return ruleType.getTitle();
    }

    /** Structured extension properties for RFC 9457 ProblemDetail. */
    public Map<String, Object> getExtensions() {
        Map<String, Object> ext = new LinkedHashMap<>(site);
        ext.put("rule", ruleType.name());
        ext.put("ruleDescription", ruleType.getDescription());
        return Map.copyOf(ext);
    }

    /**
     * Convenience factory — builds the site map from key-value pairs, omitting
     * null values.
     */
    public static GsmRuleViolationException of(GsmRuleType ruleType, String detail,
            Object... siteKeyValuePairs) {
        return new GsmRuleViolationException(ruleType, detail, toSite(siteKeyValuePairs));
    }

    /**
     * Convenience factory with cause — builds the site map from key-value pairs,
     * omitting null values.
     */
    public static GsmRuleViolationException of(GsmRuleType ruleType, String detail,
            Throwable cause, Object... siteKeyValuePairs) {
        return new GsmRuleViolationException(ruleType, detail, toSite(siteKeyValuePairs), cause);
    }

    private static Map<String, Object> toSite(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Site key-value pairs must be even; got " + keyValuePairs.length);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object value = keyValuePairs[i + 1];
            if (value != null) {
                map.put((String) keyValuePairs[i], value);
            }
        }
        return Map.copyOf(map);
    }
}
