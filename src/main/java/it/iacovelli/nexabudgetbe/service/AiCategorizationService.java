package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AiCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(AiCategorizationService.class);
    private final CategoryService categoryService;
    private final SemanticCacheService semanticCacheService;
    private final GoogleGenAiChatModel chatClient;

    public AiCategorizationService(CategoryService categoryService, SemanticCacheService semanticCacheService,
                                   GoogleGenAiChatModel chatClient) {
        this.categoryService = categoryService;
        this.semanticCacheService = semanticCacheService;
        this.chatClient = chatClient;
    }

    /**
     * Tenta di associare una descrizione a una categoria esistente usando l'AI.
     */
    public Optional<Category> categorizeTransaction(String description, User user, TransactionType type) {
        // 1. Ottieni le categorie disponibili per l'utente (dal tuo CategoryService)
        List<Category> availableCategories = categoryService.getAllAvailableCategoriesForUserAndType(user, type);
        if (availableCategories.isEmpty()) {
            return Optional.empty(); // Non ci sono categorie a cui associare
        }

        // 2. Costruisci il prompt per l'AI
        String prompt = buildPrompt(description, availableCategories);

        Optional<String> similar = semanticCacheService.findSimilar(description);


        if (similar.isPresent()) {
            String aiResponseText = similar.get();
            log.debug("Risposta AI (cache): {}", aiResponseText);
            return availableCategories.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(aiResponseText))
                    .findFirst();
        }

        try {
            log.debug("Prompt: {}", prompt);
            String aiResponseText = chatClient.call(new Prompt(prompt))
                    .getResult()
                    .getOutput()
                    .getText();

            if (aiResponseText != null) {
                aiResponseText = aiResponseText.trim();
            } else {
                aiResponseText = "";
            }

            log.debug("Risposta AI: {}", aiResponseText);

            semanticCacheService.saveToCache(description, aiResponseText);

            // 4. Cerca la categoria corrispondente nella lista
            // (La risposta AI dovrebbe essere SOLO il nome della categoria)
            String finalAiResponseText = aiResponseText;
            return availableCategories.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(finalAiResponseText))
                    .findFirst();

        } catch (Exception e) {
            // Logga l'errore (e.g., API key non valida, quota superata, ecc.)
            log.error("Errore durante la categorizzazione AI: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void updateSemanticCache(String description, Category oldCategory, Category newCategory) {
        if (oldCategory == null) {
            semanticCacheService.saveToCache(description, newCategory.getName());
        } else {
            semanticCacheService.updateCache(description, newCategory.getName());
        }
    }

    private String buildPrompt(String description, List<Category> categories) {
        String categoryList = categories.stream()
                .map(Category::getName)
                .collect(Collectors.joining(", "));

        return "Sei un assistente finanziario. Assegna la seguente descrizione di transazione a una delle categorie disponibili.\n" +
                "Rispondi *solo ed esclusivamente* con il nome esatto di una delle categorie fornite.\n\n" +
                "Descrizione Transazione: \"" + description + "\"\n\n" +
                "Categorie Disponibili: [" + categoryList + "]\n\n" +
                "Categoria:";
    }

}