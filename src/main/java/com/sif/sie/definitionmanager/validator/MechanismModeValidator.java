package com.sif.sie.definitionmanager.validator;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sif.sie.definitionmanager.entity.MechanismEntity;
import com.sif.sie.definitionmanager.repository.EffectorRepository;
import com.sif.sie.definitionmanager.repository.ReceptorRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

/**
 * Validates Mechanism generative/declarative mutual exclusivity from GSM §1:
 * <ul>
 *   <li>Generative mode (rule present): explicitly authored Effector/Receptor
 *       Ascriptions for this Mechanism Definition MUST NOT exist</li>
 *   <li>Declarative mode (rule absent): at least 1 Effector and 1 Receptor
 *       MUST be explicitly authored (checked at activation)</li>
 * </ul>
 */
@Component
public class MechanismModeValidator {

    private static final Collection<AscriptionStatusType> IN_EFFECT =
            List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

    private final EffectorRepository effectorRepo;
    private final ReceptorRepository receptorRepo;

    public MechanismModeValidator(EffectorRepository effectorRepo, ReceptorRepository receptorRepo) {
        this.effectorRepo = effectorRepo;
        this.receptorRepo = receptorRepo;
    }

    /**
     * At creation time: reject generative mode when explicit ports exist.
     */
    public void validateCreation(MechanismEntity mechanism) {
        if (!hasRule(mechanism)) {
            return;
        }

        UUID mechanismDefId = mechanism.getDefinition().getId();

        if (!effectorRepo.findAllByMechanism_Definition_Id(mechanismDefId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Generative mode conflict: explicitly authored Effector Ascriptions exist "
                            + "for Mechanism definition " + mechanismDefId
                            + ". In Generative mode, Effectors/Receptors are auto-derived from the rule.");
        }

        if (!receptorRepo.findAllByMechanism_Definition_Id(mechanismDefId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Generative mode conflict: explicitly authored Receptor Ascriptions exist "
                            + "for Mechanism definition " + mechanismDefId
                            + ". In Generative mode, Effectors/Receptors are auto-derived from the rule.");
        }
    }

    /**
     * At activation time: reject declarative mode with zero in-effect ports.
     */
    public void validateActivation(MechanismEntity mechanism) {
        if (hasRule(mechanism)) {
            return;
        }

        UUID mechanismDefId = mechanism.getDefinition().getId();

        boolean hasEffectors = !effectorRepo
                .findAllByMechanism_Definition_IdAndStatusIn(mechanismDefId, IN_EFFECT).isEmpty();
        boolean hasReceptors = !receptorRepo
                .findAllByMechanism_Definition_IdAndStatusIn(mechanismDefId, IN_EFFECT).isEmpty();

        if (!hasEffectors) {
            throw new IllegalArgumentException(
                    "Declarative mode: at least 1 in-effect Effector required for Mechanism definition "
                            + mechanismDefId);
        }
        if (!hasReceptors) {
            throw new IllegalArgumentException(
                    "Declarative mode: at least 1 in-effect Receptor required for Mechanism definition "
                            + mechanismDefId);
        }
    }

    private static boolean hasRule(MechanismEntity mechanism) {
        var stmt = mechanism.getStatement();
        return stmt.has("rule") && !stmt.get("rule").isNull() && !stmt.get("rule").asText().isBlank();
    }
}
