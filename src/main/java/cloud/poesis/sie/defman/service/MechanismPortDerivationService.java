package cloud.poesis.sie.defman.service;

import cloud.poesis.sie.defman.entity.ArchetypeEntity;
import cloud.poesis.sie.defman.entity.DefinitionEntity;
import cloud.poesis.sie.defman.entity.EffectorEntity;
import cloud.poesis.sie.defman.entity.MechanismEntity;
import cloud.poesis.sie.defman.entity.ReceptorEntity;
import cloud.poesis.sie.defman.service.MechanismRuleParsingService.ChainLink;
import cloud.poesis.sie.defman.type.DefinitionSubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * GSM §Mechanism U12: auto-derives Effector and Receptor port entities from a Mechanism's Starlark
 * rule AST.
 *
 * <p>Parses port signatures from the Starlark rule AST, resolves data and port archetypes, and
 * creates (or reuses) Definitions and port entities. Supports typed port archetypes via {@code
 * .by()} and {@code .on()} Starlark DSL clauses.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
@Service
public class MechanismPortDerivationService {

  private static final Logger LOG = LoggerFactory.getLogger(MechanismPortDerivationService.class);

  private final MechanismRuleParsingService parsingService;
  private final ArchetypeService archetypeService;
  private final EffectorService effectorService;
  private final ReceptorService receptorService;
  private final DefinitionService definitionService;
  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;

  public MechanismPortDerivationService(
      MechanismRuleParsingService parsingService,
      ArchetypeService archetypeService,
      @Lazy EffectorService effectorService,
      @Lazy ReceptorService receptorService,
      DefinitionService definitionService,
      EntityManager entityManager,
      ObjectMapper objectMapper) {
    this.parsingService = parsingService;
    this.archetypeService = archetypeService;
    this.effectorService = effectorService;
    this.receptorService = receptorService;
    this.definitionService = definitionService;
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
  }

  /**
   * Derives port entities (Effectors and Receptors) from the Mechanism's Starlark rule.
   *
   * @param mechanism the newly created Mechanism entity
   */
  public void derivePortsFromRule(MechanismEntity mechanism) {
    String rule = mechanism.getStatement().get("rule").asText();
    List<PortSignature> signatures = collectPortSignatures(rule);
    if (signatures.isEmpty()) {
      return;
    }
    derivePortEntities(mechanism, signatures);
  }

  // ======================================================================
  // Port signature collection (from Starlark AST)
  // ======================================================================

  /**
   * A derived port signature from Starlark AST analysis. direction: "effector" or "receptor"
   * dataArchetypeName: the data archetype schema.title portArchetypeName: optional port archetype
   * name (from .by()/.on()); null means use base EffectorArchetype/ReceptorArchetype
   */
  record PortSignature(String direction, String dataArchetypeName, String portArchetypeName) {}

  /**
   * Collects port signatures from a Starlark rule AST for auto-derivation. Package-private for test
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
    if (!parsingService.isSysReceiveChain(call)) return;

    List<ChainLink> chain = parsingService.unwrapReceiveChain(call);
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
    if (!parsingService.isSysEffectChain(call)) return;

    List<ChainLink> chain = parsingService.unwrapEffectChain(call);
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

  // ======================================================================
  // Port entity derivation
  // ======================================================================

  /**
   * GSM §Mechanism U12: derive port entities with fresh Definitions.
   *
   * <p>Each port signature gets its own fresh Definition — no matching, no dedup. Definition exists
   * solely as an identity anchor for ascriptions.
   */
  private void derivePortEntities(MechanismEntity mechanism, List<PortSignature> signatures) {
    ArchetypeEntity baseEffectorArchetype =
        archetypeService.findInEffectBySchemaTitle("EffectorArchetype");
    ArchetypeEntity baseReceptorArchetype =
        archetypeService.findInEffectBySchemaTitle("ReceptorArchetype");
    if (baseEffectorArchetype == null || baseReceptorArchetype == null) {
      LOG.warn("Base EffectorArchetype/ReceptorArchetype not in-effect; skipping auto-derivation");
      return;
    }

    UUID mechanismDefId = mechanism.getDefinition().getId();

    for (PortSignature sig : signatures) {
      ArchetypeEntity dataArchetype =
          archetypeService.findInEffectBySchemaTitle(sig.dataArchetypeName());
      if (dataArchetype == null) {
        LOG.warn(
            "Auto-derivation: data Archetype '{}' not in-effect; skipping port",
            sig.dataArchetypeName());
        continue;
      }

      if ("effector".equals(sig.direction())) {
        ArchetypeEntity portArchetype =
            resolvePortArchetype(sig.portArchetypeName(), baseEffectorArchetype, "Effector");
        deriveEffector(mechanism, mechanismDefId, portArchetype, dataArchetype);
      } else {
        ArchetypeEntity portArchetype =
            resolvePortArchetype(sig.portArchetypeName(), baseReceptorArchetype, "Receptor");
        deriveReceptor(mechanism, mechanismDefId, portArchetype, dataArchetype);
      }
    }
  }

  private ArchetypeEntity resolvePortArchetype(
      String portArchetypeName, ArchetypeEntity baseArchetype, String portKind) {
    if (portArchetypeName == null) {
      return baseArchetype;
    }
    ArchetypeEntity resolved = archetypeService.findInEffectBySchemaTitle(portArchetypeName);
    if (resolved == null) {
      LOG.warn(
          "Auto-derivation: {} port Archetype '{}' not in-effect; falling back to base {}",
          portKind,
          portArchetypeName,
          baseArchetype.getStatement().get("title").asText());
      return baseArchetype;
    }
    return resolved;
  }

  private void deriveEffector(
      MechanismEntity mechanism,
      UUID mechanismDefId,
      ArchetypeEntity effectorArchetype,
      ArchetypeEntity dataArchetype) {
    DefinitionEntity definition = definitionService.create(DefinitionSubjectType.EFFECTOR);

    ObjectNode statement = objectMapper.createObjectNode();
    statement.put("mechanism", mechanism.getId().toString());
    statement.put("archetype", dataArchetype.getId().toString());

    EffectorEntity effector =
        new EffectorEntity(definition, effectorArchetype, statement, mechanism, dataArchetype);
    EffectorEntity saved = effectorService.save(effector);
    entityManager.flush();
    entityManager.refresh(saved);
    LOG.debug(
        "Auto-derived Effector {} for data archetype {}",
        saved.getId(),
        dataArchetype.getStatement().get("title").asText());
  }

  private void deriveReceptor(
      MechanismEntity mechanism,
      UUID mechanismDefId,
      ArchetypeEntity receptorArchetype,
      ArchetypeEntity dataArchetype) {
    DefinitionEntity definition = definitionService.create(DefinitionSubjectType.RECEPTOR);

    ObjectNode statement = objectMapper.createObjectNode();
    statement.put("mechanism", mechanism.getId().toString());
    statement.put("archetype", dataArchetype.getId().toString());

    ReceptorEntity receptor =
        new ReceptorEntity(definition, receptorArchetype, statement, mechanism, dataArchetype);
    ReceptorEntity saved = receptorService.save(receptor);
    entityManager.flush();
    entityManager.refresh(saved);
    LOG.debug(
        "Auto-derived Receptor {} for data archetype {}",
        saved.getId(),
        dataArchetype.getStatement().get("title").asText());
  }
}
