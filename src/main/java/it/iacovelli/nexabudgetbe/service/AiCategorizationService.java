package it.iacovelli.nexabudgetbe.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AiCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(AiCategorizationService.class);
    private final CategoryService categoryService;
    private final Client client;

    // Configurazione per una risposta "secca"
    private final GenerateContentConfig generationConfig = GenerateContentConfig.builder()
            .temperature(0.1f) // Bassa "creatività"
            .topK(1f)
            .maxOutputTokens(50) // Risposta "secca"
            .build();

    public AiCategorizationService(CategoryService categoryService, @Value("${google.ai.apiKey}") String apiKey) {
        this.categoryService = categoryService;

        this.client = Client.builder().apiKey(apiKey).build();
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

        try {
            // 3. Chiama l'API di Gemini
            GenerateContentResponse response = client.models.generateContent(
                    "gemini-2.5-flash-lite",
                    prompt,
                    generationConfig);

            String aiResponseText = response.text() != null ? response.text().trim() : "";

            log.debug("Risposta AI: {}", aiResponseText);

            // 4. Cerca la categoria corrispondente nella lista
            // (La risposta AI dovrebbe essere SOLO il nome della categoria)
            return availableCategories.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(aiResponseText))
                    .findFirst();

        } catch (Exception e) {
            // Logga l'errore (e.g., API key non valida, quota superata, ecc.)
            log.error("Errore durante la categorizzazione AI: {}", e.getMessage());
            return Optional.empty();
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