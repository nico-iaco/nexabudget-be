package com.google.genai.types;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleAbstractTypeResolver;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Registers explicit mappings between abstract Builder classes and their
 * AutoValue implementations.
 * This is required for Native Image because Jackson cannot automatically
 * discover the
 * package-private AutoValue implementations.
 */
public class NativeTypesRegistry {

    public static void registerTypes(ObjectMapper mapper) {
        SimpleAbstractTypeResolver resolver = new SimpleAbstractTypeResolver();

        // Embed Content Response
        resolver.addMapping(EmbedContentResponse.Builder.class, AutoValue_EmbedContentResponse.Builder.class);
        resolver.addMapping(ContentEmbedding.Builder.class, AutoValue_ContentEmbedding.Builder.class);
        resolver.addMapping(ContentEmbeddingStatistics.Builder.class,
                AutoValue_ContentEmbeddingStatistics.Builder.class);

        // Content & Parts
        resolver.addMapping(Content.Builder.class, AutoValue_Content.Builder.class);
        resolver.addMapping(Part.Builder.class, AutoValue_Part.Builder.class);
        resolver.addMapping(Blob.Builder.class, AutoValue_Blob.Builder.class);
        resolver.addMapping(FileData.Builder.class, AutoValue_FileData.Builder.class);
        resolver.addMapping(FunctionCall.Builder.class, AutoValue_FunctionCall.Builder.class);
        resolver.addMapping(FunctionResponse.Builder.class, AutoValue_FunctionResponse.Builder.class);
        resolver.addMapping(ExecutableCode.Builder.class, AutoValue_ExecutableCode.Builder.class);
        resolver.addMapping(CodeExecutionResult.Builder.class, AutoValue_CodeExecutionResult.Builder.class);

        // Metadata
        resolver.addMapping(VideoMetadata.Builder.class, AutoValue_VideoMetadata.Builder.class);
        resolver.addMapping(GroundingMetadata.Builder.class, AutoValue_GroundingMetadata.Builder.class);
        resolver.addMapping(RetrievalMetadata.Builder.class, AutoValue_RetrievalMetadata.Builder.class);
        resolver.addMapping(SafetyRating.Builder.class, AutoValue_SafetyRating.Builder.class);
        resolver.addMapping(CitationMetadata.Builder.class, AutoValue_CitationMetadata.Builder.class);
        resolver.addMapping(UsageMetadata.Builder.class, AutoValue_UsageMetadata.Builder.class);
        resolver.addMapping(GoogleRpcStatus.Builder.class, AutoValue_GoogleRpcStatus.Builder.class);
        resolver.addMapping(LogprobsResult.Builder.class, AutoValue_LogprobsResult.Builder.class);
        resolver.addMapping(GroundingChunk.Builder.class, AutoValue_GroundingChunk.Builder.class);
        resolver.addMapping(GroundingSupport.Builder.class, AutoValue_GroundingSupport.Builder.class);

        // Check potentially missing ones from grep
        // GroundingChunkWeb, GroundingChunkRetrievedContext, SearchEntryPoint
        try {
            resolver.addMapping(GroundingChunkWeb.Builder.class, AutoValue_GroundingChunkWeb.Builder.class);
        } catch (NoClassDefFoundError | Exception e) {
            /* ignore */ }

        try {
            resolver.addMapping(GroundingChunkRetrievedContext.Builder.class,
                    AutoValue_GroundingChunkRetrievedContext.Builder.class);
        } catch (NoClassDefFoundError | Exception e) {
            /* ignore */ }

        try {
            resolver.addMapping(SearchEntryPoint.Builder.class, AutoValue_SearchEntryPoint.Builder.class);
        } catch (NoClassDefFoundError | Exception e) {
            /* ignore */ }

        // Generate Content Response (Chat)
        resolver.addMapping(GenerateContentResponse.Builder.class, AutoValue_GenerateContentResponse.Builder.class);
        resolver.addMapping(Candidate.Builder.class, AutoValue_Candidate.Builder.class);

        SimpleModule module = new SimpleModule("NativeAutoValueModule");
        module.setAbstractTypes(resolver);
        mapper.registerModule(module);
    }
}
