package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.AiReportStatusResponse;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.chat.FinanceTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Se il modello primario del report AI fallisce (eccezione o risposta vuota) la stessa chiamata
 * viene ripetuta una volta con il modello di fallback.
 */
class AiReportServiceFallbackTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
    private final ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
    private final List<String> modelsCalled = new ArrayList<>();

    private AiReportService service;
    private User user;

    @BeforeEach
    void setUp() {
        FinanceTools financeTools = new FinanceTools(
                mock(AccountService.class), mock(TransactionService.class), mock(BudgetService.class),
                mock(CategoryService.class), mock(ReportService.class), mock(CryptoPortfolioService.class),
                mock(CurrencyConversionService.class), mock(ExchangeRateService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        service = new AiReportService(mock(TransactionService.class), chatClient, financeTools, cacheManager,
                mock(EmailService.class), mock(AiReportPdfService.class));
        ReflectionTestUtils.setField(service, "reportModelName", "gemini-3-flash-preview");
        ReflectionTestUtils.setField(service, "fallbackModelName", "gemini-2.5-flash");
        ReflectionTestUtils.setField(service, "thinkingBudget", -1);
        ReflectionTestUtils.setField(service, "thinkingLevel", "MINIMAL");
        ReflectionTestUtils.setField(service, "selfReviewEnabled", false);

        user = User.builder().username("reportuser").passwordHash("hash").defaultCurrency("EUR").build();
        user.setId(UUID.randomUUID());

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        doAnswer(inv -> {
            modelsCalled.add(((GoogleGenAiChatOptions.Builder) inv.getArgument(0)).build().getModel());
            return spec;
        }).when(spec).options(any());
    }

    private AiReportStatusResponse runReport() {
        UUID jobId = UUID.randomUUID();
        service.generateAiReport(jobId, user, START, END, "it");
        return cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE).get(jobId, AiReportStatusResponse.class);
    }

    @Test
    void primarySucceeds_fallbackNotCalled() {
        when(call.content()).thenReturn("# Report");

        AiReportStatusResponse status = runReport();

        assertEquals("COMPLETED", status.status());
        assertEquals("# Report", status.content());
        assertEquals(List.of("gemini-3-flash-preview"), modelsCalled);
    }

    @Test
    void primaryThrows_fallbackUsed() {
        when(call.content()).thenThrow(new RuntimeException("503 overloaded")).thenReturn("# Report fallback");

        AiReportStatusResponse status = runReport();

        assertEquals("COMPLETED", status.status());
        assertEquals("# Report fallback", status.content());
        assertEquals(List.of("gemini-3-flash-preview", "gemini-2.5-flash"), modelsCalled);
    }

    @Test
    void primaryEmpty_fallbackUsed() {
        when(call.content()).thenReturn("  ").thenReturn("# Report fallback");

        AiReportStatusResponse status = runReport();

        assertEquals("COMPLETED", status.status());
        assertEquals("# Report fallback", status.content());
        assertEquals(List.of("gemini-3-flash-preview", "gemini-2.5-flash"), modelsCalled);
    }

    @Test
    void bothFail_jobFailed() {
        when(call.content()).thenThrow(new RuntimeException("503 overloaded"));

        AiReportStatusResponse status = runReport();

        assertEquals("FAILED", status.status());
        assertEquals(List.of("gemini-3-flash-preview", "gemini-2.5-flash"), modelsCalled);
    }

    @Test
    void noFallbackConfigured_jobFailedAfterPrimary() {
        ReflectionTestUtils.setField(service, "fallbackModelName", "");
        when(call.content()).thenThrow(new RuntimeException("503 overloaded"));

        AiReportStatusResponse status = runReport();

        assertEquals("FAILED", status.status());
        assertEquals(List.of("gemini-3-flash-preview"), modelsCalled);
    }
}
