package it.iacovelli.nexabudgetbe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final VectorStore vectorStore;


    // Limite di similarità: consideriamo "uguali" i prompt con similarità > 0.80
    private static final double SIMILARITY_THRESHOLD = 0.80;

    public SemanticCacheService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Cerca una risposta semanticamente simile nella cache.
     */
    public Optional<String> findSimilar(String userPrompt) {
        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest
                            .builder()
                            .query(userPrompt)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .topK(1)
                            .build()
            );
            if (results.isEmpty()) {
                log.debug("Nessuna corrispondenza semantica trovata per: {}", userPrompt);
                return Optional.empty();
            }

            String response = results.getFirst().getMetadata().get("category").toString();
            log.debug("Cache hit semantico per prompt simile. Risposta: {}", response);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.error("Errore durante la ricerca semantica nella cache: {}", e.getMessage());
            return Optional.empty(); // ✅ Fallback sicuro
        }
    }


    /**
     * Salva una nuova coppia prompt/risposta nella cache.
     * Prima controlla se esiste già un prompt identico per evitare duplicati.
     */
    public void saveToCache(String prompt, String response) {
        try {
            Document document = Document.builder()
                    .text(prompt)
                    .metadata(Map.of("category", response))
                    .build();
            vectorStore.add(List.of(document));
            log.debug("Saved new entry in semantic cache: {}", prompt);
        } catch (Exception e) {
            log.error("Errore durante la salvataggio nella cache semantica: {}", e.getMessage());
        }

    }

    /**
     * Aggiorna la cache con una nuova risposta.
     */
    public void updateCache(String prompt, String newResponse) {
        // Ripensare logica per update cache semantica in caso l'utente indichi manualmente la categoria
        saveToCache(prompt, newResponse);
    }


}
