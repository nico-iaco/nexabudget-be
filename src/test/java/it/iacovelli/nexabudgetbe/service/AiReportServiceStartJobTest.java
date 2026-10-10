package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.AiReportStatusResponse;
import it.iacovelli.nexabudgetbe.dto.TransactionDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.chat.FinanceTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@code startAiReportJob()} restituisce lo stato iniziale del job: il controller non deve rileggerlo dalla cache
 * subito dopo la scrittura (in ambiente la rilettura immediata restituiva null → 400 "Job non trovato o scaduto").
 * Un job PENDING il cui heartbeat è fermo (pod riavviato durante la generazione) viene dichiarato FAILED.
 */
class AiReportServiceStartJobTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private final TransactionService transactionService = mock(TransactionService.class);
    private final CacheManager cacheManager = mock(CacheManager.class);
    private final Cache jobsCache = spy(new ConcurrentMapCache(CacheConfig.AI_REPORTS_CACHE));
    private final Cache resultsCache = new ConcurrentMapCache(CacheConfig.AI_REPORTS_RESULTS_CACHE);

    private AiReportService service;
    private User user;

    @BeforeEach
    void setUp() {
        FinanceTools financeTools = new FinanceTools(
                mock(AccountService.class), mock(TransactionService.class), mock(BudgetService.class),
                mock(CategoryService.class), mock(ReportService.class), mock(CryptoPortfolioService.class),
                mock(InvestmentPortfolioService.class), mock(NetWorthService.class),
                mock(CurrencyConversionService.class), mock(ExchangeRateService.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
        service = new AiReportService(transactionService, mock(ChatClient.class), financeTools, cacheManager,
                mock(EmailService.class), mock(AiReportPdfService.class), mock(TaskScheduler.class));
        when(cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE)).thenReturn(jobsCache);
        when(cacheManager.getCache(CacheConfig.AI_REPORTS_RESULTS_CACHE)).thenReturn(resultsCache);

        user = User.builder().username("reportuser").passwordHash("hash").defaultCurrency("EUR").build();
        user.setId(UUID.randomUUID());
    }

    @Test
    void noCachedReport_returnsPendingWithoutReadingJobBack() {
        when(transactionService.getTransactionsByUserAndDateRangeForReport(user, START, END))
                .thenReturn(List.of(mock(TransactionDto.TransactionResponse.class)));

        AiReportStatusResponse status = service.startAiReportJob(user, START, END, "it");

        assertEquals("PENDING", status.status());
        assertNotNull(status.jobId());
        assertEquals(START, status.startDate());
        assertEquals(END, status.endDate());
        verify(jobsCache).put(status.jobId(), status);
        verify(jobsCache, never()).get(status.jobId(), AiReportStatusResponse.class);
    }

    @Test
    void cachedReport_returnsCompletedWithContent() {
        resultsCache.put(user.getId() + "_" + START + "_" + END + "_it", "# Report");

        AiReportStatusResponse status = service.startAiReportJob(user, START, END, "it");

        assertEquals("COMPLETED", status.status());
        assertEquals("# Report", status.content());
        verify(jobsCache).put(status.jobId(), status);
        verifyNoInteractions(transactionService);
    }

    @Test
    void noTransactions_throwsIllegalArgument() {
        when(transactionService.getTransactionsByUserAndDateRangeForReport(user, START, END)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.startAiReportJob(user, START, END, "it"));
        verify(jobsCache, never()).put(any(), any());
    }

    @Test
    void newPendingJob_hasFreshHeartbeat_staysPending() {
        when(transactionService.getTransactionsByUserAndDateRangeForReport(user, START, END))
                .thenReturn(List.of(mock(TransactionDto.TransactionResponse.class)));
        AiReportStatusResponse started = service.startAiReportJob(user, START, END, "it");

        AiReportStatusResponse status = service.getJobStatus(started.jobId(), user);

        assertEquals("PENDING", status.status());
    }

    @Test
    void pendingJobWithStaleHeartbeat_isMarkedFailed() {
        UUID jobId = pendingJob();
        jobsCache.put("heartbeat_" + jobId,
                Instant.now().minus(AiReportService.HEARTBEAT_STALE_AFTER).minusSeconds(1).toString());

        AiReportStatusResponse status = service.getJobStatus(jobId, user);

        assertEquals("FAILED", status.status());
        assertEquals(START, status.startDate());
        assertEquals("FAILED", jobsCache.get(jobId, AiReportStatusResponse.class).status());
    }

    @Test
    void pendingJobWithoutHeartbeat_isMarkedFailed() {
        UUID jobId = pendingJob();

        assertEquals("FAILED", service.getJobStatus(jobId, user).status());
    }

    @Test
    void completedJobWithoutHeartbeat_isReturnedAsIs() {
        UUID jobId = UUID.randomUUID();
        jobsCache.put(jobId, new AiReportStatusResponse(jobId, "COMPLETED", "# Report", START, END));
        jobsCache.put("owner_" + jobId, user.getId().toString());

        assertEquals("COMPLETED", service.getJobStatus(jobId, user).status());
    }

    /** Job PENDING scritto direttamente in cache, senza heartbeat (es. creato prima di un restart). */
    private UUID pendingJob() {
        UUID jobId = UUID.randomUUID();
        jobsCache.put(jobId, new AiReportStatusResponse(jobId, "PENDING", null, START, END));
        jobsCache.put("owner_" + jobId, user.getId().toString());
        return jobId;
    }
}
