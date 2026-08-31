package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.service.MechanismRuleParsingService.ChainLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import org.springframework.stereotype.Service;

/**
 * GSM §Mechanism U12: derives port specifications from a Mechanism's Starlark
 * rule AST.
 *
 * <p>
 * Parses port signatures from the Starlark rule AST, resolves data and port
 * archetypes, and
 * returns derivation results. Does NOT create entities — the caller is
 * responsible for creating
 * ports via {@link AscriptionService}. Supports typed port archetypes via
 * {@code .by()} and {@code
 * .on()} Starlark DSL clauses.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class MechanismPortDerivationService {

  private static final String EFFECTOR_ARCHETYPE_ID = "gsmarc://gsm/Effector/v1";
  private static final String RECEPTOR_ARCHETYPE_ID = "gsmarc://gsm/Receptor/v1";

  private final MechanismRuleParsingService parsingService;
  private final ArchetypeService archetypeService;
  private final ObjectMapper objectMapper;

  public MechanismPortDerivationService(
      MechanismRuleParsingService parsingService,
      ArchetypeService archetypeService,
      ObjectMapper objectMapper) {
    this.parsingService = parsingService;
    this.archetypeService = archetypeService;
    this.objectMapper = objectMapper;
  }

  /**
   * A derived port specification: archetype ID and pre-built statement, ready for
   * {@link
   * AscriptionService#create(String, JsonNode, java.util.UUID)}.
   *
   * @param archetypeId the port Archetype URI
   * @param statement   the pre-built JSON statement (mechanism + data archetype
   *                    references)
   */
  public record PortDerivation(String archetypeId, JsonNode statement) {
  }

  /**
   * Derives port specifications (Effectors and Receptors) from the Mechanism's
   * Starlark rule.
   *
   * @param mechanism the Mechanism entity to derive ports from
   * @return list of port derivations (may be empty)
   */
  public List<PortDerivation> derivePortSpecs(MechanismEntity mechanism) {
    String rule = mechanism.getStatement().get("rule").asText();
    List<PortSignature> signatures = collectPortSignatures(rule);
    if (signatures.isEmpty()) {
      return List.of();
    }
    return resolvePortDerivations(mechanism, signatures);
  }

  // ======================================================================
  // Port signature collection (from Starlark AST)
  // ======================================================================

  /**
   * A derived port signature from Starlark AST analysis. direction: "effector" or
   * "receptor"
   * dataArchetypeId: data Archetype URI. portArchetypeId: optional port-typing
   * Archetype URI from
   * .by()/.on().
   */
  record PortSignature(String direction, String dataArchetypeId, String portArchetypeId) {
  }

  /**
   * Collects port signatures from a Starlark rule AST for auto-derivation.
   * Package-private for test
   * access.
   *
   * @param rule the Starlark rule source
   * @return the collected port signatures
   */
  List<PortSignature> collectPortSignatures(String rule) {
    StarlarkFile file = parsingService.parseStarlark(rule);
    return collectPortSignatures(file);
  }

  /**
   * GSM §Mechanism U3/U4: collect port signatures from Starlark AST.
   *
   * <ul>
   * <li>sys.receive("X") → Receptor for X (trigger, base Receptor)
   * <li>sys.receive("X").on("R") → Receptor for X (trigger, port type R)
   * <li>sys.effect("A", data) → Effector for A (base Effector)
   * <li>sys.effect("A", data).by("E") → Effector for A (port type E)
   * <li>sys.effect("A", data).receive("F") → Effector for A + Receptor for F
   * (base types)
   * <li>sys.effect("A", data).receive("F").on("R") → Effector for A + Receptor
   * for F (port type
   * R)
   * <li>sys.effect("A", data).by("E").receive("F").on("R") → Effector for A (port
   * type E) +
   * Receptor for F (port type R)
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
    if (expr == null)
      return;
    if (!(expr instanceof CallExpression call))
      return;
    if (!parsingService.isSysReceiveChain(call))
      return;

    List<ChainLink> chain = parsingService.unwrapReceiveChain(call);
    if (chain.isEmpty())
      return;

    String dataArchetype = null;
    String portArchetype = null;
    for (ChainLink link : chain) {
      String arg = (!link.args().isEmpty() && link.args().get(0).getValue() instanceof StringLiteral sl)
          ? sl.getValue()
          : null;
      switch (link.method()) {
        case "receive" -> dataArchetype = arg;
        case "on" -> portArchetype = arg;
        default -> {
        }
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

    if (expr == null)
      return;
    if (!(expr instanceof CallExpression call))
      return;
    if (!parsingService.isSysEffectChain(call))
      return;

    List<ChainLink> chain = parsingService.unwrapEffectChain(call);
    if (chain.isEmpty())
      return;

    // Extract data from chain: effect("A"), by("E"), receive("F"), on("R")
    String dataArchetype = null;
    String effectorPortArchetype = null;
    String receiveArchetype = null;
    String receptorPortArchetype = null;

    for (ChainLink link : chain) {
      String arg = (!link.args().isEmpty() && link.args().get(0).getValue() instanceof StringLiteral sl)
          ? sl.getValue()
          : null;
      switch (link.method()) {
        case "effect" -> dataArchetype = arg;
        case "by" -> effectorPortArchetype = arg;
        case "receive" -> receiveArchetype = arg;
        case "on" -> receptorPortArchetype = arg;
        default -> {
        }
      }
    }

    if (dataArchetype == null)
      return;

    // Always derive Effector for the effect() data archetype
    signatures.add(new PortSignature("effector", dataArchetype, effectorPortArchetype));

    // If .receive() present → derive feedback Receptor (closed-loop)
    if (receiveArchetype != null) {
      signatures.add(new PortSignature("receptor", receiveArchetype, receptorPortArchetype));
    }
  }

  // ======================================================================
  // Port derivation resolution
  // ======================================================================

  /**
   * GSM §Mechanism U12: resolve port derivation specifications from signatures.
   *
   * <p>
   * Each port signature is resolved to an archetype and a pre-built statement.
   * The caller is
   * responsible for creating the actual port entities via
   * {@link AscriptionService}.
   */
  private List<PortDerivation> resolvePortDerivations(
      MechanismEntity mechanism, List<PortSignature> signatures) {
    ArchetypeEntity baseEffectorArchetype = archetypeService.resolveForCreation(EFFECTOR_ARCHETYPE_ID).archetype();
    ArchetypeEntity baseReceptorArchetype = archetypeService.resolveForCreation(RECEPTOR_ARCHETYPE_ID).archetype();

    List<PortDerivation> derivations = new ArrayList<>();

    for (PortSignature sig : signatures) {
      ArchetypeEntity dataArchetype = archetypeService.resolveArchetypeUri(sig.dataArchetypeId(),
          "mechanism rule reference");
      archetypeService.validateRefereeEligibility(dataArchetype, "mechanism rule reference");

      ArchetypeEntity portArchetype;
      if ("effector".equals(sig.direction())) {
        portArchetype = resolvePortArchetype(sig.portArchetypeId(), baseEffectorArchetype);
      } else {
        portArchetype = resolvePortArchetype(sig.portArchetypeId(), baseReceptorArchetype);
      }

      ObjectNode statement = objectMapper.createObjectNode();
      statement.put("mechanism", mechanism.getId().toString());
      JsonNode dataArchetypeId = dataArchetype.getStatement().get("$id");
      if (dataArchetypeId == null || !dataArchetypeId.isTextual()) {
        throw new IllegalStateException(
            "In-effect data Archetype has no textual $id URI: " + sig.dataArchetypeId());
      }
      statement.put("archetype", dataArchetypeId.asText());

      derivations.add(new PortDerivation(requireArchetypeUri(portArchetype), statement));
    }

    return derivations;
  }

  private ArchetypeEntity resolvePortArchetype(
      String portArchetypeId, ArchetypeEntity baseArchetype) {
    if (portArchetypeId == null) {
      return baseArchetype;
    }
    return archetypeService.resolveForCreation(portArchetypeId).archetype();
  }

  private String requireArchetypeUri(ArchetypeEntity archetype) {
    JsonNode id = archetype.getStatement().get("$id");
    if (id == null || !id.isTextual()) {
      throw new IllegalStateException(
          "In-effect port Archetype has no textual $id URI: " + archetype.getId());
    }
    return id.asText();
  }
}
