package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.exception.RuleViolationException;
import cloud.poesis.sie.defman.type.AscriptionConsistencyRuleType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.DictExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.ListExpression;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.SyntaxError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GSM Starlark rule structural validation service.
 *
 * <p>Validates Mechanism rule Starlark code against GSM constraints: syntax, execution budget,
 * trigger uniqueness, sys.* fluent API conformance, global whitelist, and best-effort dict literal
 * schema conformance. Also collects port signatures from the AST for auto-derivation.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class MechanismRuleValidationService {

  private static final Set<String> ALLOWED_GLOBALS =
      Set.of("sys", "now", "uuid7", "fullmatch", "search");

  private static final Set<String> STARLARK_BUILTINS =
      Set.of(
          "True",
          "False",
          "None",
          "bool",
          "dict",
          "float",
          "int",
          "list",
          "str",
          "tuple",
          "type",
          "abs",
          "all",
          "any",
          "dir",
          "enumerate",
          "fail",
          "getattr",
          "hasattr",
          "hash",
          "len",
          "max",
          "min",
          "print",
          "range",
          "repr",
          "reversed",
          "sorted",
          "zip",
          "map",
          "filter",
          "struct");

  private static final Set<String> SYS_METHODS = Set.of("effect", "receive");

  /** Valid chain methods on the EffectorBuilder returned by sys.effect(). */
  private static final Set<String> EFFECT_CHAIN_METHODS = Set.of("by", "receive", "on");

  /** Valid chain methods on the ReceptorBuilder returned by sys.receive(). */
  private static final Set<String> RECEIVE_CHAIN_METHODS = Set.of("on");

  /** Valid read-only properties on the sys namespace object. */
  private static final Set<String> SYS_PROPERTIES = Set.of("id");

  /** GSM §Mechanism V14: maximum number of top-level statements allowed in a Mechanism rule. */
  static final int MAX_RULE_STATEMENTS = 200;

  private static final Logger LOG = LoggerFactory.getLogger(MechanismRuleValidationService.class);

  private final ArchetypeService archetypeService;

  public MechanismRuleValidationService(ArchetypeService archetypeService) {
    this.archetypeService = archetypeService;
  }

  // ======================================================================
  // Public API
  // ======================================================================

  /**
   * Validates a Starlark rule and returns the trigger archetype name. Package-private for test
   * access.
   */
  String validateStarlarkRule(String rule) {
    StarlarkFile file = parseStarlark(rule);

    // GSM §Mechanism V14: execution budget — reject rules exceeding statement limit
    int stmtCount = countStatements(file);
    if (stmtCount > MAX_RULE_STATEMENTS) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_BUDGET,
          "Mechanism rule exceeds execution budget: "
              + stmtCount
              + " statements (max "
              + MAX_RULE_STATEMENTS
              + ")",
          "statementCount",
          stmtCount,
          "maxStatements",
          MAX_RULE_STATEMENTS);
    }

    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof LoadStatement) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_CONSTRUCT_BLACKLIST,
            "load() statements are not allowed in Mechanism rules",
            "field",
            "rule",
            "construct",
            "load");
      }
    }

    String triggerArchetype = validateOnTrigger(file);

    if (countOnCalls(file) > 1) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_AS_UNIQUE_STATEMENT,
          "Mechanism rule must have exactly one sys.receive() trigger declaration",
          "field",
          "rule",
          "construct",
          "on");
    }

    Set<String> locals = collectLocals(file);
    Set<String> unknowns = collectUnknownGlobals(file, locals);
    if (!unknowns.isEmpty()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
          "Unknown globals in Mechanism rule: "
              + unknowns
              + ". Allowed: "
              + ALLOWED_GLOBALS
              + " + Starlark built-ins",
          "unknownGlobals",
          unknowns.toString());
    }

    // Collect all archetype names referenced in sys.* calls
    Set<String> referencedArchetypes = new HashSet<>();
    referencedArchetypes.add(triggerArchetype);

    for (Statement stmt : file.getStatements()) {
      validateSysCallsInStatement(stmt);
      collectSysArchetypeNames(stmt, referencedArchetypes);
      if (stmt instanceof ForStatement fs) {
        for (Statement body : fs.getBody()) {
          validateSysCallsInStatement(body);
          collectSysArchetypeNames(body, referencedArchetypes);
        }
      }
    }

    // GSM §Mechanism V10: validate archetype names resolve to declared Archetypes
    for (String archetypeName : referencedArchetypes) {
      var resolved = archetypeService.findInEffectBySchemaTitle(archetypeName);
      if (resolved == null) {
        LOG.warn(
            "Mechanism rule references undeclared Archetype: '{}'. "
                + "The Archetype must be in-effect before Mechanism activation.",
            archetypeName);
      }
    }

    // GSM §Mechanism V11: best-effort dict literal schema conformance
    for (Statement stmt : file.getStatements()) {
      validateDictLiteralConformance(stmt);
      if (stmt instanceof ForStatement fs) {
        for (Statement body : fs.getBody()) {
          validateDictLiteralConformance(body);
        }
      }
    }

    return triggerArchetype;
  }

  /**
   * Collects port signatures from a Starlark rule AST for auto-derivation. Package-private for test
   * access.
   *
   * @param rule the Starlark rule source
   * @return the collected port signatures
   */
  List<PortSignature> collectPortSignatures(String rule) {
    StarlarkFile file = parseStarlark(rule);
    return collectPortSignatures(file);
  }

  /**
   * A derived port signature from Starlark AST analysis. direction: "effector" or "receptor"
   * dataArchetypeName: the data archetype schema.title portArchetypeName: optional port archetype
   * name (from .by()/.on()); null means use base EffectorArchetype/ReceptorArchetype
   */
  record PortSignature(String direction, String dataArchetypeName, String portArchetypeName) {}

  // ======================================================================
  // Starlark parsing
  // ======================================================================

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

  // ======================================================================
  // Trigger validation
  // ======================================================================

  private String validateOnTrigger(StarlarkFile file) {
    CallExpression receiveCall = extractSysReceiveCall(file);
    if (receiveCall == null) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT,
          "Mechanism rule must begin with sys.receive(\"ArchetypeName\") as its first executable statement",
          "field",
          "rule",
          "construct",
          "sys.receive");
    }

    // Unwrap chain to find root receive() args
    List<ChainLink> chain = unwrapReceiveChain(receiveCall);
    if (chain.isEmpty() || !"receive".equals(chain.get(0).method())) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_AS_FIRST_STATEMENT,
          "Mechanism rule must begin with sys.receive(\"ArchetypeName\") as its first executable statement",
          "field",
          "rule",
          "construct",
          "sys.receive");
    }

    // Validate chain order: receive → [on]
    for (int i = 1; i < chain.size(); i++) {
      String method = chain.get(i).method();
      if (!RECEIVE_CHAIN_METHODS.contains(method)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API,
            "Unknown chain method on sys.receive(): ."
                + method
                + "(). Allowed: "
                + RECEIVE_CHAIN_METHODS,
            "method",
            method);
      }
    }

    ChainLink receiveLink = chain.get(0);
    List<Argument> args = receiveLink.args();
    if (args.size() != 1 || !(args.get(0) instanceof Argument.Positional)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
          "sys.receive() must have exactly one positional string argument",
          "field",
          "rule",
          "construct",
          "sys.receive");
    }

    Expression argExpr = args.get(0).getValue();
    if (!(argExpr instanceof StringLiteral sl)) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
          "sys.receive() argument must be a string literal",
          "field",
          "rule",
          "construct",
          "sys.receive");
    }

    String archetype = sl.getValue();
    if (archetype == null || archetype.isBlank()) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE,
          "sys.receive() argument must be a non-empty string literal",
          "field",
          "rule",
          "construct",
          "sys.receive");
    }

    // Validate .on() chain method if present: exactly 1 string literal arg
    for (int i = 1; i < chain.size(); i++) {
      ChainLink link = chain.get(i);
      if (link.args().size() != 1) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY,
            "." + link.method() + "() requires exactly 1 argument (archetype name)",
            "method",
            link.method());
      }
      if (!(link.args().get(0).getValue() instanceof StringLiteral)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY,
            "." + link.method() + "() argument must be a string literal (archetype name)",
            "method",
            link.method());
      }
    }

    return archetype;
  }

  private CallExpression extractSysReceiveCall(StarlarkFile file) {
    for (Statement stmt : file.getStatements()) {
      CallExpression found = extractSysReceiveFromExpr(stmt);
      if (found != null) return found;
    }
    return null;
  }

  private CallExpression extractSysReceiveFromExpr(Statement stmt) {
    Expression expr = null;
    if (stmt instanceof ExpressionStatement es) {
      expr = es.getExpression();
    } else if (stmt instanceof AssignmentStatement as) {
      expr = as.getRHS();
    }
    if (expr instanceof CallExpression call && isSysReceiveChain(call)) {
      return call;
    }
    return null;
  }

  private int countOnCalls(StarlarkFile file) {
    int count = 0;
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof ExpressionStatement es
          && es.getExpression() instanceof CallExpression call) {
        if (isSysReceiveChain(call)) count++;
      }
      if (stmt instanceof AssignmentStatement as && as.getRHS() instanceof CallExpression call) {
        if (isSysReceiveChain(call)) count++;
      }
    }
    return count;
  }

  // ======================================================================
  // sys.* call validation
  // ======================================================================

  private void validateSysCallsInStatement(Statement stmt) {
    if (stmt instanceof ExpressionStatement es) {
      validateSysCallsInExpr(es.getExpression());
    } else if (stmt instanceof AssignmentStatement as) {
      validateSysCallsInExpr(as.getRHS());
    }
  }

  private void validateSysCallsInExpr(Expression expr) {
    if (!(expr instanceof CallExpression call)) return;

    // sys.effect() chains
    if (isSysEffectChain(call)) {
      List<ChainLink> chain = unwrapEffectChain(call);
      if (chain.isEmpty()) return;

      // Validate chain order: effect → [by] → [receive → [on]]
      validateChainOrder(chain);

      // Validate root effect() arity: 1-2 positional args, first must be string
      // literal
      ChainLink effectLink = chain.get(0);
      List<Argument> effectArgs = effectLink.args();
      if (effectArgs.isEmpty() || effectArgs.size() > 2) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY,
            "sys.effect() requires 1-2 positional arguments (archetype name, optional data)",
            "method",
            "effect");
      }
      if (!(effectArgs.get(0).getValue() instanceof StringLiteral)) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY,
            "sys.effect() first argument must be a string literal (archetype name)",
            "method",
            "effect");
      }

      // Validate chain method arity: .by(), .receive(), .on() each require exactly 1
      // string literal
      for (int i = 1; i < chain.size(); i++) {
        ChainLink link = chain.get(i);
        if (link.args().size() != 1) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY,
              "." + link.method() + "() requires exactly 1 argument (archetype name)",
              "method",
              link.method());
        }
        if (!(link.args().get(0).getValue() instanceof StringLiteral)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API_ARITY,
              "." + link.method() + "() argument must be a string literal (archetype name)",
              "method",
              link.method());
        }
      }
    }

    // sys.receive() chains are validated in validateOnTrigger; no extra check
    // needed here
  }

  // ======================================================================
  // Chain link record and unwinding
  // ======================================================================

  /** A link in a sys.effect() fluent chain. */
  record ChainLink(String method, List<Argument> args) {}

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

  /**
   * Validate chain order: effect → [by] → [receive → [on]]. State machine with 4 states: EFFECT →
   * BY → RECEIVE → ON.
   */
  private void validateChainOrder(List<ChainLink> chain) {
    if (chain.isEmpty() || !"effect".equals(chain.get(0).method())) {
      throw RuleViolationException.of(
          AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API,
          "sys.effect() chain must start with effect()",
          "method",
          chain.isEmpty() ? "?" : chain.get(0).method());
    }

    // Valid transitions: effect→by, effect→receive, by→receive, receive→on
    int state = 0; // 0=effect, 1=by, 2=receive, 3=on
    for (int i = 1; i < chain.size(); i++) {
      String method = chain.get(i).method();
      int next =
          switch (method) {
            case "by" -> 1;
            case "receive" -> 2;
            case "on" -> 3;
            default -> -1;
          };
      if (next == -1) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API,
            "Unknown chain method: ." + method + "(). Allowed: " + EFFECT_CHAIN_METHODS,
            "method",
            method);
      }
      if (next <= state) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API,
            "Invalid chain order: ."
                + method
                + "() cannot follow ."
                + chain.get(i - 1).method()
                + "(). Valid order: effect → [by] → [receive → [on]]",
            "method",
            method);
      }
      if (next == 3 && state != 2) {
        throw RuleViolationException.of(
            AscriptionConsistencyRuleType.MECHANISM_RULE_SYS_FLUENT_API,
            ".on() can only follow .receive() (it qualifies the feedback Receptor port type)",
            "method",
            method);
      }
      state = next;
    }
  }

  // ======================================================================
  // Global whitelist validation
  // ======================================================================

  private Set<String> collectLocals(StarlarkFile file) {
    Set<String> locals = new HashSet<>();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof AssignmentStatement as && as.getLHS() instanceof Identifier id) {
        locals.add(id.getName());
      }
      if (stmt instanceof ForStatement fs && fs.getVars() instanceof Identifier id) {
        locals.add(id.getName());
      }
    }
    return locals;
  }

  private Set<String> collectUnknownGlobals(StarlarkFile file, Set<String> locals) {
    Set<String> unknowns = new HashSet<>();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof ExpressionStatement es) {
        collectUnknownGlobalsInExpr(es.getExpression(), locals, unknowns);
      } else if (stmt instanceof AssignmentStatement as) {
        collectUnknownGlobalsInExpr(as.getRHS(), locals, unknowns);
      } else if (stmt instanceof ForStatement fs) {
        collectUnknownGlobalsInExpr(fs.getCollection(), locals, unknowns);
        for (Statement body : fs.getBody()) {
          if (body instanceof ExpressionStatement es) {
            collectUnknownGlobalsInExpr(es.getExpression(), locals, unknowns);
          } else if (body instanceof AssignmentStatement as2) {
            collectUnknownGlobalsInExpr(as2.getRHS(), locals, unknowns);
          }
        }
      }
    }
    return unknowns;
  }

  private void collectUnknownGlobalsInExpr(
      Expression expr, Set<String> locals, Set<String> unknowns) {
    if (expr instanceof Identifier id) {
      String name = id.getName();
      if (!ALLOWED_GLOBALS.contains(name)
          && !STARLARK_BUILTINS.contains(name)
          && !locals.contains(name)) {
        unknowns.add(name);
      }
    } else if (expr instanceof CallExpression call) {
      collectUnknownGlobalsInExpr(call.getFunction(), locals, unknowns);
      for (Argument arg : call.getArguments()) {
        collectUnknownGlobalsInExpr(arg.getValue(), locals, unknowns);
      }
    } else if (expr instanceof DotExpression dot) {
      collectUnknownGlobalsInExpr(dot.getObject(), locals, unknowns);
      // GSM §Mechanism U2: validate sys.* property accesses (non-call)
      if (dot.getObject() instanceof Identifier obj && "sys".equals(obj.getName())) {
        String field = dot.getField().getName();
        if (!SYS_METHODS.contains(field) && !SYS_PROPERTIES.contains(field)) {
          throw RuleViolationException.of(
              AscriptionConsistencyRuleType.MECHANISM_RULE_STARLARK_GLOBAL_WHITELIST,
              "Unknown sys property: sys."
                  + field
                  + ". Allowed methods: "
                  + SYS_METHODS
                  + ", allowed properties: "
                  + SYS_PROPERTIES,
              "field",
              field);
        }
      }
    } else if (expr instanceof ListExpression list) {
      for (Expression elem : list.getElements()) {
        collectUnknownGlobalsInExpr(elem, locals, unknowns);
      }
    } else if (expr instanceof DictExpression dict) {
      for (DictExpression.Entry entry : dict.getEntries()) {
        collectUnknownGlobalsInExpr(entry.getKey(), locals, unknowns);
        collectUnknownGlobalsInExpr(entry.getValue(), locals, unknowns);
      }
    }
  }

  // ======================================================================
  // Execution budget (V14)
  // ======================================================================

  private int countStatements(StarlarkFile file) {
    int count = 0;
    for (Statement stmt : file.getStatements()) {
      count++;
      if (stmt instanceof ForStatement fs) {
        count += fs.getBody().size();
      }
    }
    return count;
  }

  // ======================================================================
  // sys.* archetype name collection (V10)
  // ======================================================================

  private void collectSysArchetypeNames(Statement stmt, Set<String> names) {
    if (stmt instanceof ExpressionStatement es) {
      collectSysArchetypeNamesInExpr(es.getExpression(), names);
    } else if (stmt instanceof AssignmentStatement as) {
      collectSysArchetypeNamesInExpr(as.getRHS(), names);
    }
  }

  private void collectSysArchetypeNamesInExpr(Expression expr, Set<String> names) {
    if (!(expr instanceof CallExpression call)) return;

    if (isSysEffectChain(call)) {
      List<ChainLink> chain = unwrapEffectChain(call);
      for (ChainLink link : chain) {
        if (!link.args().isEmpty() && link.args().get(0).getValue() instanceof StringLiteral sl) {
          names.add(sl.getValue());
        }
      }
    } else if (isSysReceiveChain(call)) {
      List<ChainLink> chain = unwrapReceiveChain(call);
      for (ChainLink link : chain) {
        if (!link.args().isEmpty() && link.args().get(0).getValue() instanceof StringLiteral sl) {
          names.add(sl.getValue());
        }
      }
    }
  }

  // ======================================================================
  // Dict literal schema conformance (V11 — best-effort)
  // ======================================================================

  private void validateDictLiteralConformance(Statement stmt) {
    if (stmt instanceof ExpressionStatement es) {
      validateDictLiteralInSysCall(es.getExpression());
    } else if (stmt instanceof AssignmentStatement as) {
      validateDictLiteralInSysCall(as.getRHS());
    }
  }

  private void validateDictLiteralInSysCall(Expression expr) {
    if (!(expr instanceof CallExpression call)) return;
    if (!isSysEffectChain(call)) return;

    // Unwrap chain to find root effect() args
    List<ChainLink> chain = unwrapEffectChain(call);
    if (chain.isEmpty()) return;

    ChainLink effectLink = chain.get(0);
    List<Argument> args = effectLink.args();
    if (args.size() < 2) return;

    // First arg = archetype name; second arg = data dict
    Expression firstArg = args.get(0).getValue();
    if (!(firstArg instanceof StringLiteral sl)) return;
    String archetypeName = sl.getValue();

    Expression secondArg = args.get(1).getValue();
    if (!(secondArg instanceof DictExpression dict)) return;

    var archetype = archetypeService.findInEffectBySchemaTitle(archetypeName);
    if (archetype == null) return; // Archetype not yet in-effect; can't validate

    var schema = archetype.getStatement();
    if (schema == null || !schema.has("properties")) return;

    var properties = schema.get("properties");
    Set<String> schemaKeys = new HashSet<>();
    properties.fieldNames().forEachRemaining(schemaKeys::add);

    for (DictExpression.Entry entry : dict.getEntries()) {
      if (entry.getKey() instanceof StringLiteral keyLit) {
        String key = keyLit.getValue();
        if (!schemaKeys.contains(key)) {
          LOG.warn(
              "sys.effect(\"{}\", ...): dict key '{}' not in Archetype schema properties {}",
              archetypeName,
              key,
              schemaKeys);
        }
      }
    }
  }

  // ======================================================================
  // Port signature collection (for auto-derivation)
  // ======================================================================

  /**
   * GSM §Mechanism U3/U4: collect port signatures from Starlark AST.
   *
   * <ul>
   *   <li>sys.receive("X") → Receptor for X (trigger, base ReceptorArchetype)
   *   <li>sys.receive("X").on("R") → Receptor for X (trigger, port type R)
   *   <li>sys.effect("A", data) → Effector for A (base EffectorArchetype)
   *   <li>sys.effect("A", data).by("E") → Effector for A (port type E)
   *   <li>sys.effect("A", data).receive("F") → Effector for A + Receptor for F (base types)
   *   <li>sys.effect("A", data).receive("F").on("R") → Effector for A + Receptor for F (port type
   *       R)
   *   <li>sys.effect("A", data).by("E").receive("F").on("R") → Effector for A (port type E) +
   *       Receptor for F (port type R)
   * </ul>
   */
  List<PortSignature> collectPortSignatures(StarlarkFile file) {
    List<PortSignature> signatures = new ArrayList<>();

    for (Statement stmt : file.getStatements()) {
      // sys.receive("X") / sys.receive("X").on("R") → trigger Receptor
      collectTriggerReceptorFromStatement(stmt, signatures);

      collectPortSignaturesFromStatement(stmt, signatures);
      if (stmt instanceof ForStatement fs) {
        for (Statement body : fs.getBody()) {
          collectPortSignaturesFromStatement(body, signatures);
        }
      }
    }

    return signatures;
  }

  private void collectTriggerReceptorFromStatement(Statement stmt, List<PortSignature> signatures) {
    Expression expr = null;
    if (stmt instanceof ExpressionStatement es) {
      expr = es.getExpression();
    } else if (stmt instanceof AssignmentStatement as) {
      expr = as.getRHS();
    }
    if (expr == null) return;
    if (!(expr instanceof CallExpression call)) return;
    if (!isSysReceiveChain(call)) return;

    List<ChainLink> chain = unwrapReceiveChain(call);
    if (chain.isEmpty()) return;

    String dataArchetype = null;
    String portArchetype = null;
    for (ChainLink link : chain) {
      String arg =
          (!link.args().isEmpty() && link.args().get(0).getValue() instanceof StringLiteral sl)
              ? sl.getValue()
              : null;
      switch (link.method()) {
        case "receive" -> dataArchetype = arg;
        case "on" -> portArchetype = arg;
        default -> {}
      }
    }
    if (dataArchetype != null) {
      signatures.add(new PortSignature("receptor", dataArchetype, portArchetype));
    }
  }

  private void collectPortSignaturesFromStatement(Statement stmt, List<PortSignature> signatures) {
    Expression expr = null;

    if (stmt instanceof ExpressionStatement es) {
      expr = es.getExpression();
    } else if (stmt instanceof AssignmentStatement as) {
      expr = as.getRHS();
    }

    if (expr == null) return;
    if (!(expr instanceof CallExpression call)) return;
    if (!isSysEffectChain(call)) return;

    List<ChainLink> chain = unwrapEffectChain(call);
    if (chain.isEmpty()) return;

    // Extract data from chain: effect("A"), by("E"), receive("F"), on("R")
    String dataArchetype = null;
    String effectorPortArchetype = null;
    String receiveArchetype = null;
    String receptorPortArchetype = null;

    for (ChainLink link : chain) {
      String arg =
          (!link.args().isEmpty() && link.args().get(0).getValue() instanceof StringLiteral sl)
              ? sl.getValue()
              : null;
      switch (link.method()) {
        case "effect" -> dataArchetype = arg;
        case "by" -> effectorPortArchetype = arg;
        case "receive" -> receiveArchetype = arg;
        case "on" -> receptorPortArchetype = arg;
        default -> {}
      }
    }

    if (dataArchetype == null) return;

    // Always derive Effector for the effect() data archetype
    signatures.add(new PortSignature("effector", dataArchetype, effectorPortArchetype));

    // If .receive() present → derive feedback Receptor (closed-loop)
    if (receiveArchetype != null) {
      signatures.add(new PortSignature("receptor", receiveArchetype, receptorPortArchetype));
    }
  }
}
