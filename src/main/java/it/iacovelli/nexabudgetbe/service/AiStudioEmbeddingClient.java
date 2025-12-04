package it.iacovelli.nexabudgetbe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AiStudioEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(AiStudioEmbeddingClient.class);

    private final EmbeddingModel embeddingModel;

    public AiStudioEmbeddingClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<Double> getEmbedding(String text) {
        float[] embeddingsArray = embeddingModel.embed(text);

        if (embeddingsArray.length > 0) {
            List<Double> embeddings = new ArrayList<>();
            for (float v : embeddingsArray) {
                embeddings.add((double) v);
            }
            log.debug("Embeddings generated successfully");
            return embeddings;
        }

        throw new RuntimeException("Nessun embedding generato per il testo fornito");
    }
}
