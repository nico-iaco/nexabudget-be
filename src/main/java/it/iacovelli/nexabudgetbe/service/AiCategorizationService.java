package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.Category;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.text.Normalizer;
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

    /**
     * Tenta di associare una descrizione a una categoria esistente usando l'AI.
     * Usa la cache semantica per evitare chiamate ridondanti.
     */
    public Optional<Category> categorizeTransaction(String description, User user, TransactionType type) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }

        List<Category> availableCategories = categoryService.getAllAvailableCategoriesForUserAndType(user, type);
        if (availableCategories.isEmpty()) {
            return Optional.empty();
        }

        // 1. Cache semantica (scoped per utente e tipo)
        Optional<String> cached = semanticCacheService.findSimilar(description, user.getId(), type);
        if (cached.isPresent()) {
            String cachedName = cached.get();
            log.debug("Cache hit per '{}': '{}'", description, cachedName);
            return matchCategory(cachedName, availableCategories);
        }

        // 2. Chiamata AI
        String prompt = buildPrompt(description, availableCategories, type);
        try {
            log.debug("Categorizzazione AI per: '{}'", description);
            String raw = chatClient.call(new Prompt(prompt))
                    .getResult()
                    .getOutput()
                    .getText();

            String aiResponse = raw != null ? raw.trim() : NONE;

            // Gemini può rispondere con markdown bold (**Nome**) o virgolette — puliamo
            aiResponse = aiResponse.replaceAll("[*_`\"']+", "").trim();

            log.debug("Risposta AI per '{}': '{}'", description, aiResponse);

            if (NONE.equalsIgnoreCase(aiResponse) || aiResponse.isBlank()) {
                log.debug("AI non ha trovato categoria per: '{}'", description);
                return Optional.empty();
            }

            Optional<Category> matched = matchCategory(aiResponse, availableCategories);

            // Salva in cache solo se la risposta corrisponde a una categoria reale
            if (matched.isPresent()) {
                semanticCacheService.saveToCache(description, matched.get().getName(), user.getId(), type);
                log.info("Transazione '{}' categorizzata come '{}' (AI)", description, matched.get().getName());
            } else {
                log.debug("Risposta AI '{}' non corrisponde a nessuna categoria per '{}'", aiResponse, description);
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
        semanticCacheService.saveToCache(description, newCategory.getName(), user.getId(), type);
    }

    /**
     * Cerca la categoria con strategia a cascata:
     * 1. Corrispondenza esatta case-insensitive
     * 2. Corrispondenza dopo normalizzazione (accenti, spazi multipli)
     * 3. La risposta AI è contenuta nel nome categoria o viceversa
     */
    private Optional<Category> matchCategory(String aiResponse, List<Category> categories) {
        // 1. Exact case-insensitive
        Optional<Category> exact = categories.stream()
                .filter(c -> c.getName().equalsIgnoreCase(aiResponse))
                .findFirst();
        if (exact.isPresent()) return exact;

        // 2. Normalizzato (rimuove accenti, collassa spazi)
        String normalizedResponse = normalize(aiResponse);
        Optional<Category> normalized = categories.stream()
                .filter(c -> normalize(c.getName()).equalsIgnoreCase(normalizedResponse))
                .findFirst();
        if (normalized.isPresent()) return normalized;

        // 3. Containment (gestisce prefissi o risposte parziali dell'AI)
        return categories.stream()
                .filter(c -> normalize(c.getName()).contains(normalizedResponse)
                        || normalizedResponse.contains(normalize(c.getName())))
                .findFirst();
    }

    private String normalize(String s) {
        if (s == null) return "";
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "")   // rimuove diacritici
                         .replaceAll("\\s+", " ")
                         .trim()
                         .toLowerCase();
    }

    private String buildPrompt(String description, List<Category> categories, TransactionType type) {
        String typeLabel = type == TransactionType.OUT ? "USCITA (spesa)" : "ENTRATA (accredito)";
        String categoryList = categories.stream()
                .map(Category::getName)
                .collect(Collectors.joining("\n- ", "- ", ""));

        return """
                Sei un classificatore di transazioni bancarie italiane. Assegna la transazione alla categoria più appropriata.

                REGOLE:
                - Rispondi SOLO con il nome esatto di una categoria dalla lista
                - Se nessuna categoria è adatta, rispondi esattamente: NONE
                - Nessuna spiegazione, punteggiatura o testo aggiuntivo

                ESEMPI:
                Tipo: USCITA (spesa) | Descrizione: "ESSELUNGA SPA" → Alimentari e Supermercati
                Tipo: USCITA (spesa) | Descrizione: "NETFLIX.COM" → Abbonamenti
                Tipo: USCITA (spesa) | Descrizione: "Q8 CARBURANTE" → Trasporti
                Tipo: ENTRATA (accredito) | Descrizione: "BONIFICO STIPENDIO" → Stipendio
                Tipo: USCITA (spesa) | Descrizione: "FARMACIA CENTRALE" → Salute e Farmacia
                Tipo: USCITA (spesa) | Descrizione: "RISTORANTE DA MARIO" → Ristoranti e Bar

                ---
                Tipo: %s
                Descrizione: "%s"

                Categorie disponibili:
                %s

                Categoria:""".formatted(typeLabel, description, categoryList);
    }
}
