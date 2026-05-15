package it.iacovelli.nexabudgetbe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Models;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.AiCategorizationService;
import it.iacovelli.nexabudgetbe.service.CategoryService;
import it.iacovelli.nexabudgetbe.service.SemanticCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiCategorizationServiceTest {

    @Mock
    private CategoryService categoryService;

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private Models genaiModels;

    private AiCategorizationService service;

    private User user;
    private Category alimentari;
    private Category trasporti;
    private Category abbonamenti;
    private Category stipendio;

    @BeforeEach
    void setUp() {
        service = new AiCategorizationService(categoryService, semanticCacheService, genaiModels, new ObjectMapper());

        user = User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hash")
                .build();

        alimentari = Category.builder().id(UUID.randomUUID()).name("Alimentari e Supermercati").build();
        trasporti = Category.builder().id(UUID.randomUUID()).name("Trasporti").build();
        abbonamenti = Category.builder().id(UUID.randomUUID()).name("Abbonamenti").build();
        stipendio = Category.builder().id(UUID.randomUUID()).name("Stipendio").build();
    }

    private GenerateContentResponse fakeResponse(String text) {
        String safeText = text == null ? "" : text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return GenerateContentResponse.fromJson(
                "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + safeText + "\"}]}}]}");
    }

    // ─── Guard: descrizioni non valide ──────────────────────────────────────────

    @Test
    void nullDescription_returnsEmpty() {
        Optional<Category> result = service.categorizeTransaction(null, user, TransactionType.OUT);
        assertTrue(result.isEmpty());
        verifyNoInteractions(categoryService, genaiModels, semanticCacheService);
    }

    @Test
    void blankDescription_returnsEmpty() {
        Optional<Category> result = service.categorizeTransaction("   ", user, TransactionType.OUT);
        assertTrue(result.isEmpty());
        verifyNoInteractions(categoryService, genaiModels, semanticCacheService);
    }

    @Test
    void noAvailableCategories_returnsEmpty() {
        when(categoryService.getAllAvailableCategoriesForUser(user)).thenReturn(List.of());

        Optional<Category> result = service.categorizeTransaction("Esselunga", user, TransactionType.OUT);
        assertTrue(result.isEmpty());
        verifyNoInteractions(genaiModels, semanticCacheService);
    }

    // ─── Cache hit ───────────────────────────────────────────────────────────────

    @Test
    void cacheHit_returnsMatchedCategoryWithoutCallingAI() {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari, trasporti));
        when(semanticCacheService.findSimilar("Esselunga", user.getId()))
                .thenReturn(Optional.of("Alimentari e Supermercati"));

        Optional<Category> result = service.categorizeTransaction("Esselunga", user, TransactionType.OUT);

        assertTrue(result.isPresent());
        assertEquals("Alimentari e Supermercati", result.get().getName());
        verifyNoInteractions(genaiModels);
    }

    @Test
    void cacheHit_butCategoryNotInUserList_returnsEmpty() {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(trasporti));
        when(semanticCacheService.findSimilar("Esselunga", user.getId()))
                .thenReturn(Optional.of("Alimentari e Supermercati"));

        Optional<Category> result = service.categorizeTransaction("Esselunga", user, TransactionType.OUT);

        assertTrue(result.isEmpty());
        verifyNoInteractions(genaiModels);
    }

    // ─── AI: risposta esatta ─────────────────────────────────────────────────────

    @Test
    void aiReturnsExactMatch_returnsCategoryAndSavesToCache() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari, trasporti));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("Alimentari e Supermercati"));

        Optional<Category> result = service.categorizeTransaction("ESSELUNGA SPA", user, TransactionType.OUT);

        assertTrue(result.isPresent());
        assertEquals("Alimentari e Supermercati", result.get().getName());
        verify(semanticCacheService).saveToCache("ESSELUNGA SPA", "Alimentari e Supermercati", user.getId());
    }

    @Test
    void aiReturnsCaseInsensitiveMatch() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari, trasporti));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("alimentari e supermercati"));

        Optional<Category> result = service.categorizeTransaction("Esselunga", user, TransactionType.OUT);

        assertTrue(result.isPresent());
        assertEquals("Alimentari e Supermercati", result.get().getName());
    }

    // ─── Categorizzazione mista IN/OUT sulla stessa lista ────────────────────────

    @Test
    void allCategoriesAvailableRegardlessOfTransactionFlow() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari, trasporti, stipendio));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("Stipendio"));

        Optional<Category> result = service.categorizeTransaction("Bonifico stipendio", user, TransactionType.IN);

        assertTrue(result.isPresent());
        assertEquals("Stipendio", result.get().getName());
    }

    // ─── AI: pulizia markdown ────────────────────────────────────────────────────

    @Test
    void aiResponseWrappedInMarkdownBold_stillMatches() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari, abbonamenti));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("**Abbonamenti**"));

        Optional<Category> result = service.categorizeTransaction("NETFLIX.COM", user, TransactionType.OUT);

        assertTrue(result.isPresent());
        assertEquals("Abbonamenti", result.get().getName());
    }

    @Test
    void aiResponseWithTrailingWhitespace_stillMatches() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(trasporti));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("  Trasporti  \n"));

        Optional<Category> result = service.categorizeTransaction("Q8 CARBURANTE", user, TransactionType.OUT);

        assertTrue(result.isPresent());
        assertEquals("Trasporti", result.get().getName());
    }

    // ─── AI: risposta con accento diverso non matcha (serve nome esatto) ──────────

    @Test
    void aiResponseWithDifferentAccents_doesNotMatch() throws Exception {
        Category salute = Category.builder().id(UUID.randomUUID()).name("Salute e Farmacìa").build();
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(salute));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("Salute e Farmacia"));

        Optional<Category> result = service.categorizeTransaction("FARMACIA CENTRALE", user, TransactionType.OUT);

        assertFalse(result.isPresent());
    }

    // ─── AI: risposta parziale non matcha (serve nome esatto) ────────────────────

    @Test
    void aiReturnsPartialName_doesNotMatch() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("Alimentari"));

        Optional<Category> result = service.categorizeTransaction("Esselunga", user, TransactionType.OUT);

        assertFalse(result.isPresent());
    }

    // ─── AI: NONE e risposta non matchante ──────────────────────────────────────

    @Test
    void aiReturnsNONE_returnsEmptyAndDoesNotSaveToCache() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("NONE"));

        Optional<Category> result = service.categorizeTransaction("Pagamento generico", user, TransactionType.OUT);

        assertTrue(result.isEmpty());
        verify(semanticCacheService, never()).saveToCache(any(), any(), any());
    }

    @Test
    void aiReturnsBlankResponse_returnsEmptyAndDoesNotSaveToCache() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("   "));

        Optional<Category> result = service.categorizeTransaction("Qualcosa", user, TransactionType.OUT);

        assertTrue(result.isEmpty());
        verify(semanticCacheService, never()).saveToCache(any(), any(), any());
    }

    @Test
    void aiReturnsUnknownCategory_returnsEmptyAndDoesNotSaveToCache() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari, trasporti));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(fakeResponse("Categoria Inventata"));

        Optional<Category> result = service.categorizeTransaction("Descrizione strana", user, TransactionType.OUT);

        assertTrue(result.isEmpty());
        verify(semanticCacheService, never()).saveToCache(any(), any(), any());
    }

    @Test
    void aiReturnsEmptyText_returnsEmptyAndDoesNotSaveToCache() throws Exception {
        // Simula una risposta senza testo (candidates vuoti)
        GenerateContentResponse emptyResp = GenerateContentResponse.fromJson("{\"candidates\":[]}");
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenReturn(emptyResp);

        Optional<Category> result = service.categorizeTransaction("Test", user, TransactionType.OUT);

        assertTrue(result.isEmpty());
        verify(semanticCacheService, never()).saveToCache(any(), any(), any());
    }

    // ─── AI: errori ──────────────────────────────────────────────────────────────

    @Test
    void aiThrowsException_returnsEmptyAndDoesNotSaveToCache() throws Exception {
        when(categoryService.getAllAvailableCategoriesForUser(user))
                .thenReturn(List.of(alimentari));
        when(semanticCacheService.findSimilar(anyString(), any())).thenReturn(Optional.empty());
        when(genaiModels.generateContent(nullable(String.class), any(com.google.genai.types.Content.class), any(GenerateContentConfig.class)))
                .thenThrow(new RuntimeException("Network error"));

        Optional<Category> result = service.categorizeTransaction("Esselunga", user, TransactionType.OUT);

        assertTrue(result.isEmpty());
        verify(semanticCacheService, never()).saveToCache(any(), any(), any());
    }

    // ─── updateSemanticCache ─────────────────────────────────────────────────────

    @Test
    void updateSemanticCache_delegatesToSemanticCacheService() {
        Category oldCat = Category.builder().id(UUID.randomUUID()).name("Vecchia").build();
        Category newCat = Category.builder().id(UUID.randomUUID()).name("Nuova").build();

        service.updateSemanticCache("Esselunga", oldCat, newCat, user, TransactionType.OUT);

        verify(semanticCacheService).saveToCache("Esselunga", "Nuova", user.getId());
    }
}
