package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.service.MechanismRuleParsingService.ChainLink;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import java.util.List;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.StarlarkFile;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MechanismRuleParsingServiceTest {

  private final MechanismRuleParsingService service = new MechanismRuleParsingService();

  @Nested
  class ParseStarlark {

    @Test
    void validInput_returnsAst() {
      StarlarkFile file = service.parseStarlark("sys.receive(\"X\")");
      assertNotNull(file);
      assertFalse(file.getStatements().isEmpty());
    }

    @Test
    void syntaxError_throws() {
      RuleViolationException ex =
          assertThrows(RuleViolationException.class, () -> service.parseStarlark("def foo(:::"));
      assertEquals(AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_PARSING, ex.getRuleType());
      assertTrue(ex.getMessage().contains("syntax error"));
    }
  }

  @Nested
  class ChainDetection {

    @Test
    void sysEffect_detected() {
      StarlarkFile file = service.parseStarlark("sys.effect(\"X\", {})");
      ExpressionStatement stmt = (ExpressionStatement) file.getStatements().get(0);
      assertTrue(service.isSysEffectChain((CallExpression) stmt.getExpression()));
      assertFalse(service.isSysReceiveChain((CallExpression) stmt.getExpression()));
    }

    @Test
    void sysReceive_detected() {
      StarlarkFile file = service.parseStarlark("sys.receive(\"X\")");
      ExpressionStatement stmt = (ExpressionStatement) file.getStatements().get(0);
      assertTrue(service.isSysReceiveChain((CallExpression) stmt.getExpression()));
      assertFalse(service.isSysEffectChain((CallExpression) stmt.getExpression()));
    }

    @Test
    void chainedSysEffect_detected() {
      StarlarkFile file = service.parseStarlark("sys.effect(\"X\", {}).by(\"Y\")");
      ExpressionStatement stmt = (ExpressionStatement) file.getStatements().get(0);
      assertTrue(service.isSysEffectChain((CallExpression) stmt.getExpression()));
    }

    @Test
    void chainedSysReceive_detected() {
      StarlarkFile file = service.parseStarlark("sys.receive(\"X\").on(\"Y\")");
      ExpressionStatement stmt = (ExpressionStatement) file.getStatements().get(0);
      assertTrue(service.isSysReceiveChain((CallExpression) stmt.getExpression()));
    }
  }

  @Nested
  class ChainUnwrapping {

    @Test
    void unwrapEffectChain_naturalOrder() {
      StarlarkFile file =
          service.parseStarlark("sys.effect(\"A\", {}).by(\"B\").receive(\"C\").on(\"D\")");
      ExpressionStatement stmt = (ExpressionStatement) file.getStatements().get(0);
      List<ChainLink> chain = service.unwrapEffectChain((CallExpression) stmt.getExpression());

      assertEquals(4, chain.size());
      assertEquals("effect", chain.get(0).method());
      assertEquals("by", chain.get(1).method());
      assertEquals("receive", chain.get(2).method());
      assertEquals("on", chain.get(3).method());
    }

    @Test
    void unwrapReceiveChain_naturalOrder() {
      StarlarkFile file = service.parseStarlark("sys.receive(\"X\").on(\"Y\")");
      ExpressionStatement stmt = (ExpressionStatement) file.getStatements().get(0);
      List<ChainLink> chain = service.unwrapReceiveChain((CallExpression) stmt.getExpression());

      assertEquals(2, chain.size());
      assertEquals("receive", chain.get(0).method());
      assertEquals("on", chain.get(1).method());
    }

    @Test
    void unwrapEffectChain_singleLink() {
      StarlarkFile file = service.parseStarlark("sys.effect(\"A\", {})");
      ExpressionStatement stmt = (ExpressionStatement) file.getStatements().get(0);
      List<ChainLink> chain = service.unwrapEffectChain((CallExpression) stmt.getExpression());

      assertEquals(1, chain.size());
      assertEquals("effect", chain.get(0).method());
    }
  }
}
