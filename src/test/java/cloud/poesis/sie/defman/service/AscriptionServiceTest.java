package cloud.poesis.sie.defman.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.poesis.sie.defman.entity.AscriptionEntity;
import cloud.poesis.sie.defman.exception.ResourceNotFoundException;
import cloud.poesis.sie.defman.repository.AscriptionRepository;

@ExtendWith(MockitoExtension.class)
class AscriptionServiceTest {

    @Mock
    private AscriptionRepository ascriptionRepository;

    private AscriptionService service;

    @BeforeEach
    void setUp() {
        service = new AscriptionService(ascriptionRepository);
    }

    @Test
    void getById_returnsEntity_whenFound() {
        UUID id = UUID.randomUUID();
        AscriptionEntity entity = org.mockito.Mockito.mock(AscriptionEntity.class);
        when(ascriptionRepository.findById(id)).thenReturn(Optional.of(entity));

        AscriptionEntity result = service.getById(id);

        assertNotNull(result);
        assertEquals(entity, result);
    }

    @Test
    void getById_throwsResourceNotFound_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(ascriptionRepository.findById(id)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> service.getById(id));
        assertEquals(id, ex.getResourceId());
    }
}
