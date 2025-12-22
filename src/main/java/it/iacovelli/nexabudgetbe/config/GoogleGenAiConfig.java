package it.iacovelli.nexabudgetbe.config;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentParameters;
import com.google.genai.types.EmbedContentResponse;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.retry.support.RetryTemplate;

@Configuration
@RegisterReflectionForBinding({ GoogleGenAiTextEmbeddingOptions.class, GoogleGenAiChatOptions.class,
                EmbedContentConfig.class,
                EmbedContentParameters.class, EmbedContentResponse.class })
public class GoogleGenAiConfig {

        @Value("${spring.ai.google.genai.api-key}")
        private String apiKey;

        @Value("${spring.ai.google.genai.chat.options.model}")
        private String modelName;

        @Value("${spring.ai.google.genai.chat.options.temperature}")
        private double temperature;

        @Value("${spring.ai.google.genai.embedding.text.options.model}")
        private String embeddingModelName;

        @Value("${spring.ai.google.genai.embedding.text.options.dimensions}")
        private Integer embeddingDimensions;

        @Bean
        public GoogleGenAiChatModel client() {
                Client apiClient = Client.builder()
                                .apiKey(apiKey)
                                .build();
                return new GoogleGenAiChatModel(
                                apiClient,
                                GoogleGenAiChatOptions.builder()
                                                .model(modelName)
                                                .temperature(temperature)
                                                .build(),
                                ToolCallingManager.builder()
                                                .build(),
                                RetryTemplate.defaultInstance(),
                                ObservationRegistry.create());
        }

        @Bean
        public EmbeddingModel embeddingModel() {
                GoogleGenAiEmbeddingConnectionDetails connectionDetails = GoogleGenAiEmbeddingConnectionDetails
                                .builder()
                                .apiKey(apiKey)
                                .build();

                GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                                .model(embeddingModelName)
                                .dimensions(embeddingDimensions)
                                .taskType(GoogleGenAiTextEmbeddingOptions.TaskType.SEMANTIC_SIMILARITY)
                                .build();

                return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
        }

}
