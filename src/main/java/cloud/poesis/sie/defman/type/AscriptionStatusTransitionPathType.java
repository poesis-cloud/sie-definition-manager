package cloud.poesis.sie.defman.type;

import static cloud.poesis.sie.defman.type.AscriptionStatusType.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Centralized source of truth for all valid Ascription lifecycle state transitions, as defined in
 * the {@code gsm-ascription-lifecycle} state machine diagram.
 *
 * <p>Each value encodes a named transition edge with:
 *
 * <ul>
 *   <li><b>from/to</b> — the source and target {@link AscriptionStatusType}
 *   <li><b>refereeAllowedStatuses</b> — which statuses each referee must be in for this transition
 *   <li><b>triggersDependentCascade</b> — whether this transition propagates to dependents
 * </ul>
 *
 * <p>Services use this type instead of hardcoding transition rules — if the lifecycle evolves, only
 * this enum needs updating.
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum AscriptionStatusTransitionPathType {

  // Progress path
  CREATE(null, DRAFT, EnumSet.of(DRAFT, PROPOSED, APPROVED, ACTIVE), false),
  SUBMIT(DRAFT, PROPOSED, EnumSet.of(PROPOSED, APPROVED, ACTIVE), false),
  APPROVE(PROPOSED, APPROVED, EnumSet.of(APPROVED, ACTIVE), false),
  ACTIVATE(APPROVED, ACTIVE, EnumSet.of(ACTIVE), false),

  // Degradation path
  SUSPEND(ACTIVE, SUSPENDED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
  RESTORE(SUSPENDED, ACTIVE, EnumSet.of(ACTIVE, DEPRECATED), false),
  DEPRECATE_FROM_ACTIVE(ACTIVE, DEPRECATED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
  DEPRECATE_FROM_SUSPENDED(SUSPENDED, DEPRECATED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
  SUSPEND_FROM_DEPRECATED(DEPRECATED, SUSPENDED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED), true),
  RETIRE(DEPRECATED, RETIRED, EnumSet.of(ACTIVE, SUSPENDED, DEPRECATED, RETIRED), true),

  // Terminal paths
  ABANDON(DRAFT, ABANDONED, EnumSet.allOf(AscriptionStatusType.class), true),
  // SC-23: DRAFT blocks rejection — all statuses except DRAFT
  REJECT(PROPOSED, REJECTED, EnumSet.complementOf(EnumSet.of(DRAFT)), true);

  private final AscriptionStatusType from;
  private final AscriptionStatusType to;
  private final Set<AscriptionStatusType> refereeAllowedStatuses;
  private final boolean triggersDependentCascade;

  AscriptionStatusTransitionPathType(
      AscriptionStatusType from,
      AscriptionStatusType to,
      Set<AscriptionStatusType> refereeAllowedStatuses,
      boolean triggersDependentCascade) {
    this.from = from;
    this.to = to;
    this.refereeAllowedStatuses = Collections.unmodifiableSet(refereeAllowedStatuses);
    this.triggersDependentCascade = triggersDependentCascade;
  }

  /**
   * Returns the source status of this transition edge.
   *
   * @return the source status, or {@code null} for the initial creation transition
   */
  public AscriptionStatusType getFrom() {
    return from;
  }

  /**
   * Returns the target status of this transition edge.
   *
   * @return the target status; never {@code null}
   */
  public AscriptionStatusType getTo() {
    return to;
  }

  /**
   * Returns the set of referee statuses allowed during this transition.
   *
   * @return an unmodifiable set of allowed referee statuses
   */
  public Set<AscriptionStatusType> getRefereeAllowedStatuses() {
    return refereeAllowedStatuses;
  }

  /**
   * Returns whether this transition triggers dependent cascades.
   *
   * @return {@code true} if dependents should be cascaded
   */
  public boolean isTriggersDependentCascade() {
    return triggersDependentCascade;
  }

  // ======================================================================
  // Pre-computed lookup tables (built once at class-load time)
  // ======================================================================

  /** Composite key for edge-based lookups. */
  private record TransitionEdge(AscriptionStatusType from, AscriptionStatusType to) {}

  private static final Map<TransitionEdge, AscriptionStatusTransitionPathType> BY_EDGE;
  private static final Map<AscriptionStatusType, Set<AscriptionStatusType>> VALID_TARGETS;
  private static final Set<AscriptionStatusType> TERMINAL_STATUSES =
      EnumSet.of(RETIRED, ABANDONED, REJECTED);

  static {
    var edgeMap = new HashMap<TransitionEdge, AscriptionStatusTransitionPathType>();
    EnumMap<AscriptionStatusType, Set<AscriptionStatusType>> targetMap =
        new EnumMap<>(AscriptionStatusType.class);
    for (AscriptionStatusTransitionPathType path : values()) {
      edgeMap.put(new TransitionEdge(path.from, path.to), path);
      if (path.from != null) {
        targetMap
            .computeIfAbsent(path.from, k -> EnumSet.noneOf(AscriptionStatusType.class))
            .add(path.to);
      }
    }
    BY_EDGE = Collections.unmodifiableMap(edgeMap);
    EnumMap<AscriptionStatusType, Set<AscriptionStatusType>> unmodifiable =
        new EnumMap<>(AscriptionStatusType.class);
    targetMap.forEach((k, v) -> unmodifiable.put(k, Collections.unmodifiableSet(v)));
    VALID_TARGETS = Collections.unmodifiableMap(unmodifiable);
  }

  // ======================================================================
  // Static lookup methods
  // ======================================================================

  /**
   * Returns {@code true} if the transition from {@code from} to {@code to} is a valid lifecycle
   * edge.
   *
   * @param from the source status (may be {@code null} for creation)
   * @param to the target status
   * @return {@code true} if the edge exists in the lifecycle state machine
   */
  public static boolean isValid(AscriptionStatusType from, AscriptionStatusType to) {
    return BY_EDGE.containsKey(new TransitionEdge(from, to));
  }

  /**
   * Returns the set of statuses reachable from {@code from} in one step.
   *
   * @param from the source status
   * @return an unmodifiable set of reachable target statuses, or an empty set if no transitions
   *     exist from the given status
   */
  public static Set<AscriptionStatusType> validTargets(AscriptionStatusType from) {
    return VALID_TARGETS.getOrDefault(from, Set.of());
  }

  /**
   * Returns the set of referee statuses allowed for a transition, or {@code null} if no
   * precondition is defined for this edge.
   *
   * @param from the source status (may be {@code null} for creation)
   * @param to the target status
   * @return the allowed referee statuses, or {@code null}
   */
  public static Set<AscriptionStatusType> refereeAllowedStatuses(
      AscriptionStatusType from, AscriptionStatusType to) {
    AscriptionStatusTransitionPathType path = BY_EDGE.get(new TransitionEdge(from, to));
    return path != null ? path.refereeAllowedStatuses : null;
  }

  /**
   * Returns whether a dependent cascade should fire for the given transition.
   *
   * @param from the source status
   * @param to the target status
   * @return {@code true} if dependent cascades apply
   */
  public static boolean isDependentCascadeApplicable(
      AscriptionStatusType from, AscriptionStatusType to) {
    AscriptionStatusTransitionPathType path = BY_EDGE.get(new TransitionEdge(from, to));
    return path != null && path.triggersDependentCascade;
  }

  /**
   * Returns {@code true} if the status is terminal (no outgoing edges).
   *
   * @param status the status to check
   * @return {@code true} if the status has no outgoing transition edges
   */
  public static boolean isTerminal(AscriptionStatusType status) {
    return TERMINAL_STATUSES.contains(status);
  }
}
