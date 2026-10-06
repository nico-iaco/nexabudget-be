package it.iacovelli.nexabudgetbe.config;

import com.google.genai.Client;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient wiring for the Google GenAI (Gemini/Gemma) models used by NexaBot chat
 * and the AI report agent.
 *
 * Spring AI 2.0.0 does not ship a Spring Boot starter/autoconfiguration for the Google GenAI chat
 * model yet (only for embeddings and the MCP server), so {@link GoogleGenAiChatModel} is built by
 * hand here, reusing the {@link Client} bean already configured in {@link GenAiSdkConfig}.
 *
 * The bean-level options below are just a base; NexaBot ({@code ChatService}) and the AI report
 * agent ({@code AiReportService}) use different model names and thinking configurations, so each
 * overrides model/temperature/thinking per call via {@code GoogleGenAiChatOptions} passed to
 * {@code ChatClient.prompt().options(...)}.
 */
@Configuration
public class AiChatClientConfig {

    @Bean
    public GoogleGenAiChatModel googleGenAiChatModel(Client genaiClient) {
        return GoogleGenAiChatModel.builder()
                .genAiClient(genaiClient)
                .options(GoogleGenAiChatOptions.builder().build())
                .build();
    }

    @Bean
    public ChatClient chatClient(GoogleGenAiChatModel googleGenAiChatModel) {
        return ChatClient.builder(googleGenAiChatModel).build();
    }
}
