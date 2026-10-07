package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.AiReportStatusResponse;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.chat.FinanceTools;
import it.iacovelli.nexabudgetbe.service.chat.GenAiChatOptionsFactory;
import it.iacovelli.nexabudgetbe.service.chat.TrackedToolCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportService {

    /** Per singola chiamata al modello (bozza e self-review hanno ciascuna il proprio tetto). */
    private static final int MAX_TOOL_CALLS = 25;

    /**
     * Heartbeat del job in esecuzione: se il pod muore (deploy, OOM, eviction) il job asincrono sparisce senza
     * scrivere FAILED e resterebbe PENDING per sempre. Un PENDING con heartbeat fermo da oltre {@link #HEARTBEAT_STALE_AFTER}
     * viene quindi dichiarato FAILED da {@link #getJobStatus}. La soglia tollera qualche battito perso.
     */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    static final Duration HEARTBEAT_STALE_AFTER = Duration.ofMinutes(3);

    @Value("${nexabudget.ai.report.model}")
    private String reportModelName;

    /** Usato se la chiamata al modello primario fallisce o risponde vuoto; vuoto = nessun fallback. */
    @Value("${nexabudget.ai.report.fallback-model:}")
    private String fallbackModelName;

    @Value("${nexabudget.ai.report.thinking-budget}")
    private int thinkingBudget;

    @Value("${nexabudget.ai.report.thinking-level}")
    private String thinkingLevel;

    @Value("${nexabudget.ai.report.self-review.enabled:true}")
    private boolean selfReviewEnabled;

    private final TransactionService transactionService;
    private final ChatClient chatClient;
    private final FinanceTools financeTools;
    private final CacheManager cacheManager;
    private final EmailService emailService;
    private final AiReportPdfService aiReportPdfService;
    private final TaskScheduler taskScheduler;

    private static final String SYSTEM_PROMPT = """
            Sei un consulente finanziario esperto. Il tuo compito è generare un report finanziario dettagliato e professionale per il periodo dal %s al %s.

            HAI A DISPOSIZIONE I SEGUENTI TOOL PER RECUPERARE DATI FINANZIARI AGGIORNATI DELL'UTENTE:
            - getAccountBalances: saldi e valuta di tutti i conti bancari
            - getPeriodTotals: totali entrate, uscite e netto del periodo
            - getCategoryBreakdown: breakdown spese/entrate per categoria nel periodo
            - getActiveBudgets: budget attivi con limite e speso
            - getRemainingBudgets: residuo disponibile per ciascun budget
            - getBudgetMonthlySummary: riepilogo mensile budget (speso/residuo/%%)
            - getMonthlyTrend: trend mensile entrate/uscite degli ultimi mesi
            - getBalanceTrend: andamento del saldo mese per mese
            - getMonthComparison: confronto mese corrente vs precedente
            - getMonthlyProjection: proiezione fine mese basata sul ritmo attuale
            - getCryptoPortfolio: valore portafoglio crypto
            - getTransactionsInPeriod: elenco grezzo delle transazioni in un intervallo di date
            - searchTransactions: ricerca transazioni con filtri (tipo, categoria, testo)
            - getTransactionsByCategory: tutte le transazioni di una categoria specifica

            ISTRUZIONI OPERATIVE:
            1. Usa i tool per raccogliere TUTTI i dati necessari del periodo %s - %s PRIMA di scrivere il report. Non inventare dati: usa esclusivamente ciò che i tool restituiscono.
            2. Recupera sia gli aggregati (totali, breakdown per categoria, trend) sia il dettaglio delle singole transazioni (getTransactionsInPeriod o searchTransactions) per identificare pattern ricorrenti, abbonamenti e anomalie.
            3. Combina i dati aggregati con i dettagli delle transazioni per produrre un'analisi profonda.

            IL REPORT DEVE INCLUDERE OBBLIGATORIAMENTE QUESTE 4 SEZIONI:
            1. **Riassunto Generale**: saldo totale del periodo, andamento entrate vs uscite, tasso di risparmio, confronto col mese precedente.
            2. **Analisi per Categoria**: categorie di spesa principali con importi e percentuali; valuta se qualcuna è eccessiva rispetto ai budget impostati.
            3. **Pattern e Anomalie**: spese ricorrenti, abbonamenti, transazioni insolite o anomale identificate tra le transazioni recuperate.
            4. **Suggerimenti di Miglioramento**: 3-5 consigli pratici e specifici basati ESCLUSIVAMENTE sui dati raccolti.

            REGOLE TASSATIVE PER L'OUTPUT:
            - Scrivi ESCLUSIVAMENTE nella lingua indicata dal codice ISO: %s. Nessun mix di lingue.
            - NON INCLUDERE log di ragionamento interno, "scratchpad", o passaggi intermedi.
            - FORNISCI DIRETTAMENTE ED ESCLUSIVAMENTE il report finale pronto per la lettura, formattato in Markdown.
            - Usa un tono professionale ma amichevole.
            """;

    private static final String SELF_REVIEW_PROMPT = """
            Sei un revisore finanziario. Di seguito trovi una bozza di report finanziario per il periodo dal %s al %s.
            Verifica quanto segue, usando nuovamente i tool a disposizione se necessario per ricontrollare i numeri:
            1. Coerenza numerica: i totali, le percentuali e i confronti citati sono plausibili e coerenti tra loro.
            2. Completezza: sono presenti tutte e 4 le sezioni obbligatorie (Riassunto Generale, Analisi per Categoria, Pattern e Anomalie, Suggerimenti di Miglioramento).
            3. Nessun dato inventato: ogni cifra deve provenire dai tool.

            Se trovi errori o sezioni mancanti, CORREGGI il report e restituisci la versione finale completa.
            Se il report è già corretto e completo, restituiscilo INVARIATO.
            Rispondi ESCLUSIVAMENTE con il report finale in Markdown, nella lingua %s, senza commenti sulla revisione stessa.

            BOZZA DA REVISIONARE:
            %s
            """;

    /**
     * Crea il job e ne restituisce lo stato iniziale: {@code COMPLETED} se il report del periodo è già nella cache
     * risultati, altrimenti {@code PENDING} (il chiamante deve allora avviare {@link #generateAiReport}).
     * Lo stato è restituito direttamente invece di essere riletto dalla cache subito dopo averlo scritto.
     */
    public AiReportStatusResponse startAiReportJob(User user, LocalDate startDate, LocalDate endDate, String language) {
        validateDateRange(startDate, endDate);

        String cacheKey = user.getId() + "_" + startDate + "_" + endDate + "_" + language;
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_RESULTS_CACHE);
        if (cache != null) {
            String cachedReport = cache.get(cacheKey, String.class);
            if (cachedReport != null) {
                UUID instantJobId = UUID.randomUUID();
                AiReportStatusResponse completed = new AiReportStatusResponse(instantJobId, "COMPLETED", cachedReport, startDate, endDate);
                saveJobStatus(instantJobId, user.getId(), completed);
                return completed;
            }
        }

        boolean hasTransactions = !transactionService.getTransactionsByUserAndDateRangeForReport(user, startDate, endDate).isEmpty();
        if (!hasTransactions) {
            throw new IllegalArgumentException("Nessuna transazione trovata nel periodo specificato");
        }

        UUID jobId = UUID.randomUUID();
        AiReportStatusResponse pending = new AiReportStatusResponse(jobId, "PENDING", null, startDate, endDate);
        // Prima dello stato: un PENDING senza heartbeat è considerato orfano
        touchHeartbeat(jobId);
        saveJobStatus(jobId, user.getId(), pending);

        return pending;
    }

    @Async
    public void generateAiReport(UUID jobId, User user, LocalDate startDate, LocalDate endDate, String language) {
        var authToken = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authToken);
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> touchHeartbeat(jobId), HEARTBEAT_INTERVAL);
        try {
            log.info("[AiReportService] Job {} avviato: periodo {} - {}, lingua {}, self-review {}",
                    jobId, startDate, endDate, language, selfReviewEnabled);
            String instruction = String.format(SYSTEM_PROMPT, startDate, endDate, startDate, endDate, language);

            String draftReport = callWithTools(instruction);

            String responseContent = selfReviewEnabled
                    ? selfReviewReport(draftReport, startDate, endDate, language)
                    : draftReport;

            String cacheKey = user.getId() + "_" + startDate + "_" + endDate + "_" + language;
            Cache resultsCache = cacheManager.getCache(CacheConfig.AI_REPORTS_RESULTS_CACHE);
            if (resultsCache != null) {
                resultsCache.put(cacheKey, responseContent);
            }

            saveJobStatus(jobId, user.getId(), new AiReportStatusResponse(jobId, "COMPLETED", responseContent, startDate, endDate));
            log.info("AI Report {} completed successfully", jobId);

            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                try {
                    byte[] pdfBytes = aiReportPdfService.buildReportPdf(user, startDate, endDate, responseContent);
                    String filename = aiReportPdfService.buildFilename(startDate, endDate);
                    emailService.sendAiReportEmail(user.getEmail(), user.getUsername(), startDate, endDate, pdfBytes, filename);
                } catch (Exception e) {
                    log.error("Errore generazione PDF AI report per job {}", jobId, e);
                }
            }

        } catch (Exception e) {
            log.error("Error generating AI report for job {}", jobId, e);
            saveJobStatus(jobId, user.getId(), new AiReportStatusResponse(jobId, "FAILED", null, startDate, endDate));
        } finally {
            heartbeat.cancel(false);
            SecurityContextHolder.clearContext();
        }
    }

    private String selfReviewReport(String draftReport, LocalDate startDate, LocalDate endDate, String language) {
        try {
            String reviewInstruction = String.format(SELF_REVIEW_PROMPT, startDate, endDate, language, draftReport);
            return callWithTools(reviewInstruction);
        } catch (Exception e) {
            log.warn("[AiReportService] Self-review fallita, uso la bozza originale: {}", e.getMessage());
            return draftReport;
        }
    }

    private String callWithTools(String instruction) {
        try {
            return callWithTools(instruction, reportModelName);
        } catch (RuntimeException e) {
            if (fallbackModelName == null || fallbackModelName.isBlank() || fallbackModelName.equals(reportModelName)) {
                throw e;
            }
            log.warn("[AiReportService] Modello primario {} fallito ({}), riprovo con il fallback {}",
                    reportModelName, e.getMessage(), fallbackModelName);
            return callWithTools(instruction, fallbackModelName);
        }
    }

    private String callWithTools(String instruction, String modelName) {
        log.info("[AiReportService] Chiamata al modello {}", modelName);
        long startedAt = System.currentTimeMillis();
        TrackedToolCallbacks tools = TrackedToolCallbacks.of(financeTools, MAX_TOOL_CALLS);
        String content = chatClient.prompt()
                .user(instruction)
                .toolCallbacks(tools.callbacks())
                .options(GenAiChatOptionsFactory.build(modelName, 0.4, thinkingBudget, thinkingLevel))
                .call()
                .content();
        log.info("[AiReportService] Modello {} ha risposto in {} ms, tool usati: {}",
                modelName, System.currentTimeMillis() - startedAt, tools.toolsUsed());
        // Una risposta vuota finirebbe in cache e nell'email come report COMPLETED
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Risposta vuota dal modello " + modelName);
        }
        return content;
    }

    public AiReportStatusResponse getJobStatus(UUID jobId, User user) {
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE);
        if (cache != null) {
            AiReportStatusResponse status = cache.get(jobId, AiReportStatusResponse.class);
            if (status != null) {
                String owner = cache.get("owner_" + jobId, String.class);
                // Owner mancante (es. chiave evicted) = accesso negato, non consentito a chiunque
                if (owner == null || !owner.equals(user.getId().toString())) {
                    throw new org.springframework.security.access.AccessDeniedException("Accesso non autorizzato al job");
                }
                if ("PENDING".equals(status.status()) && isHeartbeatStale(cache, jobId)) {
                    log.warn("[AiReportService] Job {} PENDING senza heartbeat da oltre {}: esecuzione interrotta (es. restart del pod), segnato FAILED",
                            jobId, HEARTBEAT_STALE_AFTER);
                    AiReportStatusResponse failed = new AiReportStatusResponse(jobId, "FAILED", null, status.startDate(), status.endDate());
                    saveJobStatus(jobId, user.getId(), failed);
                    return failed;
                }
                return status;
            }
        }
        throw new IllegalArgumentException("Job non trovato o scaduto");
    }

    private void saveJobStatus(UUID jobId, UUID userId, AiReportStatusResponse status) {
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE);
        if (cache != null) {
            cache.put(jobId, status);
            cache.put("owner_" + jobId, userId.toString());
        }
    }

    /** Non deve mai lanciare: un'eccezione in un task a frequenza fissa ne sopprime le esecuzioni successive. */
    private void touchHeartbeat(UUID jobId) {
        try {
            Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE);
            if (cache != null) {
                cache.put(heartbeatKey(jobId), Instant.now().toString());
            }
        } catch (Exception e) {
            log.warn("[AiReportService] Aggiornamento heartbeat fallito per job {}: {}", jobId, e.getMessage());
        }
    }

    private boolean isHeartbeatStale(Cache cache, UUID jobId) {
        String lastBeat = cache.get(heartbeatKey(jobId), String.class);
        // Heartbeat assente = job creato prima di questo meccanismo o chiave persa: nessuno lo sta eseguendo
        return lastBeat == null || Instant.parse(lastBeat).isBefore(Instant.now().minus(HEARTBEAT_STALE_AFTER));
    }

    private static String heartbeatKey(UUID jobId) {
        return "heartbeat_" + jobId;
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("La data di fine non può essere precedente alla data di inizio");
        }
        long monthsBetween = ChronoUnit.MONTHS.between(startDate, endDate);
        if (monthsBetween > 12) {
            throw new IllegalArgumentException("Il periodo richiesto non può superare 1 anno");
        }
    }
}
