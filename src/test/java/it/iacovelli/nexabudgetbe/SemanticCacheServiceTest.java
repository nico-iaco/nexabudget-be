package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.model.Category;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock
    private VectorStore vectorStore;

    private SemanticCacheService service;

    private final UUID userId = UUID.randomUUID();
    private final Category alimentari = Category.builder().id(UUID.randomUUID()).name("Alimentari e Supermercati").build();

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

        Optional<SemanticCacheService.CacheHit> result = service.findSimilar("Esselunga SPA", userId);

        assertTrue(result.isPresent());
        assertEquals("Alimentari e Supermercati", result.get().categoryName());
        assertNull(result.get().categoryId(), "Voce legacy senza categoryId");
    }

    @Test
    void findSimilar_cacheHit_returnsCategoryId() {
        Document doc = Document.builder()
                .text("Esselunga")
                .metadata(Map.of("category", "Alimentari e Supermercati", "categoryId", alimentari.getId().toString()))
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        Optional<SemanticCacheService.CacheHit> result = service.findSimilar("Esselunga", userId);

        assertTrue(result.isPresent());
        assertEquals(alimentari.getId(), result.get().categoryId());
    }

    @Test
    void findSimilar_malformedCategoryId_fallsBackToNameOnly() {
        Document doc = Document.builder()
                .text("Esselunga")
                .metadata(Map.of("category", "Alimentari e Supermercati", "categoryId", "non-un-uuid"))
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        Optional<SemanticCacheService.CacheHit> result = service.findSimilar("Esselunga", userId);

        assertTrue(result.isPresent());
        assertNull(result.get().categoryId());
        assertEquals("Alimentari e Supermercati", result.get().categoryName());
    }

    @Test
    void findSimilar_noResults_returnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        Optional<SemanticCacheService.CacheHit> result = service.findSimilar("Transazione sconosciuta", userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void findSimilar_vectorStoreThrows_returnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("MongoDB unavailable"));

        Optional<SemanticCacheService.CacheHit> result = service.findSimilar("Esselunga", userId);

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
        service.saveToCache("Esselunga", alimentari, userId);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> docs = captor.getValue();
        assertEquals(1, docs.size());

        Document doc = docs.get(0);
        assertEquals("Esselunga", doc.getText());
        assertEquals("Alimentari e Supermercati", doc.getMetadata().get("category"));
        assertEquals(alimentari.getId().toString(), doc.getMetadata().get("categoryId"));
        assertEquals(userId.toString(), doc.getMetadata().get("userId"));
        assertFalse(doc.getMetadata().containsKey("transactionType"), "transactionType non deve più essere in cache");
    }

    @Test
    void saveToCache_vectorStoreThrows_doesNotPropagate() {
        doThrow(new RuntimeException("MongoDB unavailable")).when(vectorStore).add(any());

        assertDoesNotThrow(() ->
                service.saveToCache("Esselunga", alimentari, userId));
    }

    @Test
    void saveToCache_sameDescription_usesSameDocumentId() {
        Category trasporti = Category.builder().id(UUID.randomUUID()).name("Trasporti").build();

        service.saveToCache("Esselunga SPA", alimentari, userId);
        service.saveToCache("  esselunga   spa ", trasporti, userId);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(captor.capture());
        assertEquals(captor.getAllValues().get(0).getFirst().getId(), captor.getAllValues().get(1).getFirst().getId(),
                "La stessa descrizione (a meno di maiuscole/spazi) deve sovrascrivere la voce esistente");
    }

    @Test
    void saveToCache_differentUsersOrDescriptions_useDifferentDocumentIds() {
        UUID userId2 = UUID.randomUUID();

        service.saveToCache("Esselunga", alimentari, userId);
        service.saveToCache("Esselunga", alimentari, userId2);
        service.saveToCache("Coop", alimentari, userId);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(3)).add(captor.capture());
        List<String> ids = captor.getAllValues().stream().map(docs -> docs.getFirst().getId()).toList();
        assertEquals(3, ids.stream().distinct().count());
    }

    // ─── replaceInCache ──────────────────────────────────────────────────────────

    @Test
    void replaceInCache_deletesNearDuplicatesAndSavesCorrection() {
        Category trasporti = Category.builder().id(UUID.randomUUID()).name("Trasporti").build();
        Document legacy = Document.builder().id("legacy-random-id").text("Esselunga")
                .metadata(Map.of("category", "Alimentari e Supermercati")).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(legacy));

        service.replaceInCache("Esselunga", trasporti, userId);

        ArgumentCaptor<SearchRequest> searchCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(searchCaptor.capture());
        assertTrue(searchCaptor.getValue().getFilterExpression().toString().contains(userId.toString()));
        verify(vectorStore).delete(List.of("legacy-random-id"));

        ArgumentCaptor<List<Document>> addCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(addCaptor.capture());
        assertEquals("Trasporti", addCaptor.getValue().getFirst().getMetadata().get("category"));
    }

    @Test
    void replaceInCache_doesNotDeleteTheEntryBeingUpserted() {
        service.saveToCache("Esselunga", alimentari, userId);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document existing = captor.getValue().getFirst();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(existing));

        service.replaceInCache("Esselunga", alimentari, userId);

        verify(vectorStore, never()).delete(anyList());
        verify(vectorStore, times(2)).add(any());
    }

    @Test
    void replaceInCache_cleanupFails_stillSavesCorrection() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("MongoDB unavailable"));

        assertDoesNotThrow(() -> service.replaceInCache("Esselunga", alimentari, userId));

        verify(vectorStore).add(any());
    }
}
