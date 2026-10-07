package it.iacovelli.nexabudgetbe.config;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.HttpOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GenAiSdkConfig {

    @Value("${spring.ai.google.genai.api-key}")
    private String apiKey;

    /**
     * Tetto per singolo tentativo HTTP verso il modello (ogni turno del loop dei tool è una richiesta a sé; l'SDK
     * ritenta fino a 5 volte con backoff su timeout/429/5xx). Senza, l'SDK usa timeout 0 = infinito: una connessione
     * appesa blocca per sempre il report AI asincrono (job PENDING, nessun log, nessuna email) e il modello di
     * fallback non scatta mai.
     */
    @Value("${nexabudget.ai.http-timeout-seconds:120}")
    private int httpTimeoutSeconds;

    // Separate Client bean for direct SDK usage (chat, report, categorization).
    // GoogleGenAiConfig also instantiates its own Client internally for the Spring AI embedding stack.
    // Il bulk categorization gestisce in più il proprio timeout complessivo tramite CompletableFuture.get(aiCallTimeoutSeconds).
    @Bean
    public Client genaiClient() {
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().timeout(httpTimeoutSeconds * 1000).build())
                .build();
    }

    // Expose Models directly so services don't depend on the full Client and tests can mock it easily.
    @Bean
    public Models genaiModels(Client genaiClient) {
        return genaiClient.models;
    }
}
