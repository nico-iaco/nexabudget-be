package it.iacovelli.nexabudgetbe.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

import java.util.Locale;

/**
 * Opzioni per-chiamata dei modelli Google GenAI usati da NexaBot ({@code ChatService}) e dal report AI
 * ({@code AiReportService}): modello, temperatura e configurazione di thinking.
 * <p>
 * Gemma 4 accetta solo {@code thinkingLevel}, i modelli Gemini usano {@code thinkingBudget}; gli altri modelli
 * non supportano il thinking e non ricevono alcuna configurazione.
 */
@Slf4j
public final class GenAiChatOptionsFactory {

    private GenAiChatOptionsFactory() {
    }

    public static GoogleGenAiChatOptions.Builder build(String modelName, double temperature, int thinkingBudget, String thinkingLevel) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature);

        if (modelName.startsWith("gemma-4-")) {
            GoogleGenAiThinkingLevel level = parseThinkingLevel(thinkingLevel);
            if (level != null) {
                builder.thinkingLevel(level).includeThoughts(false);
            }
        } else if (modelName.startsWith("gemini-")) {
            builder.thinkingBudget(thinkingBudget).includeThoughts(false);
        }
        return builder;
    }

    /** Valore non valido (es. refuso nella variabile d'ambiente) = default del modello, invece di far fallire ogni chiamata. */
    static GoogleGenAiThinkingLevel parseThinkingLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return GoogleGenAiThinkingLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("[GenAiChatOptionsFactory] Thinking level '{}' non valido, uso il default del modello", value);
            return null;
        }
    }
}
