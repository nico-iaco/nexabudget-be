package it.iacovelli.nexabudgetbe.config;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.service.AiCategorizationService;
import it.iacovelli.nexabudgetbe.service.ExchangeRateService;
import it.iacovelli.nexabudgetbe.service.SemanticCacheService;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@TestConfiguration
@Profile("test")
public class TestConfig {

    /**
     * Helper to hard-delete all accounts and transactions (bypassing @SQLRestriction),
     * needed for test teardown when soft-deleted rows exist.
     */
    @Component
    @Profile("test")
    public static class TestDataCleaner {

        @Autowired
        private TransactionRepository transactionRepository;

        @Autowired
        private AccountRepository accountRepository;

        @Transactional
        public void hardDeleteAllTransactions() {
            transactionRepository.hardDeleteAll();
        }

        @Transactional
        public void hardDeleteAllAccounts() {
            accountRepository.hardDeleteAll();
        }
    }

    @Bean
    @Primary
    public VectorStore vectorStore() {
        return Mockito.mock(VectorStore.class);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        return Mockito.mock(EmbeddingModel.class);
    }

    @Bean
    @Primary
    public SemanticCacheService semanticCacheService() {
        SemanticCacheService mockService = Mockito.mock(SemanticCacheService.class);

        doNothing().when(mockService).saveToCache(anyString(), anyString(), any(UUID.class), any(TransactionType.class));
        when(mockService.findSimilar(anyString(), any(UUID.class), any(TransactionType.class))).thenReturn(Optional.empty());

        return mockService;
    }

    @Bean
    @Primary
    public AiCategorizationService aiCategorizationService() {
        AiCategorizationService mockService = Mockito.mock(AiCategorizationService.class);

        doNothing().when(mockService).updateSemanticCache(
                anyString(), any(Category.class), any(Category.class), any(User.class), any(TransactionType.class));
        when(mockService.categorizeTransaction(anyString(), any(), any())).thenReturn(Optional.empty());

        return mockService;
    }

    @Bean
    @Primary
    public ExchangeRateService exchangeRateService() {
        ExchangeRateService mockService = Mockito.mock(ExchangeRateService.class);
        when(mockService.getRate(anyString(), anyString())).thenReturn(Optional.empty());
        return mockService;
    }
}
