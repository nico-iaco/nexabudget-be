package it.iacovelli.nexabudgetbe.config;

import com.google.genai.Client;
import com.google.genai.Models;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GenAiSdkConfig {

    @Value("${spring.ai.google.genai.api-key}")
    private String apiKey;

    // Separate Client bean for direct SDK usage (chat, report, categorization).
    // GoogleGenAiConfig also instantiates its own Client internally for the Spring AI embedding stack.
    @Bean
    public Client genaiClient() {
        return Client.builder()
                .apiKey(apiKey)
                .build();
    }

    // Expose Models directly so services don't depend on the full Client and tests can mock it easily.
    @Bean
    public Models genaiModels(Client genaiClient) {
        return genaiClient.models;
    }
}
