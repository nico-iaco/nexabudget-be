package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.model.semantic_cache.CachedResponse;
import it.iacovelli.nexabudgetbe.repository.SemanticCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final MongoTemplate mongoTemplate;

    private final SemanticCacheRepository cacheRepository;

    private final AiStudioEmbeddingClient embeddingClient;

    @Value("${app.semanticCache.collectionName}")
    private String collectionName;

    @Value("${app.semanticCache.indexName}")
    private String indexName;

    // Limite di similarità: consideriamo "uguali" i prompt con similarità > 0.90
    private static final double SIMILARITY_THRESHOLD = 0.90;

    public SemanticCacheService(MongoTemplate mongoTemplate, SemanticCacheRepository cacheRepository, AiStudioEmbeddingClient embeddingClient) {
        this.mongoTemplate = mongoTemplate;
        this.cacheRepository = cacheRepository;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Cerca una risposta semanticamente simile nella cache.
     */
    public Optional<CachedResponse> findSimilar(String userPrompt) {
        // 1. Converti il prompt utente in un vettore
        List<Double> promptEmbedding = embeddingClient.getEmbedding(userPrompt);

        // 2. Crea l'operazione di vector search usando Document
        AggregationOperation vectorSearch = context -> new org.bson.Document("$vectorSearch",
                new org.bson.Document()
                        .append("index", indexName)
                        .append("path", "embedding")
                        .append("queryVector", promptEmbedding)
                        .append("numCandidates", 10)
                        .append("limit", 1)
        );

        // 3. Aggiungi uno stadio per aggiungere il punteggio di similarità
        AggregationOperation addScore = context -> new org.bson.Document("$addFields",
                new org.bson.Document("score",
                        new org.bson.Document("$meta", "vectorSearchScore")
                )
        );

        // 4. Filtra per soglia di similarità
        AggregationOperation matchThreshold = Aggregation.match(
                org.springframework.data.mongodb.core.query.Criteria.where("score").gte(SIMILARITY_THRESHOLD)
        );

        // 5. Unisci gli stadi
        Aggregation aggregation = Aggregation.newAggregation(
                vectorSearch,
                addScore,
                matchThreshold
        );

        // 6. Esegui la query
        AggregationResults<CachedResponse> results = mongoTemplate.aggregate(
                aggregation, collectionName, CachedResponse.class
        );

        log.debug("Semantic cache query results: {}", results.getMappedResults());

        return Optional.ofNullable(results.getUniqueMappedResult());
    }


    /**
     * Salva una nuova coppia prompt/risposta nella cache.
     * Prima controlla se esiste già un prompt identico per evitare duplicati.
     */
    public void saveToCache(String prompt, String response) {
        // Controlla se esiste già un prompt identico
        Optional<CachedResponse> existing = cacheRepository.findByPromptText(prompt);

        if (existing.isPresent()) {
            log.debug("Entry già presente nella cache per il prompt: {}", prompt);
            // Opzionalmente, potresti aggiornare la risposta se è cambiata
            CachedResponse cachedResponse = existing.get();
            if (!cachedResponse.getGeminiResponse().equals(response)) {
                log.debug("Aggiornamento risposta esistente nella cache");
                cachedResponse.setGeminiResponse(response);
                cacheRepository.save(cachedResponse);
            }
            return;
        }

        // Se non esiste, genera l'embedding e salva
        List<Double> embedding = embeddingClient.getEmbedding(prompt);
        CachedResponse newEntry = new CachedResponse(prompt, response, embedding);
        cacheRepository.save(newEntry);
        log.debug("Saved new entry in semantic cache: {}", newEntry);
    }


}
