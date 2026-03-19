package cloud.poesis.sie.defman.type;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized source of truth for all valid Ascription lifecycle state
 * transitions, as defined in the {@code gsm-ascription-lifecycle} state
 * machine diagram.
 *
 * <p>
 * Each value encodes a named transition edge: a (from, to) pair of
 * {@link AscriptionStatusType} values. The {@code from} field is {@code null}
 * for the initial creation transition.
 *
 * <p>
 * Services use this type instead of hardcoding transition rules — if the
 * lifecycle evolves, only this enum needs updating.
 */
public enum AscriptionLifecyclePathType {

    // Progress path
    CREATE(null, AscriptionStatusType.DRAFT),
    SUBMIT(AscriptionStatusType.DRAFT, AscriptionStatusType.PROPOSED),
    APPROVE(AscriptionStatusType.PROPOSED, AscriptionStatusType.APPROVED),
    ACTIVATE(AscriptionStatusType.APPROVED, AscriptionStatusType.ACTIVE),

    // Degradation path
    SUSPEND(AscriptionStatusType.ACTIVE, AscriptionStatusType.SUSPENDED),
    RESTORE(AscriptionStatusType.SUSPENDED, AscriptionStatusType.ACTIVE),
    DEPRECATE_FROM_ACTIVE(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED),
    DEPRECATE_FROM_SUSPENDED(AscriptionStatusType.SUSPENDED, AscriptionStatusType.DEPRECATED),
    SUSPEND_FROM_DEPRECATED(AscriptionStatusType.DEPRECATED, AscriptionStatusType.SUSPENDED),
    RETIRE(AscriptionStatusType.DEPRECATED, AscriptionStatusType.RETIRED),

    // Terminal paths
    ABANDON(AscriptionStatusType.DRAFT, AscriptionStatusType.ABANDONED),
    REJECT(AscriptionStatusType.PROPOSED, AscriptionStatusType.REJECTED);

    private final AscriptionStatusType from;
    private final AscriptionStatusType to;

    AscriptionLifecyclePathType(AscriptionStatusType from, AscriptionStatusType to) {
        this.from = from;
        this.to = to;
    }

    public AscriptionStatusType getFrom() {
        return from;
    }

    public AscriptionStatusType getTo() {
        return to;
    }

    // Pre-computed lookup tables (built once at class-load time)
    private static final Map<AscriptionStatusType, Set<AscriptionStatusType>> VALID_TARGETS;
    private static final Set<AscriptionStatusType> TERMINAL_STATUSES = EnumSet.of(
            AscriptionStatusType.RETIRED,
            AscriptionStatusType.ABANDONED,
            AscriptionStatusType.REJECTED);

    static {
        EnumMap<AscriptionStatusType, Set<AscriptionStatusType>> map = new EnumMap<>(AscriptionStatusType.class);
        for (AscriptionLifecyclePathType path : values()) {
            if (path.from != null) {
                map.computeIfAbsent(path.from, k -> EnumSet.noneOf(AscriptionStatusType.class))
                        .add(path.to);
            }
        }
        // Wrap inner sets as unmodifiable
        EnumMap<AscriptionStatusType, Set<AscriptionStatusType>> unmodifiable = new EnumMap<>(
                AscriptionStatusType.class);
        map.forEach((k, v) -> unmodifiable.put(k, Collections.unmodifiableSet(v)));
        VALID_TARGETS = Collections.unmodifiableMap(unmodifiable);
    }

    /**
     * Returns {@code true} if the transition from {@code from} to {@code to}
     * is a valid lifecycle edge.
     */
    public static boolean isValid(AscriptionStatusType from, AscriptionStatusType to) {
        return Arrays.stream(values())
                .anyMatch(p -> p.from == from && p.to == to);
    }

    /**
     * Returns the set of statuses reachable from {@code from} in one step.
     * Returns an empty set if no transitions exist.
     */
    public static Set<AscriptionStatusType> validTargets(AscriptionStatusType from) {
        return VALID_TARGETS.getOrDefault(from, Set.of());
    }

    /**
     * Returns {@code true} if the status is terminal (no outgoing edges).
     */
    public static boolean isTerminal(AscriptionStatusType status) {
        return TERMINAL_STATUSES.contains(status);
    }
}
