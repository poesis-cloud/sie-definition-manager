package com.sif.sie.definitionmanager.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sif.sie.definitionmanager.entity.AscriptionEntity;
import com.sif.sie.definitionmanager.entity.AscriptionStatusTransitionEntity;
import com.sif.sie.definitionmanager.entity.DefinitionEntity;
import com.sif.sie.definitionmanager.repository.AscriptionStatusTransitionRepository;
import com.sif.sie.definitionmanager.service.subtype.AbstractAscriptionSubtypeService;
import com.sif.sie.definitionmanager.service.subtype.AbstractAscriptionSubtypeService.RefereeReference;
import com.sif.sie.definitionmanager.type.AscriptionStatusType;
import com.sif.sie.definitionmanager.type.CascadeType;
import com.sif.sie.definitionmanager.type.DefinitionSubjectType;
import com.sif.sie.definitionmanager.validator.AnnotationIndexManager;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class AscriptionLifecycleServiceTest {

        @Mock
        private AscriptionStatusTransitionRepository transitionRepo;

        @Mock
        private EntityManager entityManager;

        @Mock
        private AnnotationIndexManager annotationIndexManager;

        @Mock
        private AbstractAscriptionSubtypeService structureSubtype;

        @Mock
        private AbstractAscriptionSubtypeService mechanismSubtype;

        private AscriptionLifecycleService service;

        @BeforeEach
        void setUp() {
                // Minimal subtype setup: Structure + Mechanism
                when(structureSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.STRUCTURE);
                when(structureSubtype.getCascadeTargetRoles()).thenReturn(Map.of());

                when(mechanismSubtype.getSubjectType()).thenReturn(DefinitionSubjectType.MECHANISM);
                when(mechanismSubtype.getCascadeTargetRoles()).thenReturn(
                                Map.of(DefinitionSubjectType.STRUCTURE, CascadeType.GOVERNING));

                service = new AscriptionLifecycleService(
                                List.of(structureSubtype, mechanismSubtype),
                                transitionRepo, entityManager, annotationIndexManager);
        }

        // ========================================================================
        // State machine: valid transitions
        // ========================================================================

        @Nested
        class ValidTransitions {

                @ParameterizedTest
                @CsvSource({
                                "DRAFT, PROPOSED",
                                "DRAFT, ABANDONED",
                                "PROPOSED, APPROVED",
                                "PROPOSED, REJECTED",
                                "APPROVED, ACTIVE",
                                "ACTIVE, SUSPENDED",
                                "ACTIVE, DEPRECATED",
                                "SUSPENDED, ACTIVE",
                                "SUSPENDED, DEPRECATED",
                                "DEPRECATED, SUSPENDED",
                                "DEPRECATED, RETIRED"
                })
                void validTransition_succeeds(String from, String to) {
                        UUID id = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.valueOf(from));

                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
                        stubTransitionSave();

                        assertDoesNotThrow(() -> service.transition(id, to));
                }
        }

        // ========================================================================
        // State machine: invalid transitions
        // ========================================================================

        @Nested
        class InvalidTransitions {

                @ParameterizedTest
                @CsvSource({
                                "DRAFT, ACTIVE",
                                "DRAFT, REJECTED",
                                "PROPOSED, ACTIVE",
                                "PROPOSED, ABANDONED",
                                "APPROVED, DEPRECATED",
                                "APPROVED, SUSPENDED",
                                "ACTIVE, DRAFT",
                                "ACTIVE, RETIRED",
                                "RETIRED, ACTIVE",
                                "ABANDONED, DRAFT",
                                "REJECTED, PROPOSED"
                })
                void invalidTransition_rejected(String from, String to) {
                        UUID id = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.valueOf(from));

                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

                        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                        () -> service.transition(id, to));
                        assertTrue(ex.getMessage().contains("Invalid transition"));
                }
        }

        // ========================================================================
        // Entity not found
        // ========================================================================

        @Test
        void transitionOnUnknownId_rejected() {
                UUID id = UUID.randomUUID();
                when(entityManager.find(AscriptionEntity.class, id)).thenReturn(null);

                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                () -> service.transition(id, "PROPOSED"));
                assertTrue(ex.getMessage().contains("No ascription found"));
        }

        // ========================================================================
        // Referee preconditions
        // ========================================================================

        @Nested
        class RefereePreconditions {

                @Test
                void refereeSatisfied_allowed() {
                        UUID id = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.MECHANISM,
                                        AscriptionStatusType.APPROVED);
                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

                        // The mechanism references a Structure that is ACTIVE → allowed for
                        // APPROVED→ACTIVE
                        AscriptionEntity structureRef = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
                        when(mechanismSubtype.getRefereeReferences(entity))
                                        .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

                        stubTransitionSave();

                        assertDoesNotThrow(() -> service.transition(id, "ACTIVE"));
                }

                @Test
                void refereeViolated_blocked() {
                        UUID id = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.MECHANISM,
                                        AscriptionStatusType.APPROVED);
                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);

                        // Reference is DRAFT → not allowed for APPROVED→ACTIVE (must be ACTIVE)
                        AscriptionEntity structureRef = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT);
                        when(mechanismSubtype.getRefereeReferences(entity))
                                        .thenReturn(List.of(new RefereeReference(structureRef, "structure")));

                        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                        () -> service.transition(id, "ACTIVE"));
                        assertTrue(ex.getMessage().contains("Referee precondition failed"));
                        assertTrue(ex.getMessage().contains("structure"));
                }
        }

        // ========================================================================
        // Governance convergence: APPROVED → sibling termination
        // ========================================================================

        @Nested
        class GovernanceConvergence {

                @Test
                void approval_terminatesDraftSiblings() {
                        UUID id = UUID.randomUUID();
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.PROPOSED, defId);
                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
                        stubTransitionSave();

                        // Sibling DRAFT → should be ABANDONED
                        UUID sibId = UUID.randomUUID();
                        AscriptionEntity draftSibling = stubEntity(sibId, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.DRAFT, defId);
                        when(structureSubtype.findAllByDefinitionId(defId))
                                        .thenReturn(List.of(entity, draftSibling));

                        service.transition(id, "APPROVED");

                        // transitionRepo.save should be called for the main transition + the sibling
                        // termination
                        verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
                }

                @Test
                void approval_terminatesProposedSiblings() {
                        UUID id = UUID.randomUUID();
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.PROPOSED, defId);
                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
                        stubTransitionSave();

                        // Sibling PROPOSED → should be REJECTED
                        UUID sibId = UUID.randomUUID();
                        AscriptionEntity proposedSibling = stubEntity(sibId, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.PROPOSED, defId);
                        when(structureSubtype.findAllByDefinitionId(defId))
                                        .thenReturn(List.of(entity, proposedSibling));

                        service.transition(id, "APPROVED");

                        verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
                }

                @Test
                void approval_ignoresActiveSiblings() {
                        UUID id = UUID.randomUUID();
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.PROPOSED, defId);
                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
                        stubTransitionSave();

                        // Sibling ACTIVE → not terminated
                        UUID sibId = UUID.randomUUID();
                        AscriptionEntity activeSibling = stubEntity(sibId, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.ACTIVE, defId);
                        when(structureSubtype.findAllByDefinitionId(defId))
                                        .thenReturn(List.of(entity, activeSibling));

                        service.transition(id, "APPROVED");

                        // Only 1 save for the main transition (the active sibling is not terminated)
                        verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
                }
        }

        // ========================================================================
        // Activation: previous ACTIVE → DEPRECATED
        // ========================================================================

        @Nested
        class ActivationHandoff {

                @Test
                void activation_deprecatesPreviousActive() {
                        UUID id = UUID.randomUUID();
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity entity = stubEntity(id, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.APPROVED, defId);
                        when(entityManager.find(AscriptionEntity.class, id)).thenReturn(entity);
                        stubTransitionSave();

                        // Previous ACTIVE ascription for same definition
                        UUID prevId = UUID.randomUUID();
                        AscriptionEntity previous = stubEntity(prevId, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.ACTIVE, defId);
                        when(structureSubtype.findAllByDefinitionIdAndStatus(eq(defId), anyList()))
                                        .thenReturn(List.of(previous));

                        service.transition(id, "ACTIVE");

                        // At least 2 saves: main ACTIVE transition + previous DEPRECATED
                        verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
                }
        }

        // ========================================================================
        // Identity-bound validation
        // ========================================================================

        @Nested
        class IdentityBoundValidation {

                @Test
                void identityBoundMatch_valid() {
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity newEntity = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT, defId);

                        when(structureSubtype.getIdentityBoundValues(newEntity))
                                        .thenReturn(Map.of("purpose", "order-processing"));

                        // Existing first ascription with same values
                        AscriptionEntity first = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
                        when(structureSubtype.findAllByDefinitionId(defId)).thenReturn(List.of(first));
                        when(structureSubtype.getIdentityBoundValues(first))
                                        .thenReturn(Map.of("purpose", "order-processing"));

                        assertDoesNotThrow(() -> service.validateIdentityBound(newEntity));
                }

                @Test
                void identityBoundMismatch_rejected() {
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity newEntity = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT, defId);

                        when(structureSubtype.getIdentityBoundValues(newEntity))
                                        .thenReturn(Map.of("purpose", "new-purpose"));

                        // Existing first ascription with different value
                        AscriptionEntity first = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE, defId);
                        when(structureSubtype.findAllByDefinitionId(defId)).thenReturn(List.of(first));
                        when(structureSubtype.getIdentityBoundValues(first))
                                        .thenReturn(Map.of("purpose", "original-purpose"));

                        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                        () -> service.validateIdentityBound(newEntity));
                        assertTrue(ex.getMessage().contains("Identity-bound field"));
                        assertTrue(ex.getMessage().contains("purpose"));
                }

                @Test
                void firstAscription_noCheck() {
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity newEntity = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.DRAFT, defId);

                        when(structureSubtype.getIdentityBoundValues(newEntity))
                                        .thenReturn(Map.of("purpose", "anything"));
                        when(structureSubtype.findAllByDefinitionId(defId)).thenReturn(List.of());

                        assertDoesNotThrow(() -> service.validateIdentityBound(newEntity));
                }
        }

        // ========================================================================
        // Creation preconditions
        // ========================================================================

        @Nested
        class CreationPreconditions {

                @Test
                void creationWithActiveReference_valid() {
                        AscriptionEntity entity = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);

                        AscriptionEntity activeRef = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.ACTIVE);
                        when(mechanismSubtype.getRefereeReferences(entity))
                                        .thenReturn(List.of(new RefereeReference(activeRef, "structure")));

                        assertDoesNotThrow(() -> service.validateCreationPreconditions(entity));
                }

                @Test
                void creationWithRetiredReference_blocked() {
                        AscriptionEntity entity = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.MECHANISM, AscriptionStatusType.DRAFT);

                        AscriptionEntity retiredRef = stubEntity(UUID.randomUUID(),
                                        DefinitionSubjectType.STRUCTURE, AscriptionStatusType.RETIRED);
                        when(mechanismSubtype.getRefereeReferences(entity))
                                        .thenReturn(List.of(new RefereeReference(retiredRef, "structure")));

                        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                        () -> service.validateCreationPreconditions(entity));
                        assertTrue(ex.getMessage().contains("[*]->DRAFT"));
                        assertTrue(ex.getMessage().contains("RETIRED"));
                }
        }

        // ========================================================================
        // Cascade execution
        // ========================================================================

        @Nested
        class CascadeExecution {

                @Test
                void governingCascade_propagatesTransition() {
                        UUID structureId = UUID.randomUUID();
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity structure = stubEntity(structureId, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.ACTIVE, defId);
                        when(entityManager.find(AscriptionEntity.class, structureId)).thenReturn(structure);
                        stubTransitionSave();

                        // Mechanism as governing cascade target of Structure
                        UUID mechId = UUID.randomUUID();
                        AscriptionEntity mechanism = stubEntity(mechId, DefinitionSubjectType.MECHANISM,
                                        AscriptionStatusType.ACTIVE);
                        when(mechanismSubtype.findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId))
                                        .thenReturn(List.of(mechanism));

                        // ACTIVE→DEPRECATED triggers governing cascade
                        service.transition(structureId, "DEPRECATED");

                        // 2 saves: main transition + cascaded mechanism transition
                        verify(transitionRepo, atLeast(2)).save(any(AscriptionStatusTransitionEntity.class));
                }

                @Test
                void governingCascade_statusMismatch_noOp() {
                        UUID structureId = UUID.randomUUID();
                        UUID defId = UUID.randomUUID();
                        AscriptionEntity structure = stubEntity(structureId, DefinitionSubjectType.STRUCTURE,
                                        AscriptionStatusType.ACTIVE, defId);
                        when(entityManager.find(AscriptionEntity.class, structureId)).thenReturn(structure);
                        stubTransitionSave();

                        // Mechanism is DRAFT, not ACTIVE — does not match fromStatus
                        UUID mechId = UUID.randomUUID();
                        AscriptionEntity mechanism = stubEntity(mechId, DefinitionSubjectType.MECHANISM,
                                        AscriptionStatusType.DRAFT);
                        when(mechanismSubtype.findCascadeTargetsFrom(DefinitionSubjectType.STRUCTURE, structureId))
                                        .thenReturn(List.of(mechanism));

                        service.transition(structureId, "DEPRECATED");

                        // Only 1 save: the main transition (no cascade — status mismatch, no-op for
                        // GOVERNING)
                        verify(transitionRepo, times(1)).save(any(AscriptionStatusTransitionEntity.class));
                }
        }

        // ========================================================================
        // Helpers
        // ========================================================================

        private AscriptionEntity stubEntity(UUID id, DefinitionSubjectType subjectType,
                        AscriptionStatusType status) {
                return stubEntity(id, subjectType, status, UUID.randomUUID());
        }

        private AscriptionEntity stubEntity(UUID id, DefinitionSubjectType subjectType,
                        AscriptionStatusType status, UUID defId) {
                DefinitionEntity def = mock(DefinitionEntity.class);
                when(def.getId()).thenReturn(defId);
                when(def.getSubjectType()).thenReturn(subjectType);

                AscriptionEntity entity = mock(AscriptionEntity.class);
                when(entity.getId()).thenReturn(id);
                when(entity.getDefinition()).thenReturn(def);
                when(entity.getStatus()).thenReturn(status);

                return entity;
        }

        /**
         * Stubs transitionRepo.save() to return a mock with a non-null ID, letting
         * the lifecycle service proceed past the save-flush-detach-findById sequence.
         */
        private void stubTransitionSave() {
                UUID transId = UUID.randomUUID();
                AscriptionStatusTransitionEntity savedTransition = mock(AscriptionStatusTransitionEntity.class);
                when(savedTransition.getId()).thenReturn(transId);

                when(transitionRepo.save(any(AscriptionStatusTransitionEntity.class))).thenReturn(savedTransition);
                when(transitionRepo.findById(any(UUID.class))).thenReturn(java.util.Optional.of(savedTransition));
        }
}
