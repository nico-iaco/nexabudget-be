package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.semantic_cache.CachedResponse;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SemanticCacheRepository extends MongoRepository<CachedResponse, String> {

    /**
     * Trova una entry nella cache tramite il testo esatto del prompt.
     * Questo metodo è usato per evitare duplicati.
     */
    Optional<CachedResponse> findByPromptText(String promptText);
}
