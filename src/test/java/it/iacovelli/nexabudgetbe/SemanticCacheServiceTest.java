package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.service.SemanticCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private VectorStore vectorStore;

    private SemanticCacheService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SemanticCacheService(vectorStore);
    }

    // ─── findSimilar ─────────────────────────────────────────────────────────────

    @Test
    void findSimilar_cacheHit_returnsCategoryName() {
        Document doc = Document.builder()
                .text("Esselunga")
                .metadata(java.util.Map.of("category", "Alimentari e Supermercati"))
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        Optional<String> result = service.findSimilar("Esselunga SPA", userId);

        assertTrue(result.isPresent());
        assertEquals("Alimentari e Supermercati", result.get());
    }

    @Test
    void findSimilar_noResults_returnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        Optional<String> result = service.findSimilar("Transazione sconosciuta", userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void findSimilar_vectorStoreThrows_returnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("MongoDB unavailable"));

        Optional<String> result = service.findSimilar("Esselunga", userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void findSimilar_includesUserInFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.findSimilar("Test", userId);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest request = captor.getValue();
        String filter = request.getFilterExpression().toString();
        assertTrue(filter.contains(userId.toString()), "Il filtro deve contenere lo userId");
    }

    @Test
    void findSimilar_differentUsers_useDifferentFilters() {
        UUID userId2 = UUID.randomUUID();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.findSimilar("Esselunga", userId);
        service.findSimilar("Esselunga", userId2);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(captor.capture());

        List<SearchRequest> requests = captor.getAllValues();
        String filter1 = requests.get(0).getFilterExpression().toString();
        String filter2 = requests.get(1).getFilterExpression().toString();
        assertNotEquals(filter1, filter2);
    }

    // ─── saveToCache ─────────────────────────────────────────────────────────────

    @Test
    void saveToCache_addsDocumentWithCorrectMetadata() {
        service.saveToCache("Esselunga", "Alimentari e Supermercati", userId);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> docs = captor.getValue();
        assertEquals(1, docs.size());

        Document doc = docs.get(0);
        assertEquals("Esselunga", doc.getText());
        assertEquals("Alimentari e Supermercati", doc.getMetadata().get("category"));
        assertEquals(userId.toString(), doc.getMetadata().get("userId"));
        assertFalse(doc.getMetadata().containsKey("transactionType"), "transactionType non deve più essere in cache");
    }

    @Test
    void saveToCache_vectorStoreThrows_doesNotPropagate() {
        doThrow(new RuntimeException("MongoDB unavailable")).when(vectorStore).add(any());

        assertDoesNotThrow(() ->
                service.saveToCache("Esselunga", "Alimentari", userId));
    }
}
