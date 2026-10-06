package it.iacovelli.nexabudgetbe.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrackedToolCallbacksTest {

    static class SampleTools {
        int executions = 0;

        @Tool(name = "ping", description = "ping")
        public String ping() {
            executions++;
            return "pong";
        }
    }

    private static ToolCallback ping(TrackedToolCallbacks tools) {
        return Arrays.stream(tools.callbacks())
                .filter(c -> c.getToolDefinition().name().equals("ping"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void recordsInvokedToolNames() {
        SampleTools sample = new SampleTools();
        TrackedToolCallbacks tools = TrackedToolCallbacks.of(sample, 5);

        String result = ping(tools).call("{}");

        assertTrue(result.contains("pong"));
        assertEquals(List.of("ping"), tools.toolsUsed());
        assertEquals(1, sample.executions);
    }

    @Test
    void stopsExecutingToolsOverTheLimit() {
        SampleTools sample = new SampleTools();
        TrackedToolCallbacks tools = TrackedToolCallbacks.of(sample, 2);
        ToolCallback ping = ping(tools);

        ping.call("{}");
        ping.call("{}");
        String third = ping.call("{}");

        assertTrue(third.startsWith("Limite di chiamate ai tool raggiunto"));
        assertEquals(2, sample.executions);
        assertEquals(List.of("ping", "ping"), tools.toolsUsed());
    }

    @Test
    void parseThinkingLevel_isLenient() {
        assertEquals(GoogleGenAiThinkingLevel.MINIMAL, GenAiChatOptionsFactory.parseThinkingLevel("MINIMAL"));
        assertEquals(GoogleGenAiThinkingLevel.LOW, GenAiChatOptionsFactory.parseThinkingLevel(" low "));
        assertNull(GenAiChatOptionsFactory.parseThinkingLevel("MINIMALE"));
        assertNull(GenAiChatOptionsFactory.parseThinkingLevel(""));
    }
}
