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
import java.util.UUID;

@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final VectorStore vectorStore;

    // Soglia di similarità: valori > 0.82 considerati semanticamente equivalenti
    private static final double SIMILARITY_THRESHOLD = 0.82;

    public SemanticCacheService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public Optional<String> findSimilar(String description, UUID userId) {
        try {
            String filter = "userId == '%s'".formatted(userId.toString());

            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(description)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .topK(1)
                            .filterExpression(filter)
                            .build()
            );

            if (results.isEmpty()) {
                log.debug("Cache miss per: '{}'", description);
                return Optional.empty();
            }

            Object categoryMeta = results.getFirst().getMetadata().get("category");
            if (categoryMeta == null) return Optional.empty();

            log.debug("Cache hit per: '{}' → '{}'", description, categoryMeta);
            return Optional.of(categoryMeta.toString());

        } catch (Exception e) {
            log.warn("Errore ricerca cache semantica: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void saveToCache(String description, String categoryName, UUID userId) {
        try {
            Document document = Document.builder()
                    .text(description)
                    .metadata(Map.of(
                            "category", categoryName,
                            "userId", userId.toString()
                    ))
                    .build();
            vectorStore.add(List.of(document));
            log.debug("Cache salvata: '{}' → '{}' (user={})", description, categoryName, userId);
        } catch (Exception e) {
            log.warn("Errore salvataggio cache semantica: {}", e.getMessage());
        }
    }
}
