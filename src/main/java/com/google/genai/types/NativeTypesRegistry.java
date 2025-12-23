package com.google.genai.types;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleAbstractTypeResolver;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registers explicit mappings between abstract Builder classes and their
 * AutoValue implementations.
 * This is required for Native Image because Jackson cannot automatically
 * discover the
 * package-private AutoValue implementations.
 */
public class NativeTypesRegistry {

    private static final Logger log = LoggerFactory.getLogger(NativeTypesRegistry.class);

    public static void registerTypes(ObjectMapper mapper) {
        SimpleAbstractTypeResolver resolver = new SimpleAbstractTypeResolver();

        // List of all abstract types that have an AutoValue implementation
        List<Class<?>> types = List.of(
                EmbedContentResponse.class,
                ContentEmbedding.class,
                ContentEmbeddingStatistics.class,
                Content.class,
                Part.class,
                Blob.class,
                FileData.class,
                FunctionCall.class,
                FunctionResponse.class,
                ExecutableCode.class,
                CodeExecutionResult.class,
                VideoMetadata.class,
                GroundingMetadata.class,
                RetrievalMetadata.class,
                SafetyRating.class,
                CitationMetadata.class,
                UsageMetadata.class,
                GoogleRpcStatus.class,
                LogprobsResult.class,
                GroundingChunk.class,
                GroundingSupport.class,
                EmbedContentMetadata.class,
                GenerateContentResponse.class,
                Candidate.class,
                ModalityTokenCount.class,
                Citation.class,
                RetrievalConfig.class,
                SafetySetting.class,
                GenerationConfig.class,
                VertexRagStore.class,
                VertexRagStoreRagResource.class,
                DynamicRetrievalConfig.class,
                GoogleSearchRetrieval.class,
                Tool.class,
                ToolConfig.class,
                FunctionCallingConfig.class);

        for (Class<?> type : types) {
            registerAutoValueBuilder(resolver, type);
        }

        // Try to register types that might be missing from classpath or name mismatch
        registerByName(resolver, "com.google.genai.types.GroundingChunkWeb");
        registerByName(resolver, "com.google.genai.types.GroundingChunkRetrievedContext");
        registerByName(resolver, "com.google.genai.types.SearchEntryPoint");
        registerByName(resolver, "com.google.genai.types.SegmentImageResponse");

        SimpleModule module = new SimpleModule("NativeAutoValueModule");
        module.setAbstractTypes(resolver);
        mapper.registerModule(module);

        log.info("Registered {} native AutoValue mappings", types.size());
    }

    private static void registerAutoValueBuilder(SimpleAbstractTypeResolver resolver, Class<?> type) {
        try {
            // Standard AutoValue naming: com.google.genai.types.AutoValue_Type$Builder
            String packageName = type.getPackageName();
            String simpleName = type.getSimpleName();
            String autoValueName = packageName + ".AutoValue_" + simpleName + "$Builder";

            // The abstract builder is usually Type.Builder
            Class<?> abstractBuilder = null;
            for (Class<?> inner : type.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("Builder")) {
                    abstractBuilder = inner;
                    break;
                }
            }

            if (abstractBuilder != null) {
                Class<?> concreteBuilder = Class.forName(autoValueName);
                unsafeRegister(resolver, abstractBuilder, concreteBuilder);
            }
        } catch (ClassNotFoundException e) {
            log.warn("Could not find AutoValue builder for {}: {}", type.getName(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to register AutoValue mapping for {}", type.getName(), e);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void unsafeRegister(SimpleAbstractTypeResolver resolver, Class abstractBuilder,
            Class concreteBuilder) {
        resolver.addMapping(abstractBuilder, concreteBuilder);
    }

    private static void registerByName(SimpleAbstractTypeResolver resolver, String className) {
        try {
            Class<?> type = Class.forName(className);
            registerAutoValueBuilder(resolver, type);
        } catch (ClassNotFoundException e) {
            // ignore
        }
    }
}
