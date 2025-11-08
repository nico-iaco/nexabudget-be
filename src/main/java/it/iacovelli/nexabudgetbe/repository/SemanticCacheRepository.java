package it.iacovelli.nexabudgetbe.repository;

import it.iacovelli.nexabudgetbe.model.semantic_cache.CachedResponse;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SemanticCacheRepository extends MongoRepository<CachedResponse, String> {
}
