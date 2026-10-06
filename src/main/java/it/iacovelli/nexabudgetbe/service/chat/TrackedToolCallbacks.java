package it.iacovelli.nexabudgetbe.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tool di un oggetto {@code @Tool} (es. {@link FinanceTools}) avvolti per una singola richiesta al modello.
 * <p>
 * Il loop di tool calling di Spring AI ({@code ToolCallingManager}) non espone i nomi dei tool eseguiti né
 * prevede un limite di iterazioni: questo wrapper registra ogni invocazione ({@link #toolsUsed()}) e, superate
 * {@code maxCalls} chiamate, smette di eseguire i tool restituendo al modello un messaggio che lo invita a
 * rispondere con i dati già raccolti — così una richiesta non può ciclare all'infinito.
 * <p>
 * Va creato un'istanza per richiesta (contatore e lista non sono condivisibili tra richieste).
 */
@Slf4j
public final class TrackedToolCallbacks {

    private final List<String> toolsUsed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger calls = new AtomicInteger();
    private final int maxCalls;
    private final ToolCallback[] callbacks;

    private TrackedToolCallbacks(Object toolObject, int maxCalls) {
        this.maxCalls = maxCalls;
        this.callbacks = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(toolObject).build().getToolCallbacks())
                .map(Tracked::new)
                .toArray(ToolCallback[]::new);
    }

    public static TrackedToolCallbacks of(Object toolObject, int maxCalls) {
        return new TrackedToolCallbacks(toolObject, maxCalls);
    }

    public ToolCallback[] callbacks() {
        return callbacks;
    }

    /** Nomi dei tool effettivamente eseguiti, in ordine di invocazione (le chiamate oltre il limite sono escluse). */
    public List<String> toolsUsed() {
        synchronized (toolsUsed) {
            return List.copyOf(toolsUsed);
        }
    }

    private final class Tracked implements ToolCallback {

        private final ToolCallback delegate;

        private Tracked(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String name = delegate.getToolDefinition().name();
            if (calls.incrementAndGet() > maxCalls) {
                log.warn("[TrackedToolCallbacks] Raggiunto il limite di {} chiamate ai tool, '{}' non eseguito", maxCalls, name);
                return "Limite di chiamate ai tool raggiunto: non richiedere altri tool e rispondi usando esclusivamente i dati già raccolti.";
            }
            toolsUsed.add(name);
            log.debug("[TrackedToolCallbacks] Tool invocato: {} con args: {}", name, toolInput);
            return toolContext != null ? delegate.call(toolInput, toolContext) : delegate.call(toolInput);
        }
    }
}
