package it.iacovelli.nexabudgetbe.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.util.List;

public class GoogleGenAiRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Register AutoValue implementations that are likely package-private or hidden
        List<String> autoValueClasses = List.of(
                "com.google.genai.types.AutoValue_EmbedContentResponse",
                "com.google.genai.types.AutoValue_ContentEmbedding",
                "com.google.genai.types.AutoValue_ContentEmbeddingStatistics",
                "com.google.genai.types.AutoValue_Candidate",
                "com.google.genai.types.AutoValue_GenerateContentResponse",
                "com.google.genai.types.AutoValue_Part",
                "com.google.genai.types.AutoValue_VideoMetadata",
                "com.google.genai.types.AutoValue_GroundingMetadata",
                "com.google.genai.types.AutoValue_RetrievalMetadata",
                "com.google.genai.types.AutoValue_SafetyRating",
                "com.google.genai.types.AutoValue_GoogleRpcStatus",
                "com.google.genai.types.AutoValue_LogprobsResult",
                "com.google.genai.types.AutoValue_GroundingChunk",
                "com.google.genai.types.AutoValue_GroundingSupport",
                "com.google.genai.types.AutoValue_UsageMetadata",
                "com.google.genai.types.AutoValue_CitationMetadata");

        for (String className : autoValueClasses) {
            try {
                hints.reflection().registerType(TypeReference.of(className),
                        MemberCategory.values());
            } catch (Exception e) {
                // Ignore if class not found, but it should be there
            }
        }
    }
}
