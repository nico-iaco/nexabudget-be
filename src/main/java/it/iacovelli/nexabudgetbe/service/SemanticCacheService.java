package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.model.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final VectorStore vectorStore;
    private final CacheManager cacheManager;

    // Soglia di similarità: valori > 0.82 considerati semanticamente equivalenti
    private static final double SIMILARITY_THRESHOLD = 0.82;

    // Soglia oltre la quale due voci sono considerate la stessa descrizione: usata per rimuovere,
    // alla correzione dell'utente, le voci precedenti (anche quelle salvate con id casuale) che
    // altrimenti competerebbero alla pari con quella corretta nella ricerca topK=1
    private static final double DUPLICATE_THRESHOLD = 0.97;
    private static final int DUPLICATE_SCAN_LIMIT = 10;

    static final String META_CATEGORY = "category";
    static final String META_CATEGORY_ID = "categoryId";
    static final String META_USER_ID = "userId";

    /**
     * Esito di un cache hit. {@code categoryId} è null per le voci salvate prima che venisse
     * memorizzato l'id: in quel caso il chiamante risolve la categoria per nome. {@code categoryName}
     * è null per un hit del match esatto, che memorizza solo l'id.
     */
    public record CacheHit(UUID categoryId, String categoryName) {}

    public SemanticCacheService(VectorStore vectorStore, CacheManager cacheManager) {
        this.vectorStore = vectorStore;
        this.cacheManager = cacheManager;
    }

    /**
     * Cerca prima il match esatto in Valkey (chiave = id deterministico della descrizione normalizzata), poi la
     * ricerca vettoriale. Un hit vettoriale con {@code categoryId} viene memorizzato come match esatto per la
     * descrizione cercata, così le occorrenze successive non pagano né l'embedding né la query su Atlas.
     */
    public Optional<CacheHit> findSimilar(String description, UUID userId) {
        String key = documentId(description, userId);
        UUID exact = getExact(key);
        if (exact != null) {
            log.debug("Cache hit esatto per: '{}' → {}", description, exact);
            return Optional.of(new CacheHit(exact, null));
        }

        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(description)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .topK(1)
                            .filterExpression(userFilter(userId))
                            .build()
            );

            if (results.isEmpty()) {
                log.debug("Cache miss per: '{}'", description);
                return Optional.empty();
            }

            Map<String, Object> metadata = results.getFirst().getMetadata();
            Object categoryMeta = metadata.get(META_CATEGORY);
            if (categoryMeta == null) return Optional.empty();

            CacheHit hit = new CacheHit(parseUuid(metadata.get(META_CATEGORY_ID)), categoryMeta.toString());
            log.debug("Cache hit per: '{}' → '{}' (id={})", description, hit.categoryName(), hit.categoryId());
            if (hit.categoryId() != null) {
                putExact(key, hit.categoryId());
            }
            return Optional.of(hit);

        } catch (Exception e) {
            log.warn("Errore ricerca cache semantica: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Salva (o sovrascrive) l'associazione descrizione → categoria. L'id del documento è derivato da
     * utente + descrizione normalizzata, quindi la stessa descrizione aggiorna la voce esistente
     * (upsert su {@code _id}) invece di aggiungerne una nuova.
     */
    public void saveToCache(String description, Category category, UUID userId) {
        putExact(documentId(description, userId), category.getId());
        try {
            vectorStore.add(List.of(buildDocument(description, category, userId)));
            log.debug("Cache salvata: '{}' → '{}' (user={})", description, category.getName(), userId);
        } catch (Exception e) {
            log.warn("Errore salvataggio cache semantica: {}", e.getMessage());
        }
    }

    /**
     * Correzione dell'utente: rimuove le voci quasi identiche alla descrizione (comprese quelle
     * salvate con id casuale prima dell'id deterministico) e salva quella corretta.
     */
    public void replaceInCache(String description, Category category, UUID userId) {
        String id = documentId(description, userId);
        try {
            List<String> duplicateIds = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(description)
                                    .similarityThreshold(DUPLICATE_THRESHOLD)
                                    .topK(DUPLICATE_SCAN_LIMIT)
                                    .filterExpression(userFilter(userId))
                                    .build())
                    .stream()
                    .map(Document::getId)
                    .filter(docId -> !docId.equals(id))
                    .toList();
            if (!duplicateIds.isEmpty()) {
                vectorStore.delete(duplicateIds);
                // Gli id deterministici sono anche le chiavi del match esatto (quelli casuali legacy non hanno chiave)
                duplicateIds.forEach(this::evictExact);
                log.debug("Rimosse {} voci duplicate dalla cache per '{}' (user={})", duplicateIds.size(), description, userId);
            }
        } catch (Exception e) {
            log.warn("Errore pulizia duplicati cache semantica: {}", e.getMessage());
        }
        saveToCache(description, category, userId);
    }

    /**
     * Rimuove il match esatto della descrizione: chiamato quando l'hit punta a una categoria che l'utente non ha
     * più (eliminata o unita in un'altra), altrimenti la chiave resterebbe su quell'id fino al TTL.
     */
    public void evictExactMatch(String description, UUID userId) {
        evictExact(documentId(description, userId));
    }

    static String documentId(String description, UUID userId) {
        String normalized = description.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes((userId + "|" + normalized).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private UUID getExact(String key) {
        try {
            Cache cache = cacheManager.getCache(CacheConfig.SEMANTIC_EXACT_CACHE);
            return cache != null ? parseUuid(cache.get(key, String.class)) : null;
        } catch (Exception e) {
            log.warn("Errore lettura cache esatta categorie: {}", e.getMessage());
            return null;
        }
    }

    private void putExact(String key, UUID categoryId) {
        try {
            Cache cache = cacheManager.getCache(CacheConfig.SEMANTIC_EXACT_CACHE);
            if (cache != null) {
                cache.put(key, categoryId.toString());
            }
        } catch (Exception e) {
            log.warn("Errore scrittura cache esatta categorie: {}", e.getMessage());
        }
    }

    private void evictExact(String key) {
        try {
            Cache cache = cacheManager.getCache(CacheConfig.SEMANTIC_EXACT_CACHE);
            if (cache != null) {
                cache.evict(key);
            }
        } catch (Exception e) {
            log.warn("Errore rimozione cache esatta categorie: {}", e.getMessage());
        }
    }

    private Document buildDocument(String description, Category category, UUID userId) {
        return Document.builder()
                .id(documentId(description, userId))
                .text(description)
                .metadata(Map.of(
                        META_CATEGORY, category.getName(),
                        META_CATEGORY_ID, category.getId().toString(),
                        META_USER_ID, userId.toString()
                ))
                .build();
    }

    private static String userFilter(UUID userId) {
        return "%s == '%s'".formatted(META_USER_ID, userId);
    }

    private static UUID parseUuid(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
