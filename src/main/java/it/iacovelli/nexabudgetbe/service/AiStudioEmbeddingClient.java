package it.iacovelli.nexabudgetbe.service;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AiStudioEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(AiStudioEmbeddingClient.class);

    private final Client client;

    private final String MODEL_NAME = "models/gemini-embedding-001";

    public AiStudioEmbeddingClient(@Value("${google.ai.apiKey}") String apiKey) {
        this.client = Client.builder().apiKey(apiKey).build();
    }

    public List<Double> getEmbedding(String text) {

        EmbedContentConfig embedContentConfig = EmbedContentConfig
                .builder()
                .build();

        Optional<List<ContentEmbedding>> response = client.models
                .embedContent(MODEL_NAME, text, embedContentConfig)
                .embeddings();

        if (response.isPresent() && !response.get().isEmpty()) {
            ContentEmbedding embedding = response.get().getFirst();
            log.debug("Embedding: {}", embedding);

            if (embedding.values().isPresent()) {
                return embedding.values().get().stream()
                        .map(Double::valueOf)
                        .collect(Collectors.toList());
            }

        }

        throw new RuntimeException("Nessun embedding generato per il testo fornito");
    }
}
