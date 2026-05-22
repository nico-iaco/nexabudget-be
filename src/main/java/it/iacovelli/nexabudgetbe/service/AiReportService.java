package it.iacovelli.nexabudgetbe.service;

import com.google.genai.Models;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.AiReportStatusResponse;
import it.iacovelli.nexabudgetbe.dto.TransactionDto.TransactionResponse;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.service.chat.FinanceToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportService {

    private static final int MAX_TOOL_ITERATIONS = 10;

    @Value("${nexabudget.ai.report.model}")
    private String reportModelName;

    @Value("${nexabudget.ai.report.thinking-budget}")
    private int thinkingBudget;

    @Value("${nexabudget.ai.report.thinking-level}")
    private String thinkingLevel;

    private final TransactionService transactionService;
    private final Models genaiModels;
    private final CacheManager cacheManager;
    private final EmailService emailService;
    private final AiReportPdfService aiReportPdfService;
    private final FinanceToolRegistry financeToolRegistry;

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
            - searchTransactions: ricerca transazioni con filtri (tipo, categoria, testo)

            ISTRUZIONI OPERATIVE:
            1. Usa i tool per raccogliere tutti i dati necessari PRIMA di scrivere il report. Non inventare dati: usa esclusivamente ciò che i tool restituiscono.
            2. Il file CSV allegato contiene l'elenco grezzo delle transazioni del periodo — usalo per identificare pattern ricorrenti, anomalie e singole operazioni significative.
            3. Combina i dati aggregati dei tool con i dettagli del CSV per produrre un'analisi profonda.

            IL REPORT DEVE INCLUDERE OBBLIGATORIAMENTE QUESTE 4 SEZIONI:
            1. **Riassunto Generale**: saldo totale del periodo, andamento entrate vs uscite, tasso di risparmio, confronto col mese precedente.
            2. **Analisi per Categoria**: categorie di spesa principali con importi e percentuali; valuta se qualcuna è eccessiva rispetto ai budget impostati.
            3. **Pattern e Anomalie**: spese ricorrenti, abbonamenti, transazioni insolite o anomale identificate dal CSV.
            4. **Suggerimenti di Miglioramento**: 3-5 consigli pratici e specifici basati ESCLUSIVAMENTE sui dati raccolti.

            REGOLE TASSATIVE PER L'OUTPUT:
            - Scrivi ESCLUSIVAMENTE nella lingua indicata dal codice ISO: %s. Nessun mix di lingue.
            - NON INCLUDERE log di ragionamento interno, "scratchpad", o passaggi intermedi.
            - FORNISCI DIRETTAMENTE ED ESCLUSIVAMENTE il report finale pronto per la lettura, formattato in Markdown.
            - Usa un tono professionale ma amichevole.
            """;

    public UUID startAiReportJob(User user, LocalDate startDate, LocalDate endDate, String language) {
        validateDateRange(startDate, endDate);

        String cacheKey = user.getId() + "_" + startDate + "_" + endDate + "_" + language;
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_RESULTS_CACHE);
        if (cache != null) {
            String cachedReport = cache.get(cacheKey, String.class);
            if (cachedReport != null) {
                UUID instantJobId = UUID.randomUUID();
                saveJobStatus(instantJobId, user.getId(), new AiReportStatusResponse(instantJobId, "COMPLETED", cachedReport, startDate, endDate));
                return instantJobId;
            }
        }

        List<TransactionResponse> transactions = transactionService.getTransactionsByUserAndDateRangeForReport(user, startDate, endDate);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("Nessuna transazione trovata nel periodo specificato");
        }

        UUID jobId = UUID.randomUUID();
        saveJobStatus(jobId, user.getId(), new AiReportStatusResponse(jobId, "PENDING", null, startDate, endDate));

        return jobId;
    }

    @Async
    public void generateAiReport(UUID jobId, User user, LocalDate startDate, LocalDate endDate, String language) {
        var authToken = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authToken);
        try {
            List<TransactionResponse> transactions = transactionService.getTransactionsByUserAndDateRangeForReport(user, startDate, endDate);
            byte[] csvBytes = generateCsv(transactions).getBytes(StandardCharsets.UTF_8);

            String instruction = String.format(SYSTEM_PROMPT, startDate, endDate, language);

            Part textPart = Part.fromText(instruction);
            Part csvPart = Part.fromBytes(csvBytes, "text/csv");
            Content userContent = Content.builder()
                    .role("user")
                    .parts(List.of(textPart, csvPart))
                    .build();

            List<Content> contents = new ArrayList<>();
            contents.add(userContent);

            GenerateContentConfig.Builder cfgBuilder = GenerateContentConfig.builder()
                    .temperature(0.4f)
                    .tools(List.of(financeToolRegistry.buildFinanceTool()));

            if (supportsThinking(reportModelName)) {
                cfgBuilder.thinkingConfig(buildThinkingConfig(reportModelName, thinkingBudget, thinkingLevel));
            }

            GenerateContentConfig cfg = cfgBuilder.build();

            String responseContent = runToolLoop(contents, cfg);

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
            SecurityContextHolder.clearContext();
        }
    }

    private String runToolLoop(List<Content> contents, GenerateContentConfig cfg) {
        for (int iter = 0; iter < MAX_TOOL_ITERATIONS; iter++) {
            GenerateContentResponse resp = genaiModels.generateContent(reportModelName, contents, cfg);

            List<FunctionCall> calls = resp.functionCalls();
            if (calls == null || calls.isEmpty()) {
                return resp.text();
            }

            resp.candidates()
                    .flatMap(cs -> cs.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(cs.get(0)))
                    .flatMap(c -> c.content())
                    .ifPresent(contents::add);

            List<Part> responseParts = new ArrayList<>();
            for (FunctionCall fc : calls) {
                String name = fc.name().orElse("unknown");
                Map<String, Object> args = fc.args().orElse(Map.of());
                log.debug("[AiReportService] Tool invocato: {} con args: {}", name, args);
                String toolResult = financeToolRegistry.dispatchTool(name, args);
                responseParts.add(Part.fromFunctionResponse(name, Map.of("result", toolResult)));
            }

            contents.add(Content.builder().role("user").parts(responseParts).build());
        }

        log.warn("[AiReportService] Raggiunto il limite di {} iterazioni tool calling per il report", MAX_TOOL_ITERATIONS);
        GenerateContentResponse finalResp = genaiModels.generateContent(reportModelName, contents, cfg);
        return finalResp.text();
    }

    public AiReportStatusResponse getJobStatus(UUID jobId, User user) {
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE);
        if (cache != null) {
            AiReportStatusResponse status = cache.get(jobId, AiReportStatusResponse.class);
            if (status != null) {
                String owner = cache.get("owner_" + jobId, String.class);
                if (owner != null && !owner.equals(user.getId().toString())) {
                    throw new org.springframework.security.access.AccessDeniedException("Accesso non autorizzato al job");
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

    private static boolean supportsThinking(String modelName) {
        return modelName.startsWith("gemini-") || modelName.startsWith("gemma-4-");
    }

    private static ThinkingConfig buildThinkingConfig(String modelName, int budget, String level) {
        if (modelName.startsWith("gemma-4-")) {
            return ThinkingConfig.builder().thinkingLevel(level).includeThoughts(false).build();
        }
        return ThinkingConfig.builder().thinkingBudget(budget).includeThoughts(false).build();
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

    private String generateCsv(List<TransactionResponse> transactions) throws Exception {
        StringWriter sw = new StringWriter();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader("Data", "Importo", "Tipo", "Categoria", "Descrizione")
                .build();

        try (CSVPrinter printer = new CSVPrinter(sw, format)) {
            for (TransactionResponse tx : transactions) {
                printer.printRecord(
                        tx.getDate(),
                        tx.getAmount(),
                        tx.getType(),
                        tx.getCategoryName() != null ? tx.getCategoryName() : "N/D",
                        tx.getDescription()
                );
            }
        }
        return sw.toString();
    }
}
