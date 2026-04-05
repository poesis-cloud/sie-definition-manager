package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;
import org.springframework.stereotype.Service;

/**
 * Starlark rule parsing and AST chain-walking utilities for the Mechanism subsidiary group.
 *
 * <p>Parses Starlark source into AST and provides chain-walking primitives for {@code sys.effect()}
 * and {@code sys.receive()} fluent API chains. Consumed by {@link MechanismRuleValidationService}
 * (structural validation) and {@link MechanismPortDerivationService} (port signature extraction).
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class MechanismRuleParsingService {

  /** A link in a sys.effect() or sys.receive() fluent chain. */
  record ChainLink(String method, List<Argument> args) {}

  /**
   * Parses Starlark source into an AST.
   *
   * @param rule the Starlark rule source
   * @return the parsed AST
   * @throws RuleViolationException if the source has syntax errors
   */
  StarlarkFile parseStarlark(String rule) {
    ParserInput input = ParserInput.fromString(rule, "<mechanism-rule>");
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    if (!file.errors().isEmpty()) {
      StringBuilder sb = new StringBuilder("Starlark syntax errors:");
      for (SyntaxError e : file.errors()) {
        sb.append("\n  - ").append(e.message());
      }
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_PARSING,
          sb.toString(),
          "field",
          "rule");
    }
    return file;
  }

  /**
   * Check if a CallExpression is a sys.effect() chain (possibly with .by/.receive/.on methods).
   * Walks from outermost call to innermost, looking for sys.effect root.
   */
  boolean isSysEffectChain(CallExpression call) {
    Expression current = call;
    while (current instanceof CallExpression c && c.getFunction() instanceof DotExpression dot) {
      if (dot.getObject() instanceof Identifier id && "sys".equals(id.getName())) {
        return "effect".equals(dot.getField().getName());
      }
      current = dot.getObject();
    }
    return false;
  }

  /**
   * Check if a CallExpression is a sys.receive() chain (possibly with .on). Walks from outermost
   * call to innermost, looking for sys.receive root.
   */
  boolean isSysReceiveChain(CallExpression call) {
    Expression current = call;
    while (current instanceof CallExpression c && c.getFunction() instanceof DotExpression dot) {
      if (dot.getObject() instanceof Identifier id && "sys".equals(id.getName())) {
        return "receive".equals(dot.getField().getName());
      }
      current = dot.getObject();
    }
    return false;
  }

  /**
   * Unwrap a sys.effect() fluent chain from outermost to innermost, returning links in natural
   * order: [effect, by, receive, on].
   */
  List<ChainLink> unwrapEffectChain(CallExpression call) {
    List<ChainLink> links = new ArrayList<>();
    Expression current = call;
    while (current instanceof CallExpression c && c.getFunction() instanceof DotExpression dot) {
      links.add(new ChainLink(dot.getField().getName(), c.getArguments()));
      current = dot.getObject();
      if (current instanceof Identifier) break;
    }
    Collections.reverse(links);
    return links;
  }

  /**
   * Unwrap a sys.receive() fluent chain from outermost to innermost, returning links in natural
   * order: [receive, on].
   */
  List<ChainLink> unwrapReceiveChain(CallExpression call) {
    List<ChainLink> links = new ArrayList<>();
    Expression current = call;
    while (current instanceof CallExpression c && c.getFunction() instanceof DotExpression dot) {
      links.add(new ChainLink(dot.getField().getName(), c.getArguments()));
      current = dot.getObject();
      if (current instanceof Identifier) break;
    }
    Collections.reverse(links);
    return links;
  }
}
