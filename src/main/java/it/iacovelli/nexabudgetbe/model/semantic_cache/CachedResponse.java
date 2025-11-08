package it.iacovelli.nexabudgetbe.model.semantic_cache;

import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "nexabudget-category-cache")
public class CachedResponse {

    @Id
    private String id;

    private String promptText;
    private String geminiResponse;
    private List<Double> embedding;

    public CachedResponse(String promptText, String geminiResponse, List<Double> embedding) {
        this.promptText = promptText;
        this.geminiResponse = geminiResponse;
        this.embedding = embedding;
    }

}
