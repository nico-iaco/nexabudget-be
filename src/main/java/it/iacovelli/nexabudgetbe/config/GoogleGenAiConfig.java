package it.iacovelli.nexabudgetbe.config;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
@RegisterReflectionForBinding({ GoogleGenAiTextEmbeddingOptions.class, GoogleGenAiChatOptions.class })
public class GoogleGenAiConfig {

        @Value("${spring.ai.google.genai.api-key}")
        private String apiKey;

        @Value("${spring.ai.google.genai.chat.options.model}")
        private String modelName;

        @Value("${spring.ai.google.genai.chat.options.temperature}")
        private double temperature;

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

}
