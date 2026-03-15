package cloud.poesis.sie.defman.type;

/**
 * Cascade relationship types for GSM lifecycle inter-transition rules.
 *
 * <p>
 * See {@code gsm-ascription-lifecycle} diagram — Inter Transition Rules.
 */
public enum AscriptionStatusTransitionCascadeType {

    /**
     * Existential dependency, lifecycle coupling.
     * Mechanism → Effector/Receptor.
     * Scope: all transitions.
     * On failure: source transition MUST be rejected.
     */
    CONSTITUTIVE,

    /**
     * Governance scope.
     * Structure → Mechanism/Interface/Directive/Norm.
     * Scope: all transitions.
     * On failure: no-op for that target.
     */
    GOVERNING,

    /**
     * Downstream consumer integrity.
     * Effector/Receptor → Interaction/Interface.
     * Scope: degradation + terminal transitions only.
     * On failure: no-op for that target.
     */
    DEPENDENT
}
