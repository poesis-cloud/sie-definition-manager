package io.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.poesis.sie.defman.entity.DefinitionEntity;
import io.poesis.sie.defman.entity.EffectorEntity;
import io.poesis.sie.defman.entity.MechanismEntity;
import io.poesis.sie.defman.entity.ReceptorEntity;
import io.poesis.sie.defman.repository.EffectorRepository;
import io.poesis.sie.defman.repository.MechanismRepository;
import io.poesis.sie.defman.repository.ReceptorRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MechanismServiceModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private EffectorRepository effectorRepo;

    @Mock
    private ReceptorRepository receptorRepo;

    private MechanismService service;

    @BeforeEach
    void setUp() {
        service = new MechanismService(
                mock(MechanismRepository.class),
                mock(StructureService.class),
                mock(ArchetypeService.class),
                effectorRepo,
                receptorRepo);
    }

    // ========================================================================
    // Creation: Declarative mode (no rule) — always passes
    // ========================================================================

    @Nested
    class CreationDeclarative {

        @Test
        void noRule_alwaysValid() {
            MechanismEntity mechanism = stubMechanism(null);
            assertDoesNotThrow(() -> service.validateModeCreation(mechanism));
        }

        @Test
        void emptyRule_alwaysValid() {
            MechanismEntity mechanism = stubMechanism("");
            assertDoesNotThrow(() -> service.validateModeCreation(mechanism));
        }

        @Test
        void blankRule_alwaysValid() {
            MechanismEntity mechanism = stubMechanism("   ");
            assertDoesNotThrow(() -> service.validateModeCreation(mechanism));
        }
    }

    // ========================================================================
    // Creation: Generative mode (rule present)
    // ========================================================================

    @Nested
    class CreationGenerative {

        @Test
        void generativeMode_noExistingPorts_valid() {
            MechanismEntity mechanism = stubMechanism("on(\"X\")\nsys.emit(\"Y\", {})");
            UUID defId = mechanism.getDefinition().getId();

            when(effectorRepo.findAllByMechanism_Definition_Id(defId)).thenReturn(List.of());
            when(receptorRepo.findAllByMechanism_Definition_Id(defId)).thenReturn(List.of());

            assertDoesNotThrow(() -> service.validateModeCreation(mechanism));
        }

        @Test
        void generativeMode_existingEffectors_rejected() {
            MechanismEntity mechanism = stubMechanism("on(\"X\")\nsys.emit(\"Y\", {})");
            UUID defId = mechanism.getDefinition().getId();

            EffectorEntity existing = mock(EffectorEntity.class);
            when(effectorRepo.findAllByMechanism_Definition_Id(defId)).thenReturn(List.of(existing));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateModeCreation(mechanism));
            assertTrue(ex.getMessage().contains("Generative mode conflict"));
            assertTrue(ex.getMessage().contains("Effector"));
        }

        @Test
        void generativeMode_existingReceptors_rejected() {
            MechanismEntity mechanism = stubMechanism("on(\"X\")\nsys.emit(\"Y\", {})");
            UUID defId = mechanism.getDefinition().getId();

            when(effectorRepo.findAllByMechanism_Definition_Id(defId)).thenReturn(List.of());
            ReceptorEntity existing = mock(ReceptorEntity.class);
            when(receptorRepo.findAllByMechanism_Definition_Id(defId)).thenReturn(List.of(existing));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateModeCreation(mechanism));
            assertTrue(ex.getMessage().contains("Generative mode conflict"));
            assertTrue(ex.getMessage().contains("Receptor"));
        }
    }

    // ========================================================================
    // Activation: Generative mode (rule present) — always passes
    // ========================================================================

    @Nested
    class ActivationGenerative {

        @Test
        void generativeMode_activationAlwaysValid() {
            MechanismEntity mechanism = stubMechanism("on(\"X\")\nsys.emit(\"Y\", {})");
            assertDoesNotThrow(() -> service.validateModeActivation(mechanism));
        }
    }

    // ========================================================================
    // Activation: Declarative mode (no rule) — requires ports
    // ========================================================================

    @Nested
    class ActivationDeclarative {

        @Test
        void declarativeMode_hasBothPorts_valid() {
            MechanismEntity mechanism = stubMechanism(null);
            UUID defId = mechanism.getDefinition().getId();

            EffectorEntity eff = mock(EffectorEntity.class);
            ReceptorEntity rec = mock(ReceptorEntity.class);
            when(effectorRepo.findAllByMechanism_Definition_IdAndStatusIn(eq(defId), anyCollection()))
                    .thenReturn(List.of(eff));
            when(receptorRepo.findAllByMechanism_Definition_IdAndStatusIn(eq(defId), anyCollection()))
                    .thenReturn(List.of(rec));

            assertDoesNotThrow(() -> service.validateModeActivation(mechanism));
        }

        @Test
        void declarativeMode_noEffectors_rejected() {
            MechanismEntity mechanism = stubMechanism(null);
            UUID defId = mechanism.getDefinition().getId();

            when(effectorRepo.findAllByMechanism_Definition_IdAndStatusIn(eq(defId), anyCollection()))
                    .thenReturn(List.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateModeActivation(mechanism));
            assertTrue(ex.getMessage().contains("Declarative mode"));
            assertTrue(ex.getMessage().contains("Effector"));
        }

        @Test
        void declarativeMode_noReceptors_rejected() {
            MechanismEntity mechanism = stubMechanism(null);
            UUID defId = mechanism.getDefinition().getId();

            EffectorEntity eff = mock(EffectorEntity.class);
            when(effectorRepo.findAllByMechanism_Definition_IdAndStatusIn(eq(defId), anyCollection()))
                    .thenReturn(List.of(eff));
            when(receptorRepo.findAllByMechanism_Definition_IdAndStatusIn(eq(defId), anyCollection()))
                    .thenReturn(List.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.validateModeActivation(mechanism));
            assertTrue(ex.getMessage().contains("Declarative mode"));
            assertTrue(ex.getMessage().contains("Receptor"));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private MechanismEntity stubMechanism(String rule) {
        UUID defId = UUID.randomUUID();
        DefinitionEntity defEntity = mock(DefinitionEntity.class);
        when(defEntity.getId()).thenReturn(defId);

        ObjectNode stmt = MAPPER.createObjectNode();
        if (rule != null) {
            stmt.put("rule", rule);
        }

        MechanismEntity mechanism = mock(MechanismEntity.class);
        when(mechanism.getDefinition()).thenReturn(defEntity);
        when(mechanism.getStatement()).thenReturn(stmt);

        return mechanism;
    }
}
