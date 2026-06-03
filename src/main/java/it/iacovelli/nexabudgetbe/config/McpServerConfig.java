package it.iacovelli.nexabudgetbe.config;

import it.iacovelli.nexabudgetbe.service.chat.FinanceTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Espone i 19 tool finanziari read-only di {@link FinanceTools} come tool MCP.
 * <p>
 * L'autoconfiguration di {@code spring-ai-starter-mcp-server-webmvc} raccoglie
 * automaticamente tutti i bean {@link ToolCallbackProvider} presenti nel contesto
 * e li pubblica sull'endpoint {@code /mcp} (Streamable HTTP).
 * <p>
 * L'identità dell'utente è ricavata da {@code SecurityContextHolder} all'interno
 * di {@code FinanceTools.currentUser()}: funziona correttamente perché il protocollo
 * STATELESS esegue ogni chiamata tool sullo stesso thread della richiesta HTTP autenticata.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider financeMcpTools(FinanceTools financeTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(financeTools)
                .build();
    }
}
