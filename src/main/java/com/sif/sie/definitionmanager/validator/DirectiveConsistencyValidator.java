package com.sif.sie.definitionmanager.validator;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sif.sie.definitionmanager.entity.DirectiveEntity;
import com.sif.sie.definitionmanager.repository.DirectiveRepository;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;

/**
 * Validates Directive consistency rules from GSM §2 (Directive note):
 * <ul>
 *   <li>ENSURE + PREVENT on same qualifier AND same purpose → contradiction (error)</li>
 *   <li>Positive modal + its negation on same verb + qualifier + purpose → contradiction (error)</li>
 *   <li>Opposing verb directions on same qualifier + purpose → conflict resolved by modal precedence</li>
 * </ul>
 *
 * Called at Directive activation time (APPROVED→ACTIVE transition).
 */
@Component
public class DirectiveConsistencyValidator {

    private static final Collection<AscriptionStatusType> IN_EFFECT =
            List.of(AscriptionStatusType.ACTIVE, AscriptionStatusType.DEPRECATED);

    private static final Set<Set<String>> CONTRADICTORY_VERB_PAIRS = Set.of(
            Set.of("ENSURE", "PREVENT"));

    private final DirectiveRepository directiveRepo;

    public DirectiveConsistencyValidator(DirectiveRepository directiveRepo) {
        this.directiveRepo = directiveRepo;
    }

    /**
     * Checks the activating Directive against all in-effect Directives
     * sharing the same qualifier and purpose.
     */
    public void validate(DirectiveEntity directive) {
        UUID qualifierDefId = directive.getQualifier().getDefinition().getId();
        UUID purposeDefId = directive.getPurpose().getDefinition().getId();
        UUID thisDefId = directive.getDefinition().getId();

        String verb = directive.getStatement().get("verb").asText();
        String modal = directive.getStatement().get("modal").asText();

        List<DirectiveEntity> siblings = directiveRepo
                .findAllByQualifier_Definition_IdAndPurpose_Definition_IdAndStatusIn(
                        qualifierDefId, purposeDefId, IN_EFFECT);

        for (DirectiveEntity sibling : siblings) {
            if (sibling.getDefinition().getId().equals(thisDefId)) {
                continue;
            }

            String sibVerb = sibling.getStatement().get("verb").asText();
            String sibModal = sibling.getStatement().get("modal").asText();

            // Rule 1: ENSURE + PREVENT on same qualifier+purpose → contradiction
            if (CONTRADICTORY_VERB_PAIRS.contains(Set.of(verb, sibVerb))) {
                throw new IllegalArgumentException(
                        "Directive contradiction: " + verb + " and " + sibVerb
                                + " on same qualifier (definition " + qualifierDefId
                                + ") and purpose (definition " + purposeDefId
                                + "). Conflicting directive: " + sibling.getId());
            }

            // Rule 3: positive modal + its negation on same verb → contradiction
            if (verb.equals(sibVerb) && areModalContradictions(modal, sibModal)) {
                throw new IllegalArgumentException(
                        "Directive modal contradiction: " + modal + " " + verb
                                + " vs " + sibModal + " " + sibVerb
                                + " on same qualifier (definition " + qualifierDefId
                                + ") and purpose (definition " + purposeDefId
                                + "). Conflicting directive: " + sibling.getId());
            }
        }
    }

    private static boolean areModalContradictions(String a, String b) {
        return (a.equals("MUST") && b.equals("MUST_NOT"))
                || (a.equals("MUST_NOT") && b.equals("MUST"))
                || (a.equals("SHOULD") && b.equals("SHOULD_NOT"))
                || (a.equals("SHOULD_NOT") && b.equals("SHOULD"));
    }
}
