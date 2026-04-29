package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AiCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(AiCategorizationService.class);

    private static final String NONE = "NONE";

    private final CategoryService categoryService;
    private final SemanticCacheService semanticCacheService;
    private final GoogleGenAiChatModel chatClient;

    public AiCategorizationService(CategoryService categoryService, SemanticCacheService semanticCacheService,
                                   GoogleGenAiChatModel chatClient) {
        this.categoryService = categoryService;
        this.semanticCacheService = semanticCacheService;
        this.chatClient = chatClient;
    }

    public record AiCategoryResponse(String category) {}

    /**
     * Tenta di associare una descrizione a una categoria esistente usando l'AI.
     * Usa la cache semantica per evitare chiamate ridondanti.
     */
    public Optional<Category> categorizeTransaction(String description, User user, TransactionType type) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }

        List<Category> availableCategories = categoryService.getAllAvailableCategoriesForUser(user);
        if (availableCategories.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> cached = semanticCacheService.findSimilar(description, user.getId());
        if (cached.isPresent()) {
            String cachedName = cached.get();
            log.debug("Cache hit per '{}': '{}'", description, cachedName);
            return availableCategories.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(cachedName))
                    .findFirst();
        }

        // 2. Chiamata AI
        BeanOutputConverter<AiCategoryResponse> converter = new BeanOutputConverter<>(AiCategoryResponse.class);
        String prompt = buildPrompt(description, availableCategories, type);

        try {
            log.debug("Categorizzazione AI per: '{}'", description);
            String raw = chatClient.call(new Prompt(prompt))
                    .getResult()
                    .getOutput()
                    .getText();

            // Extract JSON object from raw text, stripping any thinking tokens preamble
            String json = raw.replaceAll("(?s)^.*?(\\{[^{}]*\\}).*$", "$1");
            AiCategoryResponse response = converter.convert(json);
            String aiResponse = response != null && response.category() != null ? response.category().replaceAll("[*_`\"']+", "").trim() : NONE;

            log.debug("Risposta AI per '{}': '{}'", description, aiResponse);

            if (NONE.equalsIgnoreCase(aiResponse) || aiResponse.isBlank()) {
                log.debug("AI non ha trovato categoria per: '{}'", description);
                return Optional.empty();
            }

            Optional<Category> matched = availableCategories.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(aiResponse))
                    .findFirst();

            if (matched.isPresent()) {
                semanticCacheService.saveToCache(description, matched.get().getName(), user.getId());
                log.info("Transazione '{}' categorizzata come '{}' (AI)", description, matched.get().getName());
            } else {
                log.warn("Risposta AI '{}' non corrisponde a nessuna categoria per '{}'", aiResponse, description);
            }

            return matched;

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("Quota Gemini API esaurita, categorizzazione saltata per: '{}'", description);
            } else {
                log.error("Errore HTTP client Gemini ({}): {}", e.getStatusCode(), e.getMessage());
            }
            return Optional.empty();
        } catch (HttpServerErrorException e) {
            log.error("Errore server Gemini ({}), categorizzazione saltata per: '{}'", e.getStatusCode(), description);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Errore inatteso nella categorizzazione AI per '{}': {}", description, e.getMessage());
            return Optional.empty();
        }
    }

    public void updateSemanticCache(String description, Category oldCategory, Category newCategory, User user, TransactionType type) {
        semanticCacheService.saveToCache(description, newCategory.getName(), user.getId());
    }

    private String buildPrompt(String description, List<Category> categories, TransactionType type) {
        String typeLabel = type == TransactionType.OUT ? "USCITA (spesa)" : "ENTRATA (accredito)";
        String categoryList = categories.stream()
                .map(Category::getName)
                .collect(Collectors.joining("\n- ", "- ", ""));

        return """
                Sei un classificatore di transazioni bancarie italiane.

                REGOLE OBBLIGATORIE:
                - Rispondi con il nome ESATTO di una delle categorie elencate sotto
                - NON inventare categorie nuove o simili
                - Se nessuna categoria è adatta alla transazione, usa NONE come valore
                - In caso di dubbio, preferisci NONE a una categoria sbagliata

                FORMATO DI OUTPUT RICHIESTO:
                Rispondi esclusivamente con un oggetto JSON, senza markdown, senza spiegazioni.
                Esempio: {"category": "Alimentari"}
                Se nessuna categoria è adatta: {"category": "NONE"}

                CATEGORIE DISPONIBILI (scegli solo da questa lista):
                %s

                TRANSAZIONE DA CLASSIFICARE:
                Tipo: %s
                Descrizione: "%s"
                """.formatted(categoryList, typeLabel, description);
    }
}
