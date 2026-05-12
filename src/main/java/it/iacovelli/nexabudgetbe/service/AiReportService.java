package it.iacovelli.nexabudgetbe.service;

import com.google.genai.Models;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import it.iacovelli.nexabudgetbe.config.CacheConfig;
import it.iacovelli.nexabudgetbe.dto.AiReportStatusResponse;
import it.iacovelli.nexabudgetbe.dto.TransactionDto.TransactionResponse;
import it.iacovelli.nexabudgetbe.dto.ReportDto;
import it.iacovelli.nexabudgetbe.model.TransactionType;
import it.iacovelli.nexabudgetbe.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiReportService {

    @Value("${nexabudget.ai.report.model}")
    private String reportModelName;

    @Value("${nexabudget.ai.report.thinking-budget}")
    private int thinkingBudget;

    private final TransactionService transactionService;
    private final Models genaiModels;
    private final CacheManager cacheManager;
    private final ReportService reportService;
    private final EmailService emailService;

    private static final String SYSTEM_PROMPT = """
            Sei un consulente finanziario esperto. Analizza le seguenti transazioni bancarie (in formato CSV) di un utente per il periodo dal %s al %s.

            TI FORNISCO INOLTRE QUESTO CONTESTO AGGIUNTIVO SUL PERIODO GIA RAPPRESENTATO NEI DATI (già calcolato a livello di sistema):
            %s

            IL TUO COMPITO È GENERARE UN REPORT MOLTO DETTAGLIATO CHE INCLUDA:
            1. **Riassunto Generale**: Saldo totale del periodo, andamento Entrate vs Uscite e tasso di risparmio.
            2. **Analisi per Categoria**: Evidenzia le categorie di spesa maggiori e valuta se sono eccessive. Usa i breakdown presi in contesto da abbreviare il conteggio manuale.
            3. **Pattern e Anomalie**: Individua comportamenti ripetitivi o spese anomale leggendo le occorrenze delle transazioni del periodo.
            4. **Suggerimenti di Miglioramento**: Dai 3-5 consigli pratici basati ESCLUSIVAMENTE sui dati forniti su come l'utente potrebbe ottimizzare le proprie finanze.

            REGOLE TASSATIVE PER L'OUTPUT:
            - Scrivi ESCLUSIVAMENTE in lingua Italiana. Nessun mix di lingue.
            - NON INCLUDERE log di ragionamento interno, "scratchpad", o passaggi intermedi di auto-correzione.
            - FORNISCI DIRETTAMENTE ED ESCLUSIVAMENTE il report finale pronto per la lettura da parte del cliente, formattato in Markdown leggibile.
            - Usa un tono professionale ma amichevole. Non includere calcoli errati.
            """;

    public UUID startAiReportJob(User user, LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);

        String cacheKey = user.getId() + "_" + startDate + "_" + endDate;
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_RESULTS_CACHE);
        if (cache != null) {
            String cachedReport = cache.get(cacheKey, String.class);
            if (cachedReport != null) {
                UUID instantJobId = UUID.randomUUID();
                saveJobStatus(instantJobId, new AiReportStatusResponse(instantJobId, "COMPLETED", cachedReport));
                return instantJobId;
            }
        }

        List<TransactionResponse> transactions = transactionService.getTransactionsByUserAndDateRangeForReport(user, startDate, endDate);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("Nessuna transazione trovata nel periodo specificato");
        }

        UUID jobId = UUID.randomUUID();
        saveJobStatus(jobId, new AiReportStatusResponse(jobId, "PENDING", null));

        return jobId;
    }

    @Async
    public void generateAiReport(UUID jobId, User user, LocalDate startDate, LocalDate endDate) {
        try {
            List<TransactionResponse> transactions = transactionService.getTransactionsByUserAndDateRangeForReport(user, startDate, endDate);
            byte[] csvBytes = generateCsv(transactions).getBytes(StandardCharsets.UTF_8);

            String contextData = buildAdditionalContext(user, startDate, endDate);
            String instruction = String.format(SYSTEM_PROMPT, startDate, endDate, contextData);

            Part textPart = Part.fromText(instruction);
            Part csvPart = Part.fromBytes(csvBytes, "text/csv");
            Content userContent = Content.builder()
                    .role("user")
                    .parts(List.of(textPart, csvPart))
                    .build();

            GenerateContentConfig.Builder cfgBuilder = GenerateContentConfig.builder()
                    .temperature(0.4f);

            if (supportsThinking(reportModelName)) {
                cfgBuilder.thinkingConfig(ThinkingConfig.builder()
                        .thinkingBudget(thinkingBudget)
                        .includeThoughts(false)
                        .build());
            }

            GenerateContentResponse resp = genaiModels.generateContent(
                    reportModelName, userContent, cfgBuilder.build());

            String responseContent = resp.text();

            Cache resultsCache = cacheManager.getCache(CacheConfig.AI_REPORTS_RESULTS_CACHE);
            if (resultsCache != null) {
                String cacheKey = user.getId() + "_" + startDate + "_" + endDate;
                resultsCache.put(cacheKey, responseContent);
            }

            saveJobStatus(jobId, new AiReportStatusResponse(jobId, "COMPLETED", responseContent));
            log.info("AI Report {} completed successfully", jobId);

            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                emailService.sendAiReportEmail(user.getEmail(), user.getUsername(), startDate, endDate, responseContent);
            }

        } catch (Exception e) {
            log.error("Error generating AI report for job {}", jobId, e);
            saveJobStatus(jobId, new AiReportStatusResponse(jobId, "FAILED", null));
        }
    }

    private String buildAdditionalContext(User user, LocalDate startDate, LocalDate endDate) {
        StringBuilder sb = new StringBuilder();
        try {
            var breakdown = reportService.getCategoryBreakdown(user, startDate, endDate);
            String cur = breakdown.getCurrency();

            BigDecimal totalOut = breakdown.getCategories().stream()
                    .filter(c -> c.getInferredType() == TransactionType.OUT)
                    .map(c -> c.getNet().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalIn = breakdown.getCategories().stream()
                    .filter(c -> c.getInferredType() == TransactionType.IN)
                    .map(c -> c.getNet().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal netBalance = totalIn.subtract(totalOut);

            sb.append("--- RIEPILOGO TOTALE PERIODO (").append(startDate).append(" al ").append(endDate).append(") - Valuta: ").append(cur).append(" ---\n");
            sb.append("Entrate Totali: ").append(totalIn).append(" ").append(cur).append("\n");
            sb.append("Uscite Totali: ").append(totalOut).append(" ").append(cur).append("\n");
            sb.append("Saldo Netto del Periodo: ").append(netBalance).append(" ").append(cur).append("\n\n");

            sb.append("--- RIEPILOGO USCITE PER CATEGORIA NEL PERIODO ---\n");
            for (var cat : breakdown.getCategories()) {
                if (cat.getInferredType() == TransactionType.OUT) {
                    sb.append("- ").append(cat.getCategoryName()).append(": ").append(cat.getNet().abs())
                      .append(" (").append(String.format(java.util.Locale.US, "%.2f", cat.getPercentage())).append("%)\n");
                }
            }
            sb.append("\n");

            sb.append("--- RIEPILOGO ENTRATE PER CATEGORIA NEL PERIODO ---\n");
            for (var cat : breakdown.getCategories()) {
                if (cat.getInferredType() == TransactionType.IN) {
                    sb.append("- ").append(cat.getCategoryName()).append(": ").append(cat.getNet())
                      .append(" (").append(String.format(java.util.Locale.US, "%.2f", cat.getPercentage())).append("%)\n");
                }
            }
            sb.append("\n");

            var monthComparison = reportService.getMonthComparison(user, endDate.getYear(), endDate.getMonthValue());
            sb.append("--- CONFRONTO MACRO MESE FINALE (").append(endDate.getMonthValue()).append("/").append(endDate.getYear()).append(") VS MESE PRECEDENTE ---\n");
            sb.append("Mese Corrente: Entrate=").append(monthComparison.getCurrentMonth().getIncome())
              .append(", Uscite=").append(monthComparison.getCurrentMonth().getExpense()).append("\n");
            sb.append("Mese Precedente: Entrate=").append(monthComparison.getPreviousMonth().getIncome())
              .append(", Uscite=").append(monthComparison.getPreviousMonth().getExpense()).append("\n");
            sb.append("Variazione: Entrate ").append(monthComparison.getIncomeChange().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
              .append(monthComparison.getIncomeChange())
              .append(", Uscite ").append(monthComparison.getExpenseChange().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
              .append(monthComparison.getExpenseChange()).append("\n\n");
        } catch (Exception e) {
            log.warn("Impossibile generare contesto aggiuntivo per l'AI job", e);
        }
        return sb.toString();
    }

    public AiReportStatusResponse getJobStatus(UUID jobId) {
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE);
        if (cache != null) {
            AiReportStatusResponse status = cache.get(jobId, AiReportStatusResponse.class);
            if (status != null) {
                return status;
            }
        }
        throw new IllegalArgumentException("Job non trovato o scaduto");
    }

    private void saveJobStatus(UUID jobId, AiReportStatusResponse status) {
        Cache cache = cacheManager.getCache(CacheConfig.AI_REPORTS_CACHE);
        if (cache != null) {
            cache.put(jobId, status);
        }
    }

    private static boolean supportsThinking(String modelName) {
        return modelName.startsWith("gemini-") || modelName.startsWith("gemma-4");
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
