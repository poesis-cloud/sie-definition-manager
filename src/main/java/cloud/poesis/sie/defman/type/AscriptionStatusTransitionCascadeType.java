package cloud.poesis.sie.defman.type;

/**
 * Cascade relationship types for GSM lifecycle inter-transition rules.
 *
 * <p>
 * See {@code gsm-ascription-lifecycle} diagram — Inter Transition Rules.
 *
 * @author Clément Cazaud
 * @since 0.1.0
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
     * Structure → Mechanism/Directive/Norm.
     * Scope: all transitions.
     * On failure: no-op for that target.
     */
    GOVERNING,

    /**
     * Downstream consumer integrity.
     * Effector/Receptor → Interaction.
     * Scope: degradation + terminal transitions only.
     * On failure: no-op for that target.
     */
    DEPENDENT
}
