package it.iacovelli.nexabudgetbe.config;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.service.AiCategorizationService;
import it.iacovelli.nexabudgetbe.service.SemanticCacheService;
import org.mockito.Mockito;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@TestConfiguration
@Profile("test")
public class TestConfig {

    @Bean
    @Primary
    public VectorStore vectorStore() {
        return Mockito.mock(VectorStore.class);
    }

    @Bean
    @Primary
    public SemanticCacheService semanticCacheService() {
        SemanticCacheService mockService = Mockito.mock(SemanticCacheService.class);

        doNothing().when(mockService).saveToCache(anyString(), anyString());
        doNothing().when(mockService).updateCache(anyString(), anyString());
        when(mockService.findSimilar(anyString())).thenReturn(Optional.empty());

        return mockService;
    }

    @Bean
    @Primary
    public AiCategorizationService aiCategorizationService() {
        AiCategorizationService mockService = Mockito.mock(AiCategorizationService.class);

        doNothing().when(mockService).updateSemanticCache(anyString(), any(Category.class), any(Category.class));
        when(mockService.categorizeTransaction(anyString(), any(), any())).thenReturn(Optional.empty());

        return mockService;
    }
}

