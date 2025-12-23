package it.iacovelli.nexabudgetbe.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.util.List;

public class GoogleGenAiRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Broad registration of AutoValue implementations
        List<String> autoValueClasses = List.of(
                // Core Response Types
                "com.google.genai.types.AutoValue_EmbedContentResponse",
                "com.google.genai.types.AutoValue_ContentEmbedding",
                "com.google.genai.types.AutoValue_ContentEmbedding$Builder",
                "com.google.genai.types.AutoValue_ContentEmbeddingStatistics",
                "com.google.genai.types.AutoValue_ContentEmbeddingStatistics$Builder",
                "com.google.genai.types.AutoValue_GenerateContentResponse",
                "com.google.genai.types.AutoValue_GenerateContentResponse$Builder",
                "com.google.genai.types.AutoValue_Candidate",
                "com.google.genai.types.AutoValue_Candidate$Builder",

                // Parts & Content
                "com.google.genai.types.AutoValue_Content",
                "com.google.genai.types.AutoValue_Content$Builder",
                "com.google.genai.types.AutoValue_Part",
                "com.google.genai.types.AutoValue_Part$Builder",
                "com.google.genai.types.AutoValue_Blob",
                "com.google.genai.types.AutoValue_Blob$Builder",
                "com.google.genai.types.AutoValue_FileData",
                "com.google.genai.types.AutoValue_FileData$Builder",
                "com.google.genai.types.AutoValue_FunctionCall",
                "com.google.genai.types.AutoValue_FunctionCall$Builder",
                "com.google.genai.types.AutoValue_FunctionResponse",
                "com.google.genai.types.AutoValue_FunctionResponse$Builder",
                "com.google.genai.types.AutoValue_ExecutableCode",
                "com.google.genai.types.AutoValue_ExecutableCode$Builder",
                "com.google.genai.types.AutoValue_CodeExecutionResult",
                "com.google.genai.types.AutoValue_CodeExecutionResult$Builder",

                // Metadata Types
                "com.google.genai.types.AutoValue_VideoMetadata",
                "com.google.genai.types.AutoValue_VideoMetadata$Builder",
                "com.google.genai.types.AutoValue_GroundingMetadata",
                "com.google.genai.types.AutoValue_GroundingMetadata$Builder",
                "com.google.genai.types.AutoValue_RetrievalMetadata",
                "com.google.genai.types.AutoValue_RetrievalMetadata$Builder",
                "com.google.genai.types.AutoValue_SafetyRating",
                "com.google.genai.types.AutoValue_SafetyRating$Builder",
                "com.google.genai.types.AutoValue_CitationMetadata",
                "com.google.genai.types.AutoValue_CitationMetadata$Builder",
                "com.google.genai.types.AutoValue_UsageMetadata",
                "com.google.genai.types.AutoValue_UsageMetadata$Builder",
                "com.google.genai.types.AutoValue_GoogleRpcStatus",
                "com.google.genai.types.AutoValue_GoogleRpcStatus$Builder",
                "com.google.genai.types.AutoValue_LogprobsResult",
                "com.google.genai.types.AutoValue_LogprobsResult$Builder",
                "com.google.genai.types.AutoValue_GroundingChunk",
                "com.google.genai.types.AutoValue_GroundingChunk$Builder",
                "com.google.genai.types.AutoValue_GroundingSupport",
                "com.google.genai.types.AutoValue_GroundingSupport$Builder",

                // Nested Types often missed
                "com.google.genai.types.AutoValue_GroundingChunkWeb",
                "com.google.genai.types.AutoValue_GroundingChunkWeb$Builder",
                "com.google.genai.types.AutoValue_GroundingChunkRetrievedContext",
                "com.google.genai.types.AutoValue_GroundingChunkRetrievedContext$Builder",
                "com.google.genai.types.AutoValue_SearchEntryPoint",
                "com.google.genai.types.AutoValue_SearchEntryPoint$Builder",
                "com.google.genai.types.AutoValue_SegmentImageResponse",
                "com.google.genai.types.AutoValue_SegmentImageResponse$Builder");

        for (String className : autoValueClasses) {
            try {
                // Registering MemberCategory.values() covers constructors, methods, fields,
                // etc.
                hints.reflection().registerType(TypeReference.of(className),
                        MemberCategory.values());
            } catch (Exception e) {
                // Ignore missing classes, proceed with others
            }
        }
    }
}
