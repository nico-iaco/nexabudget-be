package it.iacovelli.nexabudgetbe.config;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.vectorstore.mongodb.atlas.MongoDBAtlasVectorStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

@Configuration
public class MongoConfig {

    private final MongoMappingContext mappingContext;

    public MongoConfig(MongoMappingContext mappingContext) {
        this.mappingContext = mappingContext;
    }

    @PostConstruct
    public void registerVectorStoreEntities() {
        // MongoDBDocument is an inner record without @Document/@Id annotations.
        // Spring Data MongoDB 5.x won't detect it as a persistent entity automatically,
        // causing mongoTemplate.save() to produce documents with only _id and _class.
        // Registering it here ensures all record components are mapped before any save.
        mappingContext.getPersistentEntity(MongoDBAtlasVectorStore.MongoDBDocument.class);
    }
}
