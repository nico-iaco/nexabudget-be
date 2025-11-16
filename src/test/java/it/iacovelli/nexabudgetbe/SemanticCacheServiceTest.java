package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.model.semantic_cache.CachedResponse;
import it.iacovelli.nexabudgetbe.repository.SemanticCacheRepository;
import it.iacovelli.nexabudgetbe.service.AiStudioEmbeddingClient;
import it.iacovelli.nexabudgetbe.service.SemanticCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private SemanticCacheRepository cacheRepository;

    @Mock
    private AiStudioEmbeddingClient embeddingClient;

    private SemanticCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new SemanticCacheService(mongoTemplate, cacheRepository, embeddingClient);
    }

    @Test
    void testSaveToCache_NewPrompt_ShouldSave() {
        // Arrange
        String prompt = "Pagamento bolletta elettricità";
        String response = "Bollette";
        List<Double> embedding = Arrays.asList(0.1, 0.2, 0.3);

        when(cacheRepository.findByPromptText(prompt)).thenReturn(Optional.empty());
        when(embeddingClient.getEmbedding(prompt)).thenReturn(embedding);

        // Act
        cacheService.saveToCache(prompt, response);

        // Assert
        verify(cacheRepository, times(1)).findByPromptText(prompt);
        verify(embeddingClient, times(1)).getEmbedding(prompt);
        verify(cacheRepository, times(1)).save(any(CachedResponse.class));
    }

    @Test
    void testSaveToCache_ExistingPromptSameResponse_ShouldNotSave() {
        // Arrange
        String prompt = "Pagamento bolletta elettricità";
        String response = "Bollette";
        CachedResponse existingEntry = new CachedResponse(prompt, response, Arrays.asList(0.1, 0.2, 0.3));

        when(cacheRepository.findByPromptText(prompt)).thenReturn(Optional.of(existingEntry));

        // Act
        cacheService.saveToCache(prompt, response);

        // Assert
        verify(cacheRepository, times(1)).findByPromptText(prompt);
        verify(embeddingClient, never()).getEmbedding(anyString());
        verify(cacheRepository, never()).save(any(CachedResponse.class));
    }

    @Test
    void testSaveToCache_ExistingPromptDifferentResponse_ShouldUpdate() {
        // Arrange
        String prompt = "Pagamento bolletta elettricità";
        String oldResponse = "Utilities";
        String newResponse = "Bollette";
        CachedResponse existingEntry = new CachedResponse(prompt, oldResponse, Arrays.asList(0.1, 0.2, 0.3));

        when(cacheRepository.findByPromptText(prompt)).thenReturn(Optional.of(existingEntry));

        // Act
        cacheService.saveToCache(prompt, newResponse);

        // Assert
        verify(cacheRepository, times(1)).findByPromptText(prompt);
        verify(embeddingClient, never()).getEmbedding(anyString());
        verify(cacheRepository, times(1)).save(existingEntry);
        assert existingEntry.getGeminiResponse().equals(newResponse);
    }

    @Test
    void testSaveToCache_MultipleSamePrompts_ShouldPreventDuplicates() {
        // Arrange
        String prompt = "Pagamento bolletta elettricità";
        String response = "Bollette";
        List<Double> embedding = Arrays.asList(0.1, 0.2, 0.3);

        // Prima chiamata: non esiste
        when(cacheRepository.findByPromptText(prompt))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new CachedResponse(prompt, response, embedding)));
        when(embeddingClient.getEmbedding(prompt)).thenReturn(embedding);

        // Act - Prima chiamata (salva)
        cacheService.saveToCache(prompt, response);

        // Act - Seconda chiamata (non salva, entry già presente)
        cacheService.saveToCache(prompt, response);

        // Assert
        verify(cacheRepository, times(2)).findByPromptText(prompt);
        verify(embeddingClient, times(1)).getEmbedding(prompt); // Solo la prima volta
        verify(cacheRepository, times(1)).save(any(CachedResponse.class)); // Solo la prima volta
    }
}

