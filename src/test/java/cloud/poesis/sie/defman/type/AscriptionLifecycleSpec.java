package cloud.poesis.sie.defman.type;

import static cloud.poesis.sie.defman.type.AscriptionStatusType.ABANDONED;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.ACTIVE;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.APPROVED;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.DEPRECATED;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.DRAFT;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.PROPOSED;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.REJECTED;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.RETIRED;
import static cloud.poesis.sie.defman.type.AscriptionStatusType.SUSPENDED;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Test-owned restatement of the Ascription lifecycle state machine ({@code
 * gsm-ascription-lifecycle}).
 *
 * <p>This table is written by hand from the specification, deliberately <em>not</em> derived from
 * {@link AscriptionStatusTransitionPathType}. Tests compare production behaviour against this
 * table; comparing production against itself would assert nothing. Any lifecycle change must be
 * applied here explicitly, which is what makes an accidental change fail the build.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public final class AscriptionLifecycleSpec {

  /**
   * A declared lifecycle edge.
   *
   * @param from source status, {@code null} for creation
   * @param to target status
   * @param refereeWindow statuses every referee must be in for this edge to be taken
   * @param dependentCascade whether taking this edge propagates to dependents
   */
  public record Edge(
      AscriptionStatusType from,
      AscriptionStatusType to,
      Set<AscriptionStatusType> refereeWindow,
      boolean dependentCascade) {}

  /** Every edge of the state machine, creation included. */
  public static final List<Edge> EDGES =
      List.of(
          new Edge(null, DRAFT, EnumSet.of(DRAFT, PROPOSED, APPROVED, ACTIVE), false),
          new Edge(DRAFT, PROPOSED, EnumSet.of(PROPOSED, APPROVED, ACTIVE), false),
          new Edge(DRAFT, ABANDONED, EnumSet.allOf(AscriptionStatusType.class), true),
          new Edge(PROPOSED, APPROVED, EnumSet.of(APPROVED, ACTIVE), false),
          new Edge(
              PROPOSED,
              REJECTED,
              EnumSet.of(
                  PROPOSED, APPROVED, ACTIVE, SUSPENDED, DEPRECATED, RETIRED, ABANDONED, REJECTED),
              true),
          new Edge(APPROVED, ACTIVE, EnumSet.of(ACTIVE), false),
          new Edge(ACTIVE, SUSPENDED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
          new Edge(ACTIVE, DEPRECATED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
          new Edge(SUSPENDED, ACTIVE, EnumSet.of(ACTIVE, DEPRECATED), false),
          new Edge(SUSPENDED, DEPRECATED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
          new Edge(DEPRECATED, SUSPENDED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
          new Edge(DEPRECATED, RETIRED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED, RETIRED), true));

  /** Statuses from which no edge leaves. */
  public static final Set<AscriptionStatusType> TERMINAL_STATUSES =
      Set.of(RETIRED, ABANDONED, REJECTED);

  /** Canonical shortest route from {@code DRAFT} to every status, as target-status steps. */
  public static final Map<AscriptionStatusType, List<AscriptionStatusType>> ROUTES_FROM_DRAFT =
      routesFromDraft();

  private AscriptionLifecycleSpec() {}

  /**
   * Returns the declared edge for a status pair.
   *
   * @param from source status, {@code null} for creation
   * @param to target status
   * @return the edge, or empty when the pair is not a declared edge
   */
  public static Optional<Edge> edge(AscriptionStatusType from, AscriptionStatusType to) {
    return EDGES.stream().filter(e -> e.from() == from && e.to() == to).findFirst();
  }

  /**
   * Returns whether a status pair is a declared edge.
   *
   * @param from source status, {@code null} for creation
   * @param to target status
   * @return {@code true} when the pair is declared
   */
  public static boolean isLegal(AscriptionStatusType from, AscriptionStatusType to) {
    return edge(from, to).isPresent();
  }

  /**
   * Returns every status reachable from {@code from} in one declared step.
   *
   * @param from source status
   * @return the declared target statuses
   */
  public static Set<AscriptionStatusType> targetsOf(AscriptionStatusType from) {
    Set<AscriptionStatusType> targets = EnumSet.noneOf(AscriptionStatusType.class);
    EDGES.stream().filter(e -> e.from() == from).forEach(e -> targets.add(e.to()));
    return targets;
  }

  /**
   * Returns every ordered status pair, creation excluded.
   *
   * @return the 81 pairs of the 9 x 9 status matrix
   */
  public static List<AscriptionStatusType[]> allStatusPairs() {
    List<AscriptionStatusType[]> pairs = new ArrayList<>();
    for (AscriptionStatusType from : AscriptionStatusType.values()) {
      for (AscriptionStatusType to : AscriptionStatusType.values()) {
        pairs.add(new AscriptionStatusType[] {from, to});
      }
    }
    return pairs;
  }

  private static Map<AscriptionStatusType, List<AscriptionStatusType>> routesFromDraft() {
    Map<AscriptionStatusType, List<AscriptionStatusType>> routes = new LinkedHashMap<>();
    routes.put(DRAFT, List.of());
    routes.put(PROPOSED, List.of(PROPOSED));
    routes.put(APPROVED, List.of(PROPOSED, APPROVED));
    routes.put(ACTIVE, List.of(PROPOSED, APPROVED, ACTIVE));
    routes.put(SUSPENDED, List.of(PROPOSED, APPROVED, ACTIVE, SUSPENDED));
    routes.put(DEPRECATED, List.of(PROPOSED, APPROVED, ACTIVE, DEPRECATED));
    routes.put(RETIRED, List.of(PROPOSED, APPROVED, ACTIVE, DEPRECATED, RETIRED));
    routes.put(ABANDONED, List.of(ABANDONED));
    routes.put(REJECTED, List.of(PROPOSED, REJECTED));
    return Map.copyOf(routes);
  }
}
