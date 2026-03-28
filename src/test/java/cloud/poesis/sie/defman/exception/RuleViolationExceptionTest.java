package cloud.poesis.sie.defman.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.type.RuleType;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RuleViolationExceptionTest {

  @Nested
  class Constructors {

    @Test
    void twoArgConstructor_setsMessageAndEmptySite() {
      var ex =
          new RuleViolationException(
              RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, "invalid transition");
      assertEquals("invalid transition", ex.getMessage());
      assertEquals(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, ex.getRuleType());
      assertTrue(ex.getSite().isEmpty());
    }

    @Test
    void threeArgConstructor_setsMessageAndSite() {
      Map<String, Object> site = Map.of("from", "DRAFT", "to", "ACTIVE");
      var ex =
          new RuleViolationException(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, "bad path", site);
      assertEquals("bad path", ex.getMessage());
      assertEquals("DRAFT", ex.getSite().get("from"));
      assertEquals("ACTIVE", ex.getSite().get("to"));
    }

    @Test
    void threeArgConstructor_nullSiteTreatedAsEmpty() {
      var ex =
          new RuleViolationException(
              RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, "detail", (Map<String, Object>) null);
      assertTrue(ex.getSite().isEmpty());
    }

    @Test
    void fourArgConstructor_setsMessageSiteAndCause() {
      Throwable cause = new RuntimeException("root");
      Map<String, Object> site = Map.of("key", "value");
      var ex =
          new RuleViolationException(
              RuleType.NORM_APPLICABILITY_CEL_PARSING, "cel error", site, cause);
      assertEquals("cel error", ex.getMessage());
      assertEquals(cause, ex.getCause());
      assertEquals("value", ex.getSite().get("key"));
    }

    @Test
    void fourArgConstructor_nullSiteTreatedAsEmpty() {
      var ex =
          new RuleViolationException(
              RuleType.NORM_APPLICABILITY_CEL_PARSING, "cel error", null, new RuntimeException());
      assertTrue(ex.getSite().isEmpty());
    }
  }

  @Nested
  class Getters {

    @Test
    void getType_delegatesToRuleType() {
      var ex = new RuleViolationException(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, "detail");
      assertEquals(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH.getType(), ex.getType());
    }

    @Test
    void getTitle_delegatesToRuleType() {
      var ex = new RuleViolationException(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, "detail");
      assertEquals(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH.getTitle(), ex.getTitle());
    }

    @Test
    void getExtensions_mergesSiteAndRuleInfo() {
      Map<String, Object> site = Map.of("definitionId", "abc");
      var ex =
          new RuleViolationException(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH, "detail", site);

      Map<String, Object> ext = ex.getExtensions();
      assertEquals("abc", ext.get("definitionId"));
      assertEquals(RuleType.ASCRIPTION_STATUS_TRANSITION_PATH.name(), ext.get("rule"));
      assertEquals(
          RuleType.ASCRIPTION_STATUS_TRANSITION_PATH.getDescription(), ext.get("ruleDescription"));
    }

    @Test
    void getExtensions_emptySiteStillHasRuleInfo() {
      var ex = new RuleViolationException(RuleType.NORM_APPLICABILITY_CEL_PARSING, "detail");
      Map<String, Object> ext = ex.getExtensions();
      assertEquals(2, ext.size());
      assertNotNull(ext.get("rule"));
      assertNotNull(ext.get("ruleDescription"));
    }
  }

  @Nested
  class StaticFactories {

    @Test
    void of_buildsFromKeyValuePairs() {
      var ex =
          RuleViolationException.of(
              RuleType.NORM_APPLICABILITY_CEL_PARSING,
              "bad applicability",
              "field",
              "applicability",
              "ascriptionId",
              "uuid-123");
      assertEquals("bad applicability", ex.getMessage());
      assertEquals("applicability", ex.getSite().get("field"));
      assertEquals("uuid-123", ex.getSite().get("ascriptionId"));
    }

    @Test
    void of_omitsNullValues() {
      var ex =
          RuleViolationException.of(
              RuleType.NORM_APPLICABILITY_CEL_PARSING, "detail", "key1", "val1", "key2", null);
      assertEquals("val1", ex.getSite().get("key1"));
      assertTrue(ex.getSite().size() == 1);
    }

    @Test
    void of_throwsOnOddPairs() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RuleViolationException.of(
                  RuleType.NORM_APPLICABILITY_CEL_PARSING,
                  "detail",
                  "key1",
                  "val1",
                  "orphanedKey"));
    }

    @Test
    void ofWithCause_buildsFromKeyValuePairsAndCause() {
      Throwable cause = new RuntimeException("root");
      var ex =
          RuleViolationException.of(
              RuleType.NORM_APPLICABILITY_CEL_PARSING,
              "bad applicability",
              cause,
              "field",
              "applicability");
      assertEquals("bad applicability", ex.getMessage());
      assertEquals(cause, ex.getCause());
      assertEquals("applicability", ex.getSite().get("field"));
    }

    @Test
    void ofWithCause_throwsOnOddPairs() {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              RuleViolationException.of(
                  RuleType.NORM_APPLICABILITY_CEL_PARSING,
                  "detail",
                  new RuntimeException(),
                  "key1"));
    }

    @Test
    void of_emptyVarargs() {
      var ex = RuleViolationException.of(RuleType.NORM_APPLICABILITY_CEL_PARSING, "no site");
      assertTrue(ex.getSite().isEmpty());
    }
  }

  @Nested
  class SiteImmutability {

    @Test
    void siteMapIsImmutable() {
      Map<String, Object> site = new java.util.HashMap<>();
      site.put("key", "val");
      var ex = new RuleViolationException(RuleType.NORM_APPLICABILITY_CEL_PARSING, "detail", site);
      assertThrows(UnsupportedOperationException.class, () -> ex.getSite().put("newKey", "newVal"));
    }

    @Test
    void extensionsMapIsImmutable() {
      var ex = new RuleViolationException(RuleType.NORM_APPLICABILITY_CEL_PARSING, "detail");
      assertThrows(
          UnsupportedOperationException.class, () -> ex.getExtensions().put("newKey", "newVal"));
    }
  }
}
