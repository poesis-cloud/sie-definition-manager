package cloud.poesis.sie.defman.type;

/**
 * Rules governing Ascription lifecycle status transitions.
 *
 * <p>
 * Each value describes a <em>rule</em> (the constraint that must hold during a
 * status
 * transition), not an error or violation. These rules enforce the Ascription
 * state machine
 * (gsm-ascription-lifecycle) including path validity, referee preconditions,
 * cascade semantics,
 * approval convergence, activation handoff, and terminal immutability.
 *
 * <p>
 * Every value carries:
 *
 * <ul>
 * <li>{@code type} — a stable machine-readable URI ({@code gsm:rules/…})
 * <li>{@code title} — short human-readable label for RFC 9457 ProblemDetail
 * <li>{@code description} — natural-language statement of the rule
 * </ul>
 *
 * @author Clément Cazaud
 * @since 1.0.0
 */
public enum AscriptionStatusTransitionRuleType implements RuleType {
    ASCRIPTION_STATUS_TRANSITION_PATH(
            "gsm:rules/ascription/status-transition/path",
            "Ascription status transition path",
            "A lifecycle transition is permitted only between statuses connected "
                    + "by an edge in the Ascription state machine (gsm-ascription-"
                    + "lifecycle)."),

    ASCRIPTION_STATUS_TRANSITION_COMPATIBILITY_WITH_REFERENCE_STATUS(
            "gsm:rules/ascription/status-transition/compatibility-with-reference-status",
            "Ascription status transition compatibility with reference status",
            "A lifecycle transition requires every referenced entity (referee FK) "
                    + "to be in a lifecycle status that satisfies the referential "
                    + "integrity precondition for the target status."),

    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_CONSTITUENTS(
            "gsm:rules/ascription/status-transition/cascade-to-constituents",
            "Ascription status transition cascade to constituents",
            "A constitutive cascade (Mechanism → Effectors/Receptors) must "
                    + "complete successfully for all lifecycle-coupled targets — "
                    + "failure blocks the source transition."),

    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_SUBJECTS(
            "gsm:rules/ascription/status-transition/cascade-to-subjects",
            "Ascription status transition cascade to subjects",
            "A governing cascade (Structure → Mechanisms, Directives, Norms) "
                    + "propagates the transition to all governed elements — non-"
                    + "blocking, no-op on failure."),

    ASCRIPTION_STATUS_TRANSITION_CASCADE_TO_DEPENDENTS(
            "gsm:rules/ascription/status-transition/cascade-to-dependents",
            "Ascription status transition cascade to dependents",
            "A dependent cascade (Effector/Receptor → Interactions) propagates "
                    + "degradation and terminal transitions to downstream consumers "
                    + "— non-blocking, no-op on failure."),

    ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE(
            "gsm:rules/ascription/status-transition/approval-convergence",
            "Ascription status transition approval convergence",
            "Approving an Ascription auto-terminates all non-terminal sibling "
                    + "Ascriptions for the same Definition: DRAFT → ABANDONED, "
                    + "PROPOSED → REJECTED."),

    ASCRIPTION_STATUS_TRANSITION_VERSION_UNIQUENESS(
            "gsm:rules/ascription/status-transition/version-uniqueness",
            "Ascription status transition version uniqueness",
            "Approving an Ascription assigns exactly one positive governance version "
                    + "within its Definition."),

    ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_CANDIDATE_VERSION(
            "gsm:rules/ascription/status-transition/archetype-candidate-version",
            "Ascription status transition archetype candidate version",
            "Submitting an Archetype requires its authored $id suffix to equal the next Definition "
                    + "version without reserving that suffix."),

    ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_VERSION_RECONCILIATION(
            "gsm:rules/ascription/status-transition/archetype-version-reconciliation",
            "Ascription status transition archetype version reconciliation",
            "After approving an Archetype, its database-materialized version must equal the authored "
                    + "$id version suffix before sibling convergence."),

    ASCRIPTION_STATUS_TRANSITION_ACTIVATION_HANDOFF(
            "gsm:rules/ascription/status-transition/activation-handoff",
            "Ascription status transition activation handoff",
            "Activating an Ascription supersedes the previous in-effect "
                    + "Ascription for the same Definition: the predecessor "
                    + "transitions from ACTIVE to DEPRECATED."),

    ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY(
            "gsm:rules/ascription/status-transition/terminal-immutability",
            "Ascription status transition terminal immutability",
            "Once an Ascription reaches a terminal status (ABANDONED, REJECTED, "
                    + "RETIRED), no further status transitions may be appended — "
                    + "a new creative cycle requires a new Ascription.");

    private final String type;
    private final String title;
    private final String description;

    AscriptionStatusTransitionRuleType(String type, String title, String description) {
        this.type = type;
        this.title = title;
        this.description = description;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
